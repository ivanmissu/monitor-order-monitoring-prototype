package com.monitor.businessdemo.order;

import java.time.Instant;
import java.util.List;

/** 对外返回的订单快照，不暴露业务对象的可变状态。 */
public record OrderSnapshot(
        String orderId,
        String tripId,
        String bizLine,
        long cityId,
        String seatType,
        long amountFen,
        String status,
        Instant createdAt,
        List<String> reportedEvents) {
}
