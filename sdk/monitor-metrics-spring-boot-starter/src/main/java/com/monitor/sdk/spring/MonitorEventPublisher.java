package com.monitor.sdk.spring;

import com.monitor.sdk.EventReporter;
import com.monitor.sdk.MonitorEvent;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Spring Boot 推荐的上报入口。
 *
 * <p>调用 {@link #publish(MonitorEvent)} 后，事件会在当前事务成功提交后才进入 SDK 队列；
 * 没有事务时则立即进入队列。这样监控不会记录已回滚的状态迁移。对跨进程可靠投递要求更高
 * 的系统仍应配合业务 Outbox 使用。</p>
 */
public final class MonitorEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final EventReporter reporter;

    public MonitorEventPublisher(ApplicationEventPublisher applicationEventPublisher, EventReporter reporter) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.reporter = reporter;
    }

    /** 事务提交后上报；没有事务时立即上报。 */
    public void publish(MonitorEvent event) {
        applicationEventPublisher.publishEvent(new MonitorEventPublication(event));
    }

    /** 绕过事务同步，适用于无状态的异步消费或明确已提交的 Outbox relay。 */
    public boolean reportNow(MonitorEvent event) {
        return reporter.report(event);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void afterCommit(MonitorEventPublication publication) {
        reporter.report(publication.getEvent());
    }
}
