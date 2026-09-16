import type { BizLine } from "@/entities/business/model";
import type { AlertView } from "@/services/monitor/client";
import { fmtDeltaPp, fmtDurationText, fmtInt, fmtRatio } from "@/services/monitor/format";
import type { AlertItem } from "./alert";

/** 将后端 AlertView 映射为前端告警列表模型。 */
export function toAlertItem(alert: AlertView): AlertItem {
  const isRatio = alert.baseline_kind === "threshold" || alert.value <= 1;
  const valueStr = isRatio && alert.value <= 1 ? fmtRatio(alert.value, 1) : fmtInt(alert.value);
  const baselineValue = alert.value <= 1 ? fmtRatio(alert.baseline, 1) : fmtInt(alert.baseline);
  const baseStr = alert.baseline_kind === "threshold" ? `阈值 ${baselineValue}` : `基线 ${baselineValue}`;

  return {
    id: alert.alert_id,
    level: (alert.level as AlertItem["level"]) || "P2",
    title: alert.title,
    scope: alert.scope?.text || "—",
    time: fmtDurationText(alert.duration_sec, alert.periods),
    value: valueStr,
    baseline: baseStr,
    delta: fmtDeltaPp(alert.delta_pp),
    status: alert.status === "firing" ? "firing" : "claimed",
    metric: `${alert.metric?.id ?? ""}${alert.metric?.version ? ` · ${alert.metric.version}` : ""}`,
    biz: (alert.biz_line as BizLine) || "all",
  };
}
