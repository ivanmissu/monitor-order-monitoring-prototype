package com.monitor.server.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 双数据源装配 —— 权限即数据源（接口文档 §03）。
 *
 * <p>聚合层与明细层使用不同 ClickHouse 账号与不同 query profile，
 * 实现资源隔离：客服的 ODS 慢查询不会占满大盘查询的内存配额。
 */
@Configuration
@ConditionalOnProperty(name = "monitor.store.kind", havingValue = "clickhouse", matchIfMissing = true)
public class StoreConfig {

    /** grafana_dash：只读 agg_*，短超时、低内存，供大盘与告警求值。 */
    @Bean
    @Primary
    public DataSource aggDataSource(MonitorProperties props) {
        return build(props.getStore().getAgg(), "monitor-agg", 16,
                "max_execution_time=10,max_memory_usage=2000000000");
    }

    /** cs_detail：只读 ods_order_event，长超时、高内存，但连接数严格受限。 */
    @Bean
    public DataSource odsDataSource(MonitorProperties props) {
        return build(props.getStore().getOds(), "monitor-ods", 4,
                "max_execution_time=60,max_memory_usage=6000000000,max_rows_to_read=500000000");
    }

    private DataSource build(MonitorProperties.Store.Ds ds, String poolName,
                             int maxPoolSize, String customSettings) {
        HikariConfig cfg = new HikariConfig();
        cfg.setPoolName(poolName);
        cfg.setJdbcUrl(ds.getUrl());
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(ds.getPassword());
        cfg.setMaximumPoolSize(maxPoolSize);
        cfg.setMinimumIdle(1);
        cfg.setConnectionTimeout(5_000);
        cfg.setValidationTimeout(3_000);
        // ClickHouse 侧 profile 限制，与账号权限共同构成资源隔离
        cfg.addDataSourceProperty("custom_http_params", customSettings);
        return new HikariDataSource(cfg);
    }
}
