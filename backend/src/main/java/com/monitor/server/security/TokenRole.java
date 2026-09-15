package com.monitor.server.security;

import java.util.Set;

/**
 * Token 角色（接口文档 §03）。权限即数据源：不同角色绑定不同 ClickHouse 账号与可访问表。
 */
public enum TokenRole {

    /** grafana_dash：仅 agg_* + 字典读 */
    DASH_READ("grafana_dash", Set.of("agg", "dict")),
    /** cs_detail：ods_order_event（按 city_id 行策略） */
    CS_DETAIL("cs_detail", Set.of("ods", "dict")),
    /** alert_engine + 用户身份：agg_* + 字典 + 告警事件表，可写处置动作 */
    ALERT_OPS("alert_engine", Set.of("agg", "dict", "alert")),
    /** biz_producer：无查询权，仅事件上报 */
    INGEST("biz_producer", Set.of("ingest")),
    /** monitor_admin：全部 */
    ADMIN("monitor_admin", Set.of("agg", "ods", "dict", "alert", "ingest", "admin"));

    private final String account;
    private final Set<String> scopes;

    TokenRole(String account, Set<String> scopes) {
        this.account = account;
        this.scopes = scopes;
    }

    /** 对应的 ClickHouse / 字典库账号名。 */
    public String account() {
        return account;
    }

    public boolean can(String scope) {
        return scopes.contains(scope);
    }
}
