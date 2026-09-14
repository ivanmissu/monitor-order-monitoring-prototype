package com.minitor.server.alert;

import com.minitor.server.domain.Dims;
import com.minitor.server.store.MinitorStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * §14 通知出站。IM webhook 适配层的统一 payload 合同。
 *
 * <p><b>告警必须可行动</b>：三链接（下钻 / 口径 / runbook）为强制字段，
 * 缺失时规则注册即被拒绝；不可行动的告警降级进大盘，不发通知。
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);

    /** 生成 IM 卡片体。飞书 / 企微 / 钉钉由各自适配器再做一次结构映射。 */
    public Map<String, Object> card(MinitorStore.AlertRow row) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("level", row.level().name());
        card.put("title", row.title());
        card.put("metric_line", formatValue(row) + " vs " + formatBaseline(row)
                + "（" + (row.deltaPp() >= 0 ? "+" : "") + row.deltaPp() + "pp）");
        card.put("dimension", row.scopeText());
        card.put("duration", "持续 " + row.periods() + " 个周期");
        card.put("links", List.of(
                Map.of("text", "下钻面板", "url",
                        "https://minitor.internal/redirect/drilldown?alert_id=" + row.alertId()),
                Map.of("text", "指标口径", "url",
                        "https://minitor.internal/dict/" + row.metricId() + "?v=" + row.metricVersion()),
                Map.of("text", "Runbook", "url", row.runbook())));
        card.put("actions", List.of(
                Map.of("text", "认领", "api", "/api/v1/alerts/" + row.alertId() + "/ack"),
                Map.of("text", "静默 30min", "api", "/api/v1/silences")));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("card", card);
        payload.put("at", List.of("oncall-primary"));
        payload.put("escalate_after_min", row.level() == Dims.AlertLevel.P1 ? 15 : 0);
        payload.put("channels", channelsOf(row.level()));
        return payload;
    }

    /**
     * 按分级路由：
     * P0 → IM 强提醒 + 短信（电话二期）；P1 → IM @值班，15min 未认领升级备值；
     * P2 → IM 频道；P3 → 只进大盘，不发通知。
     */
    public void dispatch(MinitorStore.AlertRow row) {
        if (!row.level().notifiable()) {
            log.debug("[{}] P3 告警只进大盘，不发通知", row.ruleId());
            return;
        }
        Map<String, Object> payload = card(row);
        // 生产环境在此调用 IM webhook / 短信网关；此处仅记录，避免联调误发
        log.info("通知路由 level={} channels={} alert_id={} title={}",
                row.level(), channelsOf(row.level()), row.alertId(), row.title());
    }

    private static List<String> channelsOf(Dims.AlertLevel level) {
        return switch (level) {
            case P0 -> List.of("im_strong", "sms");
            case P1 -> List.of("im_at");
            case P2 -> List.of("im_channel");
            case P3 -> List.of();
        };
    }

    private static String formatValue(MinitorStore.AlertRow row) {
        return row.denominator() > 0
                ? String.format("%.2f%%", row.value() * 100)
                : String.format("%.0f", row.value());
    }

    private static String formatBaseline(MinitorStore.AlertRow row) {
        return row.denominator() > 0
                ? String.format("阈值 %.2f%%", row.baseline() * 100)
                : String.format("阈值 %.0f", row.baseline());
    }
}
