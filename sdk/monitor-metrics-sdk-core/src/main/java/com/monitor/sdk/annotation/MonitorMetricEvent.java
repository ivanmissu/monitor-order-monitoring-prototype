package com.monitor.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记由 Monitor Java Agent 在方法成功退出后上报的业务指标事件。
 *
 * <p>各 {@code *Arg} 是方法参数的 0 基下标。事件类型必须先在 Monitor 指标字典中登记，
 * {@code propNames} 与 {@code propArgIndexes} 必须等长，且 props 必须在该事件类型白名单中。
 * 当 {@link #reportOnThrowable()} 为 false（默认）时，仅成功返回的方法会被上报。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MonitorMetricEvent {

    String eventType();

    String bizLine() default "";

    int orderIdArg() default -1;

    int tripIdArg() default -1;

    int cityIdArg() default -1;

    int seatTypeArg() default -1;

    int driverIdArg() default -1;

    int amountFenArg() default -1;

    int eventTimeArg() default -1;

    String[] propNames() default {};

    int[] propArgIndexes() default {};

    /** 将方法异常也作为一次业务事件上报；仅在该 eventType 的契约明确允许时使用。 */
    boolean reportOnThrowable() default false;
}
