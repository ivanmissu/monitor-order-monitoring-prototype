-- ============================================================================
--  Monitor ClickHouse DDL
--  部署：1 分片 × 2 副本（8C/32G/500G SSD ×2）
--  容量：≈250 万事件/天（10 万单 × 25 事件），ODS 压缩后 <100MB/天，180 天 <20GB
-- ============================================================================

CREATE DATABASE IF NOT EXISTS monitor;

-- ─────────────────────────── ODS 明细层（真相之源） ──────────────────────────
CREATE TABLE IF NOT EXISTS monitor.ods_order_event
(
    event_id        UInt64,
    event_type      LowCardinality(String),
    event_time      DateTime64(3),
    ingest_time     DateTime64(3),
    order_id        UInt64,
    trip_id         String,
    city_id         UInt64,
    seat_type       LowCardinality(String),
    biz_line        LowCardinality(String),
    driver_id_hash  String,
    amount          Int64,
    props           String CODEC(ZSTD(3)),
    dt              Date MATERIALIZED toDate(event_time, 'Asia/Shanghai'),
    version         UInt64 DEFAULT 1,

    -- 客服按 order_id 查询走此索引，配合分区裁剪保证 P99 < 3s
    INDEX bf_order   order_id       TYPE bloom_filter(0.01) GRANULARITY 4,
    INDEX bf_driver  driver_id_hash TYPE bloom_filter(0.01) GRANULARITY 4
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/ods_order_event', '{replica}', version)
PARTITION BY dt
ORDER BY (event_type, city_id, event_time, event_id)
TTL dt + INTERVAL 180 DAY
SETTINGS index_granularity = 8192;

-- 死信表：字典校验不通过的事件，不污染正式数据
CREATE TABLE IF NOT EXISTS monitor.ods_dirty_event
(
    dt          Date DEFAULT today(),
    received_at DateTime DEFAULT now(),
    event_type  LowCardinality(String),
    reason      LowCardinality(String),  -- EVENT_TYPE_UNKNOWN / REQUIRED_DIM_MISSING / PII_DETECTED ...
    raw_head    String CODEC(ZSTD(3)),
    producer    String
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/ods_dirty_event', '{replica}')
PARTITION BY dt ORDER BY (dt, reason, event_type)
TTL dt + INTERVAL 30 DAY;

-- T+1 对账缺口明细
CREATE TABLE IF NOT EXISTS monitor.ods_state_gap
(
    dt                  Date,
    order_id            UInt64,
    biz_line            LowCardinality(String),
    from_state          LowCardinality(String),
    to_state            LowCardinality(String),
    missing_event_type  LowCardinality(String),
    gap_sec             UInt32,
    likely_reason       String
)
ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/ods_state_gap', '{replica}')
PARTITION BY dt ORDER BY (dt, biz_line, missing_event_type)
TTL dt + INTERVAL 180 DAY;

-- ─────────────────────────── 聚合层（可加性原子指标） ─────────────────────────
-- 存储原则：只存 ① 事件明细 ② 可加性原子指标。
-- 比率一律查询期由分子/分母派生，不落库 —— 比率不可加、均值的均值是错的、
-- 口径变更无需回刷、告警阈值只改字典不改管道。
-- 例外：唯一数与分位数以「可合并状态列」入表（uniqCombinedState / quantilesTDigestState）。

CREATE TABLE IF NOT EXISTS monitor.agg_5m
(
    minute                  DateTime,
    city_id                 UInt64,
    seat_type               LowCardinality(String),
    biz_line                LowCardinality(String),
    -- 取消域维度列（非取消指标填 '-'），避免为 8 种组合各设一列
    cancel_by               LowCardinality(String) DEFAULT '-',
    cancel_stage            LowCardinality(String) DEFAULT '-',
    fault                   LowCardinality(String) DEFAULT '-',
    -- 资金域 / 风控域 / 接口域维度列
    fail_code               LowCardinality(String) DEFAULT '-',
    rule_id                 LowCardinality(String) DEFAULT '-',
    api_id                  LowCardinality(String) DEFAULT '-',

    -- A 供给
    trip_published_cnt      SimpleAggregateFunction(sum, UInt64),
    booking_created_cnt     SimpleAggregateFunction(sum, UInt64),
    -- B 匹配
    grab_submitted_cnt      SimpleAggregateFunction(sum, UInt64),
    grab_won_cnt            SimpleAggregateFunction(sum, UInt64),
    dispatch_sent_cnt       SimpleAggregateFunction(sum, UInt64),
    dispatch_ack_cnt        SimpleAggregateFunction(sum, UInt64),
    dispatch_timeout_cnt    SimpleAggregateFunction(sum, UInt64),
    transfer_submit_cnt     SimpleAggregateFunction(sum, UInt64),
    transfer_accepted_cnt   SimpleAggregateFunction(sum, UInt64),
    designated_assigned_cnt SimpleAggregateFunction(sum, UInt64),
    designated_timeout_cnt  SimpleAggregateFunction(sum, UInt64),
    -- C 履约
    order_created_cnt       SimpleAggregateFunction(sum, UInt64),
    order_confirmed_cnt     SimpleAggregateFunction(sum, UInt64),
    order_delivered_cnt     SimpleAggregateFunction(sum, UInt64),
    arrive_pickup_cnt       SimpleAggregateFunction(sum, UInt64),
    pickup_wait_over8_cnt   SimpleAggregateFunction(sum, UInt64),
    cancel_cnt              SimpleAggregateFunction(sum, UInt64),
    no_show_cnt             SimpleAggregateFunction(sum, UInt64),
    -- D 资金（金额一律 Int64 分）
    prepay_cnt              SimpleAggregateFunction(sum, UInt64),
    prepay_fail_cnt         SimpleAggregateFunction(sum, UInt64),
    gtv_sum                 SimpleAggregateFunction(sum, Int64),
    refund_amount_sum       SimpleAggregateFunction(sum, Int64),
    commission_sum          SimpleAggregateFunction(sum, Int64),
    settle_credited_cnt     SimpleAggregateFunction(sum, UInt64),
    settle_overdue_cnt      SimpleAggregateFunction(sum, UInt64),
    withdraw_req_cnt        SimpleAggregateFunction(sum, UInt64),
    withdraw_ok_cnt         SimpleAggregateFunction(sum, UInt64),
    -- E 风控 / 体验
    risk_hit_cnt            SimpleAggregateFunction(sum, UInt64),
    risk_frozen_amount_sum  SimpleAggregateFunction(sum, Int64),
    complaint_cnt           SimpleAggregateFunction(sum, UInt64),
    appeal_cnt              SimpleAggregateFunction(sum, UInt64),
    appeal_upheld_cnt       SimpleAggregateFunction(sum, UInt64),
    -- F 链路 / 接口
    event_cnt               SimpleAggregateFunction(sum, UInt64),
    late_event_cnt          SimpleAggregateFunction(sum, UInt64),
    request_cnt             SimpleAggregateFunction(sum, UInt64),
    node_fail_cnt           SimpleAggregateFunction(sum, UInt64),
    dep_call_cnt            SimpleAggregateFunction(sum, UInt64),
    dep_fail_cnt            SimpleAggregateFunction(sum, UInt64),

    -- 可合并状态列：上卷时 merge，不属于「不可加的存储指标」
    trip_driver_uniq        AggregateFunction(uniqCombined(0.01), UInt64),
    pickup_wait_p           AggregateFunction(quantilesTDigest(0.5, 0.95), UInt32),
    latency_p               AggregateFunction(quantilesTDigest(0.5, 0.95, 0.99), UInt32),
    ingest_delay_p          AggregateFunction(quantilesTDigest(0.5, 0.99), UInt32),

    updated_at              SimpleAggregateFunction(max, DateTime)
)
ENGINE = ReplicatedAggregatingMergeTree('/clickhouse/tables/{shard}/agg_5m', '{replica}')
PARTITION BY toDate(minute)
ORDER BY (minute, city_id, seat_type, biz_line, cancel_by, cancel_stage, fault,
          fail_code, rule_id, api_id)
TTL toDate(minute) + INTERVAL 180 DAY;

-- agg_1m：仅主干列，服务分钟级告警，30 天，不回补
CREATE TABLE IF NOT EXISTS monitor.agg_1m AS monitor.agg_5m
ENGINE = ReplicatedAggregatingMergeTree('/clickhouse/tables/{shard}/agg_1m', '{replica}')
PARTITION BY toDate(minute)
ORDER BY (minute, city_id, seat_type, biz_line, cancel_by, cancel_stage, fault,
          fail_code, rule_id, api_id)
TTL toDate(minute) + INTERVAL 30 DAY;

-- agg_1h / agg_1d：全量 + 状态列；2 年，资金相关 5 年
CREATE TABLE IF NOT EXISTS monitor.agg_1h AS monitor.agg_5m
ENGINE = ReplicatedAggregatingMergeTree('/clickhouse/tables/{shard}/agg_1h', '{replica}')
PARTITION BY toYYYYMM(minute)
ORDER BY (minute, city_id, seat_type, biz_line, cancel_by, cancel_stage, fault,
          fail_code, rule_id, api_id)
TTL toDate(minute) + INTERVAL 2 YEAR;

CREATE TABLE IF NOT EXISTS monitor.agg_1d AS monitor.agg_5m
ENGINE = ReplicatedAggregatingMergeTree('/clickhouse/tables/{shard}/agg_1d', '{replica}')
PARTITION BY toYYYYMM(minute)
ORDER BY (minute, city_id, seat_type, biz_line, cancel_by, cancel_stage, fault,
          fail_code, rule_id, api_id)
TTL toDate(minute) + INTERVAL 5 YEAR;

-- ─────────────────────────── 物化视图链 ods → 1m/5m → 1h → 1d ──────────────
CREATE MATERIALIZED VIEW IF NOT EXISTS monitor.mv_ods_to_5m TO monitor.agg_5m AS
SELECT
    toStartOfFiveMinute(event_time)                                   AS minute,
    city_id,
    seat_type,
    biz_line,
    if(event_type = 'order_cancelled', JSONExtractString(props, 'by'), '-')     AS cancel_by,
    if(event_type = 'order_cancelled', JSONExtractString(props, 'stage'), '-')  AS cancel_stage,
    if(event_type = 'order_cancelled', JSONExtractString(props, 'fault'), '-')  AS fault,
    JSONExtractString(props, 'fail_code')                             AS fail_code,
    JSONExtractString(props, 'rule_id')                               AS rule_id,
    '-'                                                               AS api_id,

    countIf(event_type = 'trip_published')                            AS trip_published_cnt,
    countIf(event_type = 'booking_created')                           AS booking_created_cnt,
    countIf(event_type = 'order_grab_submitted')                      AS grab_submitted_cnt,
    countIf(event_type = 'order_grab_won')                            AS grab_won_cnt,
    countIf(event_type = 'dispatch_sent')                             AS dispatch_sent_cnt,
    countIf(event_type = 'dispatch_ack')                              AS dispatch_ack_cnt,
    countIf(event_type = 'dispatch_timeout')                          AS dispatch_timeout_cnt,
    countIf(event_type = 'transfer_submitted')                        AS transfer_submit_cnt,
    countIf(event_type = 'transfer_accepted')                         AS transfer_accepted_cnt,
    countIf(event_type = 'designated_assigned')                       AS designated_assigned_cnt,
    countIf(event_type = 'designated_timeout')                        AS designated_timeout_cnt,
    countIf(event_type = 'order_created')                             AS order_created_cnt,
    countIf(event_type = 'passenger_confirmed')                       AS order_confirmed_cnt,
    countIf(event_type = 'order_delivered')                           AS order_delivered_cnt,
    countIf(event_type = 'arrive_pickup')                             AS arrive_pickup_cnt,
    countIf(event_type = 'passenger_boarded'
            AND JSONExtractUInt(props, 'wait_sec') > 480)             AS pickup_wait_over8_cnt,
    countIf(event_type = 'order_cancelled')                           AS cancel_cnt,
    countIf(event_type = 'no_show')                                   AS no_show_cnt,
    countIf(event_type = 'prepay_succeeded')                          AS prepay_cnt,
    countIf(event_type = 'prepay_failed')                             AS prepay_fail_cnt,
    sumIf(amount, event_type = 'prepay_succeeded')                    AS gtv_sum,
    sumIf(amount, event_type = 'refund_completed')                    AS refund_amount_sum,
    sumIf(JSONExtractInt(props, 'commission'),
          event_type = 'settlement_credited')                         AS commission_sum,
    countIf(event_type = 'settlement_credited')                       AS settle_credited_cnt,
    countIf(event_type = 'settlement_overdue')                        AS settle_overdue_cnt,
    countIf(event_type = 'withdraw_requested')                        AS withdraw_req_cnt,
    countIf(event_type = 'withdraw_succeeded')                        AS withdraw_ok_cnt,
    countIf(event_type = 'risk_hit')                                  AS risk_hit_cnt,
    sumIf(amount, event_type = 'risk_hit'
          AND JSONExtractString(props, 'action') = 'freeze')          AS risk_frozen_amount_sum,
    countIf(event_type = 'complaint_submitted')                       AS complaint_cnt,
    countIf(event_type = 'appeal_submitted')                          AS appeal_cnt,
    countIf(event_type = 'appeal_resolved'
            AND JSONExtractUInt(props, 'upheld') = 1)                 AS appeal_upheld_cnt,
    count()                                                           AS event_cnt,
    countIf(dateDiff('second', event_time, ingest_time) > 600)        AS late_event_cnt,
    0                                                                 AS request_cnt,
    0                                                                 AS node_fail_cnt,
    0                                                                 AS dep_call_cnt,
    0                                                                 AS dep_fail_cnt,

    uniqCombinedState(0.01)(cityHash64(driver_id_hash))               AS trip_driver_uniq,
    quantilesTDigestState(0.5, 0.95)(toUInt32(JSONExtractUInt(props, 'wait_sec'))) AS pickup_wait_p,
    quantilesTDigestState(0.5, 0.95, 0.99)(toUInt32(0))               AS latency_p,
    quantilesTDigestState(0.5, 0.99)(
        toUInt32(dateDiff('second', event_time, ingest_time)))        AS ingest_delay_p,
    max(ingest_time)                                                  AS updated_at
FROM monitor.ods_order_event
GROUP BY minute, city_id, seat_type, biz_line, cancel_by, cancel_stage, fault,
         fail_code, rule_id, api_id;

-- 说明：agg_5m → agg_1h → agg_1d 的上卷 MV 结构同上，
-- 差异仅在时间函数（toStartOfHour / toDate）与状态列的 *Merge → *State 转换。

-- ─────────────────────────── 系统表（元监控 / 布局 / 告警） ──────────────────
CREATE TABLE IF NOT EXISTS monitor.system_link_health
(
    node_key     LowCardinality(String),
    node_label   String,
    status       LowCardinality(String),
    detail       String,
    metric_id    String,
    alert_id     Nullable(UInt64),
    heartbeat_at DateTime,
    sort_order   UInt8
) ENGINE = ReplacingMergeTree(heartbeat_at) ORDER BY node_key;

CREATE TABLE IF NOT EXISTS monitor.system_pipeline_health
(
    component    String,
    status       LowCardinality(String),
    throughput   String,
    heartbeat_at DateTime,
    detail       String,
    extra        String,
    sort_order   UInt8
) ENGINE = ReplacingMergeTree(heartbeat_at) ORDER BY component;

CREATE TABLE IF NOT EXISTS monitor.system_topology_layout
(
    node_id String, label String, sub String,
    x UInt16, y UInt16, w UInt16, h UInt16,
    kind LowCardinality(String), sort_order UInt8
) ENGINE = ReplacingMergeTree ORDER BY node_id;

CREATE TABLE IF NOT EXISTS monitor.system_backfill_job
(
    job_id String, target_dt Date, status LowCardinality(String),
    started_at DateTime, finished_at Nullable(DateTime), rows_written UInt64
) ENGINE = ReplacingMergeTree(started_at) ORDER BY job_id;

CREATE TABLE IF NOT EXISTS monitor.alert_event
(
    alert_id          UInt64,
    rule_id           LowCardinality(String),
    level             LowCardinality(String),
    title             String,
    biz_line          LowCardinality(String),
    city_id           UInt64,
    city_name         String,
    seat_type         LowCardinality(String),
    scope_text        String,
    fired_at          DateTime,
    periods           UInt16,
    value             Float64,
    baseline          Float64,
    baseline_kind     LowCardinality(String),
    delta_pp          Float64,
    numerator         UInt64,
    denominator       UInt64,
    status            LowCardinality(String),
    ack_by            Nullable(String),
    ack_at            Nullable(DateTime),
    metric_id         String,
    metric_version    LowCardinality(String),
    runbook           String,
    deduped           UInt16,
    aggregated_cities UInt16,
    suppressed_by     Nullable(String),
    judgement         Nullable(String),   -- VALID / INVALID / NOISE / DUPLICATED
    root_cause        Nullable(String),
    resolved_at       Nullable(DateTime),
    mtta_sec          Int32 DEFAULT -1,
    mttr_sec          Int32 DEFAULT -1,
    updated_at        DateTime DEFAULT now()
) ENGINE = ReplacingMergeTree(updated_at)
PARTITION BY toYYYYMM(fired_at) ORDER BY (alert_id);

CREATE TABLE IF NOT EXISTS monitor.alert_silence
(
    silence_id       String,
    matchers         String,
    from_at          DateTime,
    to_at            DateTime,
    reason           String,
    owner            String,
    suppressed_count UInt32 DEFAULT 0,
    auto             UInt8 DEFAULT 0
) ENGINE = ReplacingMergeTree ORDER BY silence_id;

-- ─────────────────────────── 权限：权限即数据源 ──────────────────────────────
CREATE USER IF NOT EXISTS grafana_dash IDENTIFIED BY '***'
    SETTINGS max_execution_time = 10, max_memory_usage = 2000000000;
GRANT SELECT ON monitor.agg_1m  TO grafana_dash;
GRANT SELECT ON monitor.agg_5m  TO grafana_dash;
GRANT SELECT ON monitor.agg_1h  TO grafana_dash;
GRANT SELECT ON monitor.agg_1d  TO grafana_dash;
GRANT SELECT ON monitor.alert_event TO grafana_dash;

-- 客服明细账号：独立 profile + 行策略（按城市），越权由服务层直接 403
CREATE USER IF NOT EXISTS cs_detail IDENTIFIED BY '***'
    SETTINGS max_execution_time = 60, max_memory_usage = 6000000000;
GRANT SELECT ON monitor.ods_order_event TO cs_detail;
-- CREATE ROW POLICY cs_city ON monitor.ods_order_event
--     USING city_id IN (330100, 440100) TO cs_detail;

CREATE USER IF NOT EXISTS alert_engine IDENTIFIED BY '***';
GRANT SELECT ON monitor.agg_1m TO alert_engine;
GRANT SELECT, INSERT, ALTER UPDATE ON monitor.alert_event TO alert_engine;
GRANT SELECT, INSERT, ALTER DELETE ON monitor.alert_silence TO alert_engine;

CREATE USER IF NOT EXISTS biz_producer IDENTIFIED BY '***';
GRANT INSERT ON monitor.ods_order_event TO biz_producer;
GRANT INSERT ON monitor.ods_dirty_event TO biz_producer;
