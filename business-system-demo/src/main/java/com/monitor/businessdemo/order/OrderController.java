package com.monitor.businessdemo.order;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 业务系统自身的 API；浏览器只访问这里，不接触 Monitor ingest token。 */
@RestController
@RequestMapping("/api/demo/orders")
public class OrderController {

    private final BusinessOrderService orders;

    public OrderController(BusinessOrderService orders) {
        this.orders = orders;
    }

    @GetMapping
    public List<OrderSnapshot> list() {
        return orders.list();
    }

    @GetMapping("/{orderId}")
    public OrderSnapshot get(@PathVariable String orderId) {
        return orders.find(orderId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderSnapshot create(@RequestBody(required = false) CreateOrderRequest request) {
        CreateOrderRequest body = request == null ? new CreateOrderRequest(
                null, "carpool", null, "standard", 8650L, "demo") : request;
        return orders.create(new BusinessOrderService.CreateOrderCommand(
                body.orderId(), body.bizLine(), body.cityId(), body.seatType(),
                body.amountFen(), body.channel()));
    }

    @PostMapping("/{orderId}/actions")
    public OrderSnapshot action(@PathVariable String orderId,
                                @RequestBody ActionRequest request) {
        if (request == null || request.action() == null) {
            throw new IllegalArgumentException("action 不能为空");
        }
        return orders.action(orderId, request.action());
    }

    public record CreateOrderRequest(String orderId, String bizLine, Long cityId,
                                     String seatType, Long amountFen, String channel) {
    }

    public record ActionRequest(String action) {
    }
}
