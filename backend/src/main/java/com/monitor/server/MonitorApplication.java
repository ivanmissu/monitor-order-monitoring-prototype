package com.monitor.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Monitor —— 旁路式订单业务监控服务。
 *
 * <p>三条贯穿全局的架构约束（与接口文档 §00 一致）：
 * <ol>
 *   <li><b>比率不落库</b>：聚合层只存可加性原子指标，一切比率在查询期由分子/分母派生；</li>
 *   <li><b>口径不硬编码</b>：所有指标定义读自指标字典，改口径 = 新版本，不改数据管道；</li>
 *   <li><b>权限即数据源</b>：五类 token 绑定不同 ClickHouse 账号与行策略，越权直接 403。</li>
 * </ol>
 *
 * <p>本服务对业务系统<b>只读</b>：消费领域事件，不回写任何业务存储。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class MonitorApplication {

    public static void main(String[] args) {
        SpringApplication.run(MonitorApplication.class, args);
    }
}
