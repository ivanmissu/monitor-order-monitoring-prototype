package com.monitor.businessdemo.simulation;

import com.monitor.businessdemo.order.BusinessOrderService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 一键生成多种订单状态，方便观察监控平台的事件接收与指标变化。 */
@RestController
@RequestMapping("/api/demo")
public class SimulationController {

    private final BusinessOrderService orders;

    public SimulationController(BusinessOrderService orders) {
        this.orders = orders;
    }

    @PostMapping("/simulate")
    public BusinessOrderService.SimulationResult simulate(
            @RequestBody(required = false) SimulationRequest request) {
        SimulationRequest body = request == null ? new SimulationRequest(10, "carpool", "mixed") : request;
        return orders.simulate(body.count() == null ? 10 : body.count(), body.bizLine(), body.scenario());
    }

    @GetMapping("/sdk/status")
    public BusinessOrderService.ReporterStatsView sdkStatus() {
        return orders.stats();
    }

    public record SimulationRequest(Integer count, String bizLine, String scenario) {
    }
}
