// ── 展示层格式化 ──
// 后端遵循「金额单位分、比率 0-1」纪律，换算只在展示层完成。

import type { MetricValue } from "./client";

const nf = new Intl.NumberFormat("zh-CN");

export const fmtInt = (n: number) => nf.format(Math.round(n));

/** 分 → 「¥ N万」。 */
export function fmtGtvFen(fen: number): string {
  const yuan = fen / 100;
  if (yuan >= 10000) return `¥ ${nf.format(Math.round(yuan / 10000))}万`;
  return `¥ ${nf.format(Math.round(yuan))}`;
}

export const fmtYuan = (fen: number) => `¥${(fen / 100).toFixed(2)}`;

/** 比率 0-1 → 百分比字符串。 */
export const fmtRatio = (r: number, digits = 1) => `${(r * 100).toFixed(digits)}%`;

/** delta（比率变化）→ 「+4.2%」。 */
export function fmtDelta(delta?: number): string {
  if (delta === undefined || delta === null) return "—";
  const pct = delta * 100;
  return `${pct >= 0 ? "+" : ""}${pct.toFixed(1)}%`;
}

/** delta_pp（百分点变化）→ 「+3.2pp」。 */
export function fmtDeltaPp(pp?: number): string {
  if (pp === undefined || pp === null) return "—";
  return `${pp >= 0 ? "+" : ""}${pp.toFixed(1)}pp`;
}

/** 将后端 MetricValue 渲染为卡片需要的 value/delta/good 三元组。 */
export function metricDisplay(v: MetricValue): { value: string; delta: string; good: boolean } {
  let value: string;
  switch (v.unit) {
    case "ratio":
      value = fmtRatio(v.value, 2);
      break;
    case "fen":
      value = fmtGtvFen(v.value);
      break;
    case "ms":
      value = `${fmtInt(v.value)}ms`;
      break;
    default:
      value = fmtInt(v.value);
  }
  const delta = v.delta_pp !== undefined ? fmtDeltaPp(v.delta_pp) : fmtDelta(v.delta);
  return { value, delta, good: v.good };
}

/** ISO 时间 → HH:mm:ss（Asia/Shanghai 展示）。 */
export function fmtClock(iso?: string): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString("zh-CN", { hour12: false, timeZone: "Asia/Shanghai" });
}

/** ISO 时间 → 「YYYY-MM-DD HH:mm:ss」。 */
export function fmtDateTime(iso?: string): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d
    .toLocaleString("zh-CN", { hour12: false, timeZone: "Asia/Shanghai" })
    .replace(/\//g, "-");
}

/** 秒 → 「Nm Ns」/「Nh Nm」。 */
export function fmtDuration(sec: number): string {
  if (sec < 60) return `${sec}s`;
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  if (m < 60) return `${m}m ${s}s`;
  const h = Math.floor(m / 60);
  return `${h}h ${m % 60}m`;
}

/** 「持续 N 分钟 / N 周期」文案。 */
export function fmtDurationText(sec: number, periods?: number): string {
  if (periods && periods > 0) return `持续 ${periods} 周期`;
  const m = Math.round(sec / 60);
  return `持续 ${m} 分钟`;
}
