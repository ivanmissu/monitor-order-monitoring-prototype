package com.monitor.sdk.spring;

import com.monitor.sdk.MonitorEvent;

/** Spring 应用事件：Starter 在事务提交后将其异步交给 Monitor reporter。 */
public final class MonitorEventPublication {

    private final MonitorEvent event;

    public MonitorEventPublication(MonitorEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        this.event = event;
    }

    public MonitorEvent getEvent() {
        return event;
    }
}
