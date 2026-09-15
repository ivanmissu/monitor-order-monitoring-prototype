package com.monitor.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Monitor 自有配置。阈值、门槛、窗口全部外置，改参数不改代码。
 */
@ConfigurationProperties(prefix = "monitor")
public class MonitorProperties {

    private String timezone = "Asia/Shanghai";
    private String dictVersion = "2026.09";
    private Store store = new Store();
    private Query query = new Query();
    private Ingest ingest = new Ingest();
    private Alert alert = new Alert();

    public static class Store {
        private String kind = "clickhouse";
        private Ds agg = new Ds();
        private Ds ods = new Ds();

        public static class Ds {
            private String url;
            private String username;
            private String password;

            public String getUrl() {
                return url;
            }

            public void setUrl(String url) {
                this.url = url;
            }

            public String getUsername() {
                return username;
            }

            public void setUsername(String username) {
                this.username = username;
            }

            public String getPassword() {
                return password;
            }

            public void setPassword(String password) {
                this.password = password;
            }
        }

        public String getKind() {
            return kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public Ds getAgg() {
            return agg;
        }

        public void setAgg(Ds agg) {
            this.agg = agg;
        }

        public Ds getOds() {
            return ods;
        }

        public void setOds(Ds ods) {
            this.ods = ods;
        }
    }

    public static class Query {
        /** 明细查询强制时间窗上限 */
        private int detailMaxWindowDays = 90;
        /** 小样本门槛：维度 × 分钟事件数低于此值不评告警 */
        private int smallSampleFloor = 20;
        /** 分钟粒度允许的最大跨度 */
        private int minuteGrainMaxSpanDays = 7;

        public int getDetailMaxWindowDays() {
            return detailMaxWindowDays;
        }

        public void setDetailMaxWindowDays(int v) {
            this.detailMaxWindowDays = v;
        }

        public int getSmallSampleFloor() {
            return smallSampleFloor;
        }

        public void setSmallSampleFloor(int v) {
            this.smallSampleFloor = v;
        }

        public int getMinuteGrainMaxSpanDays() {
            return minuteGrainMaxSpanDays;
        }

        public void setMinuteGrainMaxSpanDays(int v) {
            this.minuteGrainMaxSpanDays = v;
        }
    }

    public static class Ingest {
        private int maxBatchSize = 500;
        private long maxBatchBytes = 2 * 1024 * 1024L;
        private int dedupeTtlHours = 48;

        public int getMaxBatchSize() {
            return maxBatchSize;
        }

        public void setMaxBatchSize(int v) {
            this.maxBatchSize = v;
        }

        public long getMaxBatchBytes() {
            return maxBatchBytes;
        }

        public void setMaxBatchBytes(long v) {
            this.maxBatchBytes = v;
        }

        public int getDedupeTtlHours() {
            return dedupeTtlHours;
        }

        public void setDedupeTtlHours(int v) {
            this.dedupeTtlHours = v;
        }
    }

    public static class Alert {
        private Duration evalInterval = Duration.ofSeconds(60);
        private List<String> peakWindows = List.of("07:00-10:00", "17:00-20:00");
        private int escalateAfterMinutes = 15;
        /** P0 断流类规则禁止静默，防止「失明期」被掩盖 */
        private List<String> nonSilenceableRules = List.of("R01", "R03", "R04");

        public Duration getEvalInterval() {
            return evalInterval;
        }

        public void setEvalInterval(Duration v) {
            this.evalInterval = v;
        }

        public List<String> getPeakWindows() {
            return peakWindows;
        }

        public void setPeakWindows(List<String> v) {
            this.peakWindows = v;
        }

        public int getEscalateAfterMinutes() {
            return escalateAfterMinutes;
        }

        public void setEscalateAfterMinutes(int v) {
            this.escalateAfterMinutes = v;
        }

        public List<String> getNonSilenceableRules() {
            return nonSilenceableRules;
        }

        public void setNonSilenceableRules(List<String> v) {
            this.nonSilenceableRules = v;
        }
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getDictVersion() {
        return dictVersion;
    }

    public void setDictVersion(String dictVersion) {
        this.dictVersion = dictVersion;
    }

    public Store getStore() {
        return store;
    }

    public void setStore(Store store) {
        this.store = store;
    }

    public Query getQuery() {
        return query;
    }

    public void setQuery(Query query) {
        this.query = query;
    }

    public Ingest getIngest() {
        return ingest;
    }

    public void setIngest(Ingest ingest) {
        this.ingest = ingest;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert;
    }
}
