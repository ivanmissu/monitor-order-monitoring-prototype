package com.monitor.sdk.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记由 Monitor Java Agent 在方法成功退出后上报的业务指标事件。
 *
 * <p>事件类型必须先在 Monitor 指标字典中登记。业务线 {@link #bizLine()} 建议在项目的配置文件中
 * 全局统一配置（例如在 {@code application.yml}、{@code application.properties} 或 {@code monitor-sdk.properties}
 * 中设置 {@code monitor.sdk.biz-line=driver}），无需在每个方法注解上重复标注；若注解显式声明则优先使用注解值。</p>
 *
 * <p>针对业务方法入参支持两种参数解析方式：
 * <ul>
 *   <li><b>实体类 / DTO / Record / Map 等复杂入参（推荐）：</b>使用各 {@code *Path} 字段指定属性路径（例如
 *       {@code orderIdPath = "orderId"}、{@code orderIdPath = "0.orderId"}、{@code orderIdPath = "0.order.id"}
 *       或 {@code orderIdPath = "0.order_id"}）。</li>
 *   <li><b>基础类型多参数入参：</b>可继续使用各 {@code *Arg} 字段指定方法参数的 0 基下标（例如 {@code orderIdArg = 0}）。</li>
 *   <li><b>扩展属性 props：</b>使用 {@link #propNames()} 声明扩展属性名时，可通过 {@link #propPaths()} 指定各属性的提取路径，
 *       或通过 {@link #propArgIndexes()} 指定参数下标。{@code propNames} 必须与 {@code propPaths}（或 {@code propArgIndexes}）等长，
 *       且属性必须在该事件类型的白名单中。</li>
 * </ul>
 * </p>
 *
 * <p>当 {@link #reportOnThrowable()} 为 false（默认）时，仅成功返回的方法会被上报。</p>
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface MonitorMetricEvent {

    /** 事件类型，必须在指标字典中登记，例如 {@code order_delivered}。 */
    String eventType();

    /**
     * 业务线标识。缺省为空字符串时，自动使用项目中配置文件设置的全局 {@code monitor.sdk.biz-line}；
     * 若显式填写则优先使用当前注解上的值。
     */
    String bizLine() default "";

    /** 订单 ID 的参数下标（0 基）。若配置了 {@link #orderIdPath()} 则优先使用 Path。 */
    int orderIdArg() default -1;

    /** 订单 ID 的属性提取路径，支持实体类字段/Getter、Record 访问器、Map Key 以及多级嵌套路径，例如 {@code "orderId"}、{@code "0.orderId"}、{@code "0.order_id"}。 */
    String orderIdPath() default "";

    /** 行程 ID 的参数下标（0 基）。若配置了 {@link #tripIdPath()} 则优先使用 Path。 */
    int tripIdArg() default -1;

    /** 行程 ID 的属性提取路径。 */
    String tripIdPath() default "";

    /** 城市 ID 的参数下标（0 基）。若配置了 {@link #cityIdPath()} 则优先使用 Path。 */
    int cityIdArg() default -1;

    /** 城市 ID 的属性提取路径，例如 {@code "cityId"}、{@code "0.cityId"}。 */
    String cityIdPath() default "";

    /** 车型/座席类型的参数下标（0 基）。若配置了 {@link #seatTypePath()} 则优先使用 Path。 */
    int seatTypeArg() default -1;

    /** 车型/座席类型的属性提取路径。 */
    String seatTypePath() default "";

    /** 司机 ID 的参数下标（0 基），会自动进行 SHA-256 脱敏。若配置了 {@link #driverIdPath()} 则优先使用 Path。 */
    int driverIdArg() default -1;

    /** 司机 ID 的属性提取路径，提取后会自动进行 SHA-256 脱敏。 */
    String driverIdPath() default "";

    /** 金额（分）的参数下标（0 基）。若配置了 {@link #amountFenPath()} 则优先使用 Path。 */
    int amountFenArg() default -1;

    /** 金额（分）的属性提取路径，例如 {@code "amountFen"}、{@code "0.amount"}。 */
    String amountFenPath() default "";

    /** 事件时间的参数下标（0 基）。若配置了 {@link #eventTimePath()} 则优先使用 Path。 */
    int eventTimeArg() default -1;

    /** 事件时间的属性提取路径，支持 Instant、OffsetDateTime、Date、时间戳（毫秒）或 ISO-8601 字符串；缺省时取当前时间。 */
    String eventTimePath() default "";

    /** 扩展属性名称列表，必须与 {@link #propPaths()} 或 {@link #propArgIndexes()} 等长。 */
    String[] propNames() default {};

    /** 扩展属性对应的参数下标列表。 */
    int[] propArgIndexes() default {};

    /** 扩展属性对应的提取路径列表，例如 {@code {"0.tripDurationSec", "0.channel"}}。 */
    String[] propPaths() default {};

    /** 将方法异常也作为一次业务事件上报；仅在该 eventType 的契约明确允许时使用。 */
    boolean reportOnThrowable() default false;
}
