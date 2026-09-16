package com.monitor.sdk.agent;

import com.monitor.sdk.annotation.MonitorMetricEvent;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatchers;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;

/**
 * Monitor Java Agent 入口。
 *
 * <p>Agent 只织入带 {@link MonitorMetricEvent} 的方法，避免按包名全量扫描或对所有请求
 * 产生不可控的指标基数。上报失败、事件字段映射失败都只会被隔离记录，不会影响业务逻辑。</p>
 */
public final class MonitorMetricsAgent {

    private MonitorMetricsAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        try {
            AgentConfig config = AgentConfig.from(agentArgs);
            if (!config.isEnabled()) {
                log("disabled by monitor.sdk.enabled=false");
                return;
            }
            AgentRuntime.initialize(config);
            Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    AgentRuntime.close();
                }
            }, "monitor-metrics-agent-shutdown"));

            new AgentBuilder.Default()
                    .ignore(ElementMatchers.nameStartsWith("com.monitor.sdk.agent."))
                    .type(ElementMatchers.declaresMethod(ElementMatchers.isAnnotatedWith(MonitorMetricEvent.class)))
                    .transform(new AgentBuilder.Transformer() {
                        @Override
                        public DynamicType.Builder<?> transform(DynamicType.Builder<?> builder,
                                                                TypeDescription typeDescription,
                                                                ClassLoader classLoader,
                                                                JavaModule module,
                                                                ProtectionDomain protectionDomain) {
                            return builder.visit(Advice.to(MonitorEventAdvice.class)
                                    .on(ElementMatchers.isMethod()
                                            .and(ElementMatchers.isAnnotatedWith(MonitorMetricEvent.class))));
                        }
                    })
                    .with(new AgentBuilder.Listener.Adapter() {
                        @Override
                        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                                            boolean loaded, Throwable throwable) {
                            log("failed to instrument " + typeName + ": " + throwable.getMessage());
                        }
                    })
                    .installOn(instrumentation);
            log("started; endpoint=" + config.getReporterConfig().getEndpoint());
        } catch (Throwable ex) {
            // javaagent 属于旁路能力，配置错误也绝不应让宿主 JVM 无法启动。
            log("not started: " + ex.getMessage());
        }
    }

    private static void log(String message) {
        System.err.println("[monitor-metrics-agent] " + message);
    }
}
