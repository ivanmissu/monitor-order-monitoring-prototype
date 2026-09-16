package com.monitor.sdk;

import java.util.List;

/**
 * 发送结果回调。所有回调均在 SDK 的后台线程运行，异常会被 SDK 隔离。
 * 不要在回调内执行耗时 IO 或再次调用同一个 reporter 的 {@code flush()}。
 */
public interface DeliveryListener {

    DeliveryListener NOOP = new DeliveryListener() { };

    default void onDelivered(List<MonitorEvent> events, DeliveryReceipt receipt) {
    }

    default void onPermanentFailure(List<MonitorEvent> events, DeliveryFailure failure) {
    }

    default void onDiscarded(MonitorEvent event, String reason) {
    }
}
