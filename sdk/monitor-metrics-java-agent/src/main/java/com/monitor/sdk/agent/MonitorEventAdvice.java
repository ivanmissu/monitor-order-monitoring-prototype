package com.monitor.sdk.agent;

import net.bytebuddy.asm.Advice;

import java.lang.reflect.Method;

/** 被 Byte Buddy 内联到标注方法的 advice。 */
public final class MonitorEventAdvice {

    private MonitorEventAdvice() {
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void onExit(@Advice.Origin Method method,
                              @Advice.AllArguments Object[] arguments,
                              @Advice.Thrown Throwable error) {
        AgentRuntime.report(method, arguments, error);
    }
}
