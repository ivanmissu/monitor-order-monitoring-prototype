package com.monitor.sdk.spring;

import com.monitor.sdk.AsyncHttpEventReporter;
import com.monitor.sdk.EventReporter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;

/** Monitor 上报 SDK 的 Spring Boot 自动装配。 */
@AutoConfiguration
@ConditionalOnClass(ApplicationEventPublisher.class)
@EnableConfigurationProperties(MonitorSdkProperties.class)
@ConditionalOnProperty(prefix = "monitor.sdk", name = "enabled", havingValue = "true")
public class MonitorSdkAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EventReporter.class)
    public EventReporter monitorEventReporter(MonitorSdkProperties properties) {
        return new AsyncHttpEventReporter(properties.toReporterConfig());
    }

    @Bean
    @ConditionalOnMissingBean
    public MonitorEventPublisher monitorEventPublisher(ApplicationEventPublisher applicationEventPublisher,
                                                        EventReporter reporter) {
        return new MonitorEventPublisher(applicationEventPublisher, reporter);
    }
}
