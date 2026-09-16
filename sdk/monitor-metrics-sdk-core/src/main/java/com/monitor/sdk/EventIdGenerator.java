package com.monitor.sdk;

/**
 * 生成上报事件的全链路幂等 ID。
 *
 * <p>调用方可以实现此接口并接入自身的雪花 ID 服务。请保证同一个业务状态迁移在
 * 重试、回放时使用同一个 ID；SDK 默认生成时间有序的 64 位字符串 ID。</p>
 */
public interface EventIdGenerator {

    String nextId();
}
