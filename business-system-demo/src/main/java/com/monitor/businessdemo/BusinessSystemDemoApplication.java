package com.monitor.businessdemo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 一个尽量贴近真实业务服务的最小示例：订单状态变化时通过 Monitor SDK
 * 旁路上报事件，业务接口本身不依赖监控服务才能完成。
 */
@SpringBootApplication
public class BusinessSystemDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(BusinessSystemDemoApplication.class, args);
    }
}
