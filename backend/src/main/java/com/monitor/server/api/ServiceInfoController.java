package com.monitor.server.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** API 服务入口，方便本地与预览环境直接确认后端已经启动。 */
@RestController
public class ServiceInfoController {

    @GetMapping("/")
    public Map<String, String> index() {
        return Map.of(
                "service", "monitor-server",
                "version", "1.4.0",
                "status", "UP",
                "health", "/actuator/health",
                "api_base", "/api/v1");
    }
}
