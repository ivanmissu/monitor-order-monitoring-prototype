package com.minitor.server.domain;

/**
 * 维度与状态枚举集合。集中放置以避免大量单行枚举文件。
 */
public final class Dims {

    private Dims() {
    }

    /** 告警分级（设计文档 §6.3）。 */
    public enum AlertLevel {
        /** 资损或全国断流：电话/IM 强提醒 + 短信，5min 认领 */
        P0(5),
        /** 重点城市核心率值越界且持续 2 周期：15min 未认领升级备值 */
        P1(15),
        /** 长尾城市 / 次要指标：当日处理 */
        P2(1440),
        /** 只进大盘与日报，不发通知 */
        P3(0);

        private final int slaMinutes;

        AlertLevel(int slaMinutes) {
            this.slaMinutes = slaMinutes;
        }

        public int slaMinutes() {
            return slaMinutes;
        }

        public boolean notifiable() {
            return this != P3;
        }
    }

    public enum AlertStatus {
        FIRING, CLAIMED, RESOLVED, SILENCED;

        public String id() {
            return name().toLowerCase();
        }
    }

    /** 一期只用三类规则，检测算法二期再上（设计文档 §6.2）。 */
    public enum RuleType {
        /** 静态阈值 + 持续 N 周期，滤毛刺 */
        STATIC_THRESHOLD_PERSIST,
        /** 环比 */
        RING_RATIO,
        /** 周同比，节假日可豁免 */
        YOY_RATIO,
        /** 跌零 / 无数据：基线窗口应有数据而无 */
        NO_DATA
    }

    /** 指标类型：只有 ATOMIC 落库，DERIVED 一律查询期派生。 */
    public enum MetricType {
        ATOMIC, DERIVED, TECH
    }

    public enum MetricDomain {
        SUPPLY, MATCH, FULFILL, FUND, RISK, EXP, LINK, API;

        public String id() {
            return name().toLowerCase();
        }
    }

    public enum Unit {
        COUNT, RATIO, FEN, MS, SEC;

        public String id() {
            return name().toLowerCase();
        }
    }

    /** 健康分档，用于链路节点、接口状态、拓扑边着色。 */
    public enum Health {
        OK, WARN, BAD;

        public String id() {
            return name().toLowerCase();
        }
    }

    /** 告警关闭时的判定，有效率 = VALID / 总数（设计文档 §6.3 运营闭环）。 */
    public enum Judgement {
        VALID, INVALID, NOISE, DUPLICATED
    }

    public enum RootCause {
        CODE, CONFIG, THIRD_PARTY, DATA, CAPACITY, FALSE_POSITIVE, UNKNOWN
    }
}
