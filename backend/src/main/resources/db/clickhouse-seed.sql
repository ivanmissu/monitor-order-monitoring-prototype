-- ============================================================================
--  Monitor ClickHouse 种子数据（integration profile 由 IntegrationSeedRunner 执行）
--  执行前置：db/clickhouse-ddl.sql 已建表；聚合层数据由 ODS 历史事件经物化视图
--  上卷生成（ods → 1m/5m → 1h → 1d），与生产管道完全同构。
--  占位符 ${seedDays} / ${eventsPerDay} 由 runner 注入；已有数据时整体跳过。
-- ============================================================================

-- ─────────────────────────── ① 城市维度字典 ────────────────────────────────
INSERT INTO monitor.dim_city_src (city_id, name, priority) VALUES
    (330100, '杭州', 'focus'), (440100, '广州', 'focus'), (510100, '成都', 'focus'),
    (310000, '上海', 'normal'), (320100, '南京', 'normal'), (440300, '深圳', 'focus'),
    (420100, '武汉', 'normal'), (110000, '北京', 'focus');

-- ─────────────────────────── ② 链路健康（值班哨红绿灯） ──────────────────────
INSERT INTO monitor.system_link_health
(node_key, node_label, status, detail, metric_id, alert_id, heartbeat_at, sort_order) VALUES
    ('mq', '业务事件 MQ', 'ok', 'lag 1.2s', 'link.consumer_lag', NULL, now(), 1),
    ('consumer', 'Consumer', 'ok', '4 / 4 实例', 'link.consumer_lag', NULL, now(), 2),
    ('clickhouse', 'ClickHouse', 'ok', '2 / 2 副本', NULL, NULL, now(), 3),
    ('freshness', '事件新鲜度', 'bad', 'P99 6m12s', 'link.ingest_delay_p99', 4471, now(), 4),
    ('evaluator', '规则求值', 'ok', '周期 60s', NULL, NULL, now(), 5);

-- ─────────────────────────── ③ 管道健康（元监控） ───────────────────────────
INSERT INTO monitor.system_pipeline_health
(component, status, throughput, heartbeat_at, detail, extra, sort_order) VALUES
    ('order-domain-topic (5 线)', 'ok', '8,420 msg/s', now(), 'lag 2,210', 'retention 7d', 1),
    ('monitor-consumer ×4', 'ok', '8,406 msg/s', now(), '本地缓冲 0', '无状态', 2),
    ('ClickHouse replica-01', 'ok', '写入 42ms', now(), '8C / 32G', 'healthy', 3),
    ('ClickHouse replica-02', 'ok', '复制延迟 0.3s', now(), '8C / 32G', 'healthy', 4),
    ('RuleEvaluator', 'ok', '48 条 / min', now(), '求值 328ms', '20 规则启用', 5),
    ('T+1 reconciler', 'warn', '缺口 23 笔', now() - INTERVAL 4 HOUR, '等待重算', 'job rc-20260903-07', 6);

-- ─────────────────────────── ④ 拓扑布局（接口监控） ─────────────────────────
INSERT INTO monitor.system_topology_layout
(node_id, label, sub, x, y, w, h, kind, sort_order) VALUES
    ('client', '客户端 App', '5 条业务线', 20, 182, 110, 40, 'edge', 1),
    ('gw', 'API 网关', '8,420 QPS', 200, 182, 110, 40, 'gateway', 2),
    ('order', '订单中心', '6,180 QPS', 380, 182, 110, 40, 'core', 3),
    ('pay', '支付中心', '2,140 QPS', 680, 34, 150, 40, 'core', 4),
    ('dispatch', '调度派单', '860 QPS', 680, 118, 150, 40, 'core', 5),
    ('settle', '结算中心', '620 QPS', 680, 202, 150, 40, 'core', 6),
    ('risk', '风控引擎', '3,200 QPS', 680, 286, 150, 40, 'core', 7),
    ('notify', '消息通知', '1,050 QPS', 680, 370, 150, 40, 'core', 8);

-- ─────────────────────────── ⑤ 对账缺口明细（T+1） ─────────────────────────
INSERT INTO monitor.ods_state_gap
(dt, order_id, biz_line, from_state, to_state, missing_event_type, gap_sec, likely_reason) VALUES
    (yesterday(), 'CP20260914007741', 'carpool', 'boarded', 'completed', 'order_delivered', 812, '送达打卡未发事件（客户端离线）'),
    (yesterday(), 'DR20260914003118', 'driver', 'enroute', 'completed', 'order_delivered', 654, '送达打卡未发事件（客户端离线）'),
    (yesterday(), 'CP20260914000692', 'carpool', 'created', 'boarded', 'passenger_boarded', 2201, '上车打卡事件未发'),
    (yesterday(), 'DD20260914001847', 'designated', 'completed', 'settled', 'settlement_credited', 3600, '结算服务重试期间事件丢失'),
    (yesterday(), 'TR20260914000932', 'transfer', 'completed', 'settled', 'settlement_credited', 1980, '结算服务重试期间事件丢失');

-- ─────────────────────────── ⑥ 活动告警（与前端演示口径同源） ────────────────
INSERT INTO monitor.alert_event
(alert_id, rule_id, level, title, biz_line, city_id, city_name, seat_type, scope_text, fired_at,
 periods, value, baseline, baseline_kind, delta_pp, numerator, denominator, status, ack_by,
 metric_id, metric_version, runbook, deduped, aggregated_cities, mtta_sec, mttr_sec, updated_at) VALUES
    (4471, 'R02', 'P0', '预付成功率持续低于阈值', 'carpool', 0, '全国', 'all', '全国 · 全部座型', now() - INTERVAL 4 MINUTE,
     4, 0.918, 0.95, 'threshold', -3.2, 9420, 10261, 'firing', NULL,
     'fund.prepay_success_rate', 'v3', 'https://wiki.internal/rb/r02', 3, 12, -1, -1, now()),
    (4472, 'R05', 'P0', '派单响应超时激增', 'driver', 0, '全国', 'all', '全国 · 快车', now() - INTERVAL 3 MINUTE,
     3, 0.124, 0.05, 'threshold', 7.4, 10664, 86000, 'firing', NULL,
     'driver.dispatch_timeout_rate', 'v2', 'https://wiki.internal/rb/r05', 3, 12, -1, -1, now()),
    (4473, 'R10', 'P1', '完单率低于 30 日基线', 'carpool', 330100, '杭州', 'all', '杭州市 · 独享', now() - INTERVAL 12 MINUTE,
     2, 0.724, 0.831, 'baseline30d', -10.7, 5021, 6935, 'firing', NULL,
     'core.delivery_rate', 'v2', 'https://wiki.internal/rb/r10', 0, 1, -1, -1, now()),
    (4474, 'R04', 'P1', '转单成功率下降', 'transfer', 440100, '广州', 'all', '广州市', now() - INTERVAL 9 MINUTE,
     3, 0.682, 0.82, 'baseline30d', -13.8, 2380, 3490, 'firing', NULL,
     'transfer.transfer_success_rate', 'v1', 'https://wiki.internal/rb/r04', 0, 1, -1, -1, now()),
    (4475, 'R13', 'P1', '结算逾期单量超阈值', 'carpool', 440100, '广州', 'all', '广州市 · 全部座型', now() - INTERVAL 12 MINUTE,
     2, 68, 50, 'threshold', 36.0, 68, 0, 'claimed', 'linzhou',
     'fund.settle_overdue_cnt', 'v1', 'https://wiki.internal/rb/r13', 0, 1, 187, -1, now()),
    (4476, 'R06', 'P1', '代驾接单超时', 'designated', 510100, '成都', 'all', '成都市', now() - INTERVAL 8 MINUTE,
     2, 0.186, 0.10, 'threshold', 8.6, 2264, 12172, 'firing', NULL,
     'designated.accept_timeout_rate', 'v1', 'https://wiki.internal/rb/r06', 0, 1, -1, -1, now()),
    (4477, 'R19', 'P2', '上车等待超率偏高', 'carpool', 510100, '成都', '2seat', '成都市 · 2座', now() - INTERVAL 19 MINUTE,
     2, 0.173, 0.15, 'threshold', 2.3, 1210, 6994, 'firing', NULL,
     'core.wait_over8_rate', 'v1', 'https://wiki.internal/rb/r19', 0, 1, -1, -1, now()),
    (4478, 'R16', 'P2', '航班延误取消量上升', 'airport', 110000, '北京', 'all', '首都 T3', now() - INTERVAL 25 MINUTE,
     2, 47, 28, 'yoy', 67.9, 47, 0, 'claimed', 'linzhou',
     'core.order_cancelled_cnt', 'v2', 'https://wiki.internal/rb/r16', 0, 1, 251, -1, now()),
    (4479, 'R09', 'P0', '支付回调接口失败率越界', 'all', 0, '全国', 'all', '支付中心 · 全平台', now() - INTERVAL 3 MINUTE,
     3, 0.047, 0.02, 'threshold', 2.7, 6032, 128340, 'firing', NULL,
     'api.fail_rate', 'v1', 'https://wiki.internal/rb/r09', 3, 12, -1, -1, now()),
    (4480, 'R21', 'P1', '航班状态同步接口异常率上升', 'airport', 0, '全国', 'all', '接送机 · flight-service', now() - INTERVAL 14 MINUTE,
     2, 0.009, 0.005, 'threshold', 0.4, 65, 7200, 'firing', NULL,
     'api.dependency_error_rate', 'v1', 'https://wiki.internal/rb/r21', 0, 1, -1, -1, now());

-- 本周历史告警（周报统计口径：有效率 / MTTA / MTTR）
INSERT INTO monitor.alert_event
(alert_id, rule_id, level, title, biz_line, city_id, city_name, seat_type, scope_text, fired_at,
 periods, value, baseline, baseline_kind, delta_pp, numerator, denominator, status, ack_by,
 metric_id, metric_version, runbook, deduped, aggregated_cities, judgement, root_cause,
 resolved_at, mtta_sec, mttr_sec, updated_at)
SELECT
    9000 + number AS alert_id,
    arrayElement(['R02', 'R05', 'R10', 'R04', 'R13', 'R19', 'R21', 'R16'], (number % 8) + 1) AS rule_id,
    arrayElement(['P0', 'P1', 'P1', 'P2'], (number % 4) + 1) AS level,
    '历史规则触发（种子）' AS title,
    arrayElement(['driver', 'transfer', 'carpool', 'designated', 'airport'], (number % 5) + 1) AS biz_line,
    0, '全国', 'all', '全国',
    now() - INTERVAL (number % 5) DAY - INTERVAL (number % 24) HOUR AS fired_at,
    2, 0.12, 0.05, 'threshold', 2.5, 100, 1000,
    'resolved', 'oncall-rot',
    'core.order_created_cnt', 'v1', 'https://wiki.internal/rb/r02',
    0, 1,
    arrayElement(['VALID', 'VALID', 'VALID', 'VALID', 'INVALID', 'NOISE'], (number % 6) + 1) AS judgement,
    arrayElement(['CODE', 'CONFIG', 'THIRD_PARTY', 'CAPACITY'], (number % 4) + 1) AS root_cause,
    fired_at + INTERVAL (300 + (number % 3600)) SECOND AS resolved_at,
    toInt32(60 + (number % 1200)) AS mtta_sec,
    toInt32(300 + (number % 5400)) AS mttr_sec,
    now()
FROM numbers(180);

-- ─────────────────────────── ⑦ 静默规则 ────────────────────────────────────
INSERT INTO monitor.alert_silence
(silence_id, matchers, from_at, to_at, reason, owner, suppressed_count, auto) VALUES
    ('sil-20260916-01', '{"biz_line":["carpool"],"rule_id":["R10"]}', now() - INTERVAL 2 HOUR, now() + INTERVAL 2 HOUR,
     '杭州完单率波动为节假日效应，静默观察', 'linzhou', 3, 0),
    ('sil-20260916-02', '{"rule_id":["R01"]}', now() - INTERVAL 1 HOUR, now() + INTERVAL 6 HOUR,
     'T+1 回补期间自动静默新鲜度告警', 'system', 12, 1);

-- ─────────────────────────── ⑧ ODS 历史事件（经 MV 上卷聚合层） ─────────────

-- order_created（日占比 0.1）
WITH toUInt32(${eventsPerDay} * 0.1) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-order_created-' || toString(n),
  'order_created',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"channel":"app"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- order_delivered（日占比 0.08）
WITH toUInt32(${eventsPerDay} * 0.08) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-order_delivered-' || toString(n),
  'order_delivered',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"trip_duration_sec":' || toString(if(g % 60 = 0, 200 + oh3 % 80, 600 + oh3 % 2400)) || ',"seat_type":"' || seat || '"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 60)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- passenger_confirmed（日占比 0.079）
WITH toUInt32(${eventsPerDay} * 0.079) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-passenger_confirmed-' || toString(n),
  'passenger_confirmed',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"result":"confirmed"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- arrive_pickup（日占比 0.079）
WITH toUInt32(${eventsPerDay} * 0.079) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-arrive_pickup-' || toString(n),
  'arrive_pickup',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"wait_started_at":"2026-09-15 09:00:00"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- passenger_boarded（日占比 0.078）
WITH toUInt32(${eventsPerDay} * 0.078) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-passenger_boarded-' || toString(n),
  'passenger_boarded',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"wait_sec":' || toString(120 + oh3 % 900) || '}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- prepay_succeeded（日占比 0.078）
WITH toUInt32(${eventsPerDay} * 0.078) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-prepay_succeeded-' || toString(n),
  'prepay_succeeded',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  price,
  '{"pay_channel":"' || arrayElement(['alipay','wechat','balance'], (oh1 % 3) + 1) || '"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- prepay_failed（日占比 0.0007）
WITH toUInt32(${eventsPerDay} * 0.0007) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-prepay_failed-' || toString(n),
  'prepay_failed',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  price,
  '{"fail_code":"' || arrayElement(['PAY_CHANNEL_TIMEOUT','WALLET_LOCK_BUSY'], (oh1 % 2) + 1) || '","pay_channel":"alipay"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- settlement_credited（日占比 0.077）
WITH toUInt32(${eventsPerDay} * 0.077) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-settlement_credited-' || toString(n),
  'settlement_credited',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  price - price / 10,
  '{"commission":' || toString(price / 10) || ',"driver_income":' || toString(price - price / 10) || '}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- settlement_overdue（日占比 0.0003）
WITH toUInt32(${eventsPerDay} * 0.0003) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-settlement_overdue-' || toString(n),
  'settlement_overdue',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"overdue_sec":86400}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- order_cancelled（日占比 0.0035）
WITH toUInt32(${eventsPerDay} * 0.0035) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-order_cancelled-' || toString(n),
  'order_cancelled',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"by":"' || arrayElement(['driver','passenger','system'], (oh1 % 3) + 1) || '","stage":"' || arrayElement(['pre1h','enroute','lt1h'], (oh2 % 3) + 1) || '","fault":"' || arrayElement(['at-fault','no-fault'], (oh3 % 2) + 1) || '","reason_code":"' || arrayElement(['ROUTE_MISMATCH','VEHICLE_ISSUE','PRICE_CHANGE','WEATHER'], (oh1 % 4) + 1) || '"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- no_show（日占比 0.0005）
WITH toUInt32(${eventsPerDay} * 0.0005) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-no_show-' || toString(n),
  'no_show',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- refund_completed（日占比 0.0008）
WITH toUInt32(${eventsPerDay} * 0.0008) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-refund_completed-' || toString(n),
  'refund_completed',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  price,
  '{"dispute_id":"D' || toString(g) || '"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- dispatch_sent（日占比 0.051）
WITH toUInt32(${eventsPerDay} * 0.051) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-dispatch_sent-' || toString(n),
  'dispatch_sent',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"radius_m":' || toString(1500 + oh3 % 3000) || ',"candidate_count":' || toString(3 + oh2 % 9) || '}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'driver' AS biz,
    arrayElement(['express', 'express_pool'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- dispatch_ack（日占比 0.049）
WITH toUInt32(${eventsPerDay} * 0.049) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-dispatch_ack-' || toString(n),
  'dispatch_ack',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"ack_duration_sec":' || toString(1 + oh3 % 9) || '}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'driver' AS biz,
    arrayElement(['express', 'express_pool'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- dispatch_timeout（日占比 0.006）
WITH toUInt32(${eventsPerDay} * 0.006) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-dispatch_timeout-' || toString(n),
  'dispatch_timeout',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"timeout_sec":30}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'driver' AS biz,
    arrayElement(['express', 'express_pool'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- transfer_submitted（日占比 0.007）
WITH toUInt32(${eventsPerDay} * 0.007) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-transfer_submitted-' || toString(n),
  'transfer_submitted',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"reason_code":"' || arrayElement(['ROUTE_MISMATCH','WEATHER'], (oh1 % 2) + 1) || '"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'transfer' AS biz,
    arrayElement(['express'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- transfer_accepted（日占比 0.0065）
WITH toUInt32(${eventsPerDay} * 0.0065) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-transfer_accepted-' || toString(n),
  'transfer_accepted',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"match_duration_sec":18}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'transfer' AS biz,
    arrayElement(['express'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- order_grab_submitted（日占比 0.022）
WITH toUInt32(${eventsPerDay} * 0.022) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-order_grab_submitted-' || toString(n),
  'order_grab_submitted',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"candidate_count":' || toString(1 + oh2 % 8) || '}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'carpool' AS biz,
    arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- order_grab_won（日占比 0.019）
WITH toUInt32(${eventsPerDay} * 0.019) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-order_grab_won-' || toString(n),
  'order_grab_won',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"match_duration_sec":' || toString(1 + oh3 % 10) || ',"route_score":' || toString(60 + oh2 % 40) || '}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'carpool' AS biz,
    arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- designated_assigned（日占比 0.0125）
WITH toUInt32(${eventsPerDay} * 0.0125) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-designated_assigned-' || toString(n),
  'designated_assigned',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"distance_m":2400}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'designated' AS biz,
    arrayElement(['designated'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- designated_timeout（日占比 0.0023）
WITH toUInt32(${eventsPerDay} * 0.0023) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-designated_timeout-' || toString(n),
  'designated_timeout',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"timeout_sec":45}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'designated' AS biz,
    arrayElement(['designated'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- booking_created（日占比 0.006）
WITH toUInt32(${eventsPerDay} * 0.006) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-booking_created-' || toString(n),
  'booking_created',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"flight_no_hash":"' || substring(lower(hex(SHA256(toString(g)))), 1, 12) || '","service_type":"pickup"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'airport' AS biz,
    arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- trip_published（日占比 0.023）
WITH toUInt32(${eventsPerDay} * 0.023) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-trip_published-' || toString(n),
  'trip_published',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"seats":' || toString(2 + oh2 % 4) || ',"detour_tolerance":' || toString(15 + oh3 % 30) || ',"depart_time_bucket":"' || arrayElement(['morning','evening','night'], (oh1 % 3) + 1) || '"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    'carpool' AS biz,
    arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 2) + 1) AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- risk_hit（日占比 0.0027）
WITH toUInt32(${eventsPerDay} * 0.0027) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-risk_hit-' || toString(n),
  'risk_hit',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  5000 + oh3 % 50000,
  '{"rule_id":"' || arrayElement(['R-1142','R-2201','R-3310'], (oh1 % 3) + 1) || '","action":"freeze"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 60)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- complaint_submitted（日占比 0.003）
WITH toUInt32(${eventsPerDay} * 0.003) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-complaint_submitted-' || toString(n),
  'complaint_submitted',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"complaint_tag":"' || arrayElement(['SERVICE','ROUTE','FEE'], (oh1 % 3) + 1) || '"}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- appeal_submitted（日占比 0.0016）
WITH toUInt32(${eventsPerDay} * 0.0016) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-appeal_submitted-' || toString(n),
  'appeal_submitted',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- appeal_resolved（日占比 0.0014）
WITH toUInt32(${eventsPerDay} * 0.0014) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-appeal_resolved-' || toString(n),
  'appeal_resolved',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{"upheld":' || toString(oh1 % 2) || '}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- withdraw_requested（日占比 0.005）
WITH toUInt32(${eventsPerDay} * 0.005) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-withdraw_requested-' || toString(n),
  'withdraw_requested',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- withdraw_succeeded（日占比 0.0049）
WITH toUInt32(${eventsPerDay} * 0.0049) AS cpd
INSERT INTO monitor.ods_order_event
(event_id, event_type, event_time, ingest_time, order_id, trip_id, city_id, seat_type, biz_line, driver_id_hash, amount, props, version)
SELECT
  'HS-withdraw_succeeded-' || toString(n),
  'withdraw_succeeded',
  et,
  it,
  oid,
  'T' || toString(70000000 + day_idx * 100000 + g),
  city,
  seat,
  biz,
  drv,
  0,
  '{}',
  1
FROM (
  SELECT
    number AS n,
    number % cpd AS g,
    intDiv(number, cpd) AS day_idx,
    (g * 2654435761 + day_idx * 40503) % 4294967296 AS oh1,
    (g * 97 + day_idx * 31) % 100000 AS oh2,
    (g * 2246822519 + day_idx * 65537) % 4294967296 AS oh3,
    arrayElement([330100, 330100, 440100, 440100, 510100, 310000, 320100, 440300, 420100], (oh1 % 9) + 1) AS city,
    arrayElement(['driver', 'driver', 'driver', 'driver', 'carpool', 'carpool', 'transfer', 'designated', 'airport'], (oh2 % 9) + 1) AS biz,
    multiIf(biz = 'carpool', arrayElement(['shared_2', 'shared_4', 'exclusive'], (oh2 % 3) + 1),
            biz = 'driver', arrayElement(['express', 'express_pool'], (oh2 % 2) + 1),
            biz = 'airport', arrayElement(['pickup', 'dropoff'], (oh2 % 2) + 1),
            'designated') AS seat,
    lower(hex(SHA256(toString(g % 5000)))) AS drv,
    'HSO' || toString(day_idx * 100000 + g) AS oid,
    1500 + (oh3 % 26500) AS price,
    multiIf(oh1 % 10 < 3, (7 + oh2 % 3) * 3600,
            oh1 % 10 < 6, (9 + oh2 % 2) * 3600,
            oh1 % 10 < 8, (12 + oh2 % 3) * 3600,
            (17 + oh2 % 3) * 3600) + (oh3 % 3600) AS tod,
    toDateTime64(toDate(today() - day_idx) + INTERVAL tod SECOND, 3) AS et,
    toDateTime64(et + INTERVAL if(oh1 % 300 = 0, 900 + oh3 % 600, 2 + oh3 % 80) SECOND, 3) AS it
  FROM numbers(cpd * ${seedDays})
)
WHERE et < now() - INTERVAL 60 MINUTE;


-- ─────────────────────────── ⑨ 接口监控指标（agg_1m 专行） ─────────────────
-- 核心接口状态（近 6 分钟，每分钟一行；qps = request_cnt / 60 口径成立）
INSERT INTO monitor.agg_1m
(minute, city_id, seat_type, biz_line, api_id, path, service, service_label, upstream,
 warn_threshold, bad_threshold, request_cnt, node_fail_cnt, latency_p,
 consumer_lag_sec, partition, ingest_delay_p, updated_at)
SELECT
    toStartOfMinute(now() - INTERVAL m MINUTE) AS minute,
    0, '-', a.biz, a.api_id, a.path, a.svc, a.label, 'order-service',
    0.005, 0.02,
    toUInt64(a.qps * 60)                                   AS request_cnt,
    toUInt64(a.qps * 60 * a.fail)                          AS node_fail_cnt,
    quantilesTDigestState(0.5, 0.95, 0.99)(toUInt32(v))    AS latency_p,
    0.4 + (m % 3) * 0.3                                    AS consumer_lag_sec,
    'carpool-' || toString(m % 4)                          AS partition,
    quantilesTDigestState(0.5, 0.99)(toUInt32(18 + (v % 50))) AS ingest_delay_p,
    toStartOfMinute(now() - INTERVAL m MINUTE)             AS updated_at
FROM
    (SELECT biz, api_id, path, svc, label, qps, fail, [p50, p50, p50, toUInt32((p50 + p99) / 2), p99, p99] AS vals
     FROM VALUES('biz String, api_id String, path String, svc String, label String, qps UInt32, fail Float64, p50 UInt32, p99 UInt32',
           ('all',      'pay.callback',     '/api/v1/pay/callback',     'pay-service',      '支付回调',   2140, 0.0470, 180,  640),
           ('driver',   'dispatch.respond', '/api/v1/dispatch/respond', 'dispatch-service', '派单响应',    860, 0.0231, 210,  890),
           ('transfer', 'transfer.submit',  '/api/v1/transfer/submit',  'transfer-service', '转单提交',    340, 0.0162, 130,  420),
           ('airport',  'flight.sync',      '/api/v1/flight/sync',      'flight-service',   '航班状态同步', 120, 0.0090, 640, 1800),
           ('driver',   'order.create',     '/api/v1/order/create',     'order-service',    '创建订单',   1240, 0.0034,  88,  240),
           ('carpool',  'withdraw.apply',   '/api/v1/withdraw/apply',   'withdraw-service', '提现申请',    280, 0.0012,  96,  320),
           ('carpool',  'settle.credit',    '/api/v1/settle/credit',    'settle-service',   '结算入账',    620, 0.0008,  74,  210),
           ('all',      'risk.evaluate',    '/api/v1/risk/evaluate',    'risk-engine',      '规则判定',   3200, 0.0001,  12,   45))) AS a
    ARRAY JOIN a.vals AS v
    CROSS JOIN (SELECT number AS m FROM numbers(6)) AS mins
GROUP BY minute, a.biz, a.api_id, a.path, a.svc, a.label, a.qps, a.fail, m, v;

-- 依赖拓扑边（topology 视图的 caller→callee 流量）
INSERT INTO monitor.agg_1m
(minute, city_id, seat_type, biz_line, api_id, caller, callee, dep_call_cnt, dep_fail_cnt,
 latency_p, consumer_lag_sec, partition, updated_at)
SELECT
    toStartOfMinute(now() - INTERVAL m MINUTE) AS minute,
    0, '-', 'all', '-', e.f, e.t,
    toUInt64(e.qps * 60)                          AS dep_call_cnt,
    toUInt64(e.qps * 60 * e.err)                  AS dep_fail_cnt,
    quantilesTDigestState(0.5, 0.95, 0.99)(toUInt32(v)) AS latency_p,
    0.4 + (m % 3) * 0.3                           AS consumer_lag_sec,
    'carpool-' || toString(m % 4)                 AS partition,
    toStartOfMinute(now() - INTERVAL m MINUTE)    AS updated_at
FROM
    (SELECT f, t, qps, err, [p50, p50, p50, toUInt32((p50 + p99) / 2), p99, p99] AS vals
     FROM VALUES('f String, t String, qps UInt32, err Float64, p50 UInt32, p99 UInt32',
           ('client', 'gw',      8420, 0.0002,  42, 130),
           ('gw',     'order',   6180, 0.0034,  88, 240),
           ('order',  'pay',     2140, 0.0470, 180, 640),
           ('order',  'dispatch', 860, 0.0231, 210, 890),
           ('order',  'settle',   620, 0.0008,  74, 210),
           ('order',  'risk',    3200, 0.0001,  12,  45),
           ('order',  'notify',  1050, 0.0052,  60, 200))) AS e
    ARRAY JOIN e.vals AS v
    CROSS JOIN (SELECT number AS m FROM numbers(6)) AS mins
GROUP BY minute, e.f, e.t, e.qps, e.err, m, v;

-- ─────────────────────────── ⑩ 字典生效确认 ────────────────────────────────
SYSTEM RELOAD DICTIONARY monitor.dim_city;
