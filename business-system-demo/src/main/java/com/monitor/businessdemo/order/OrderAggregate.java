package com.monitor.businessdemo.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** 仅用于演示的内存订单；真实业务系统中这里通常对应领域对象和业务数据库。 */
final class OrderAggregate {

    private final String orderId;
    private final String tripId;
    private final String bizLine;
    private final long cityId;
    private final String seatType;
    private final long amountFen;
    private final String driverId;
    private final Instant createdAt;
    private final List<String> reportedEvents = new ArrayList<>();
    private String status;

    OrderAggregate(String orderId, String tripId, String bizLine, long cityId,
                   String seatType, long amountFen, String driverId) {
        this.orderId = orderId;
        this.tripId = tripId;
        this.bizLine = bizLine;
        this.cityId = cityId;
        this.seatType = seatType;
        this.amountFen = amountFen;
        this.driverId = driverId;
        this.createdAt = Instant.now();
        this.status = "created";
    }

    String orderId() {
        return orderId;
    }

    String tripId() {
        return tripId;
    }

    String bizLine() {
        return bizLine;
    }

    long cityId() {
        return cityId;
    }

    String seatType() {
        return seatType;
    }

    long amountFen() {
        return amountFen;
    }

    String driverId() {
        return driverId;
    }

    synchronized String status() {
        return status;
    }

    synchronized void status(String nextStatus) {
        this.status = nextStatus;
    }

    synchronized void reported(String eventType) {
        reportedEvents.add(eventType);
    }

    synchronized OrderSnapshot snapshot() {
        return new OrderSnapshot(orderId, tripId, bizLine, cityId, seatType, amountFen,
                status, createdAt, List.copyOf(reportedEvents));
    }
}
