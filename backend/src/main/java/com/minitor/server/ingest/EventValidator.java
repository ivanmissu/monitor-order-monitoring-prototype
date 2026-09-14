package com.minitor.server.ingest;

import com.minitor.server.dict.DictSeed;
import com.minitor.server.domain.BizLine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 事件质量校验：字典校验 event_type 枚举、必填维度非空、props 白名单与 PII 检测。
 * 违规事件进 {@code ods_dirty_event} 死信表并计数告警，<b>不污染正式数据</b>。
 */
@Component
public class EventValidator {

    /** 明文手机号 / 身份证 / 邮箱：命中即判 PII 违规。 */
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[\\dXx](?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");

    public record Issue(int index, String code, String field, String msg) {
    }

    public record Result(List<EventEnvelope> accepted, List<Issue> issues) {

        public boolean hasIssues() {
            return !issues.isEmpty();
        }
    }

    private final Map<String, DictSeed.EventTypeDef> registry = new LinkedHashMap<>();

    public EventValidator() {
        DictSeed.eventTypes().forEach(e -> registry.put(e.eventType(), e));
    }

    public Result validate(List<EventEnvelope> events, boolean strict) {
        List<EventEnvelope> accepted = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();

        for (int i = 0; i < events.size(); i++) {
            EventEnvelope e = events.get(i);
            List<Issue> local = new ArrayList<>();

            // ① event_type 必须已登记
            DictSeed.EventTypeDef def = e.eventType() == null ? null : registry.get(e.eventType());
            if (def == null) {
                local.add(new Issue(i, "EVENT_TYPE_UNKNOWN", "event_type",
                        "未登记，需先在字典注册并声明 supersedes"));
            }

            // ② 必填维度（维度必须为发生时刻快照值）
            List<String> missing = new ArrayList<>();
            if (isBlank(e.eventId())) {
                missing.add("event_id");
            }
            if (e.eventTime() == null) {
                missing.add("event_time");
            }
            if (isBlank(e.orderId())) {
                missing.add("order_id");
            }
            if (isBlank(e.bizLine())) {
                missing.add("biz_line");
            }
            if (e.cityId() == null) {
                missing.add("city_id");
            }
            if (!missing.isEmpty()) {
                local.add(new Issue(i, "REQUIRED_DIM_MISSING", String.join(",", missing),
                        "维度必须为发生时刻快照值"));
            }

            // ③ biz_line 枚举合法
            if (!isBlank(e.bizLine())) {
                try {
                    BizLine b = BizLine.of(e.bizLine());
                    if (b.isAll()) {
                        local.add(new Issue(i, "INVALID_ENUM", "biz_line", "不接受 all，必须为具体业务线"));
                    }
                } catch (Exception ex) {
                    local.add(new Issue(i, "INVALID_ENUM", "biz_line", "未知业务线: " + e.bizLine()));
                }
            }

            // ④ 金额不可为 null（否则聚合口径不确定）
            if (e.amount() == null) {
                local.add(new Issue(i, "BAD_AMOUNT", "amount", "无金额事件必须显式填 0，不可为 null"));
            }

            // ⑤ 事件时间越界（未来超 5 分钟 / 过去超 180 天）
            if (e.eventTime() != null) {
                if (e.eventTime().isAfter(now.plusMinutes(5))) {
                    local.add(new Issue(i, "OUT_OF_RANGE", "event_time", "事件时间晚于服务端 5 分钟以上"));
                } else if (Duration.between(e.eventTime(), now).toDays() > 180) {
                    local.add(new Issue(i, "OUT_OF_RANGE", "event_time", "超出 ODS 保留期 180 天"));
                }
            }

            // ⑥ props 白名单 + PII 检测
            if (def != null && e.props() != null) {
                for (Map.Entry<String, Object> kv : e.props().entrySet()) {
                    if (!def.propsWhitelist().contains(kv.getKey())) {
                        local.add(new Issue(i, "PROPS_NOT_WHITELISTED", "props." + kv.getKey(),
                                "字段未在白名单内，允许：" + def.propsWhitelist()));
                    }
                    if (containsPii(String.valueOf(kv.getValue()))) {
                        local.add(new Issue(i, "PII_DETECTED", "props." + kv.getKey(),
                                "命中 PII 规则（手机号/证件号/邮箱），禁止明文上报"));
                    }
                }
            }
            if (containsPii(e.driverIdHash())) {
                local.add(new Issue(i, "PII_DETECTED", "driver_id_hash", "必须为 SHA256 脱敏值"));
            }

            if (local.isEmpty()) {
                accepted.add(e);
            } else {
                issues.addAll(local);
                if (!strict) {
                    // 非严格模式下仍拒收该条，但不影响同批其他事件
                    continue;
                }
            }
        }
        return new Result(accepted, issues);
    }

    private static boolean containsPii(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        return PHONE.matcher(value).find() || ID_CARD.matcher(value).find()
                || EMAIL.matcher(value).find();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
