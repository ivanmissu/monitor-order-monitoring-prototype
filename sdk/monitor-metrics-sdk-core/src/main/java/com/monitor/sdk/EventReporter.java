package com.monitor.sdk;

import java.time.Duration;

/** 异步、非阻塞的指标事件上报入口。 */
public interface EventReporter extends AutoCloseable {

    /**
     * 将事件放入内存队列，不会在业务线程执行网络 IO。
     *
     * @return 已进入发送队列时为 {@code true}；队列已满或 reporter 已关闭时为 {@code false}。
     */
    boolean report(MonitorEvent event);

    /**
     * 等待当前已入队的事件到达成功或永久失败状态。
     *
     * @return 在超时前完成时为 {@code true}，否则为 {@code false}。
     */
    boolean flush(Duration timeout);

    ReporterStats stats();

    /** 使用配置的 shutdownTimeout 尽力发送队列中的剩余事件。 */
    @Override
    void close();
}
