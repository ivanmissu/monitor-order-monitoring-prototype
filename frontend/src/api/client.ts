// ── Monitor Server API Client ──
//
// 统一封装 monitor-server 的 /api/v1 读写接口（接口文档 docs/monitor-api.md）。
// 设计要点：
//  - 走相对路径 /api/v1，由 Vite dev proxy / 生产网关反向代理到 Spring Boot，浏览器端不硬编码服务地址。
//  - 按角色携带 Bearer token（演示态静态 token；生产由 SSO 换取短时效用户态 token）。
//  - 统一解包 { code, message, data } 响应外壳；非 0 code 抛出 ApiError。
//  - 读取必带响应头（X-Monitor-Freshness / partial / Grain / Dict-Version）供 UI 渲染口径水印。

export type BizLine =
  | "all"
  | "driver"
  | "transfer"
  | "carpool"
  | "designated"
  | "airport";

/** 演示态角色 token（见 application.yml monitor.security.tokens）。 */
export const TOKENS = {
  dash: "dash-token", // DASH_READ：大盘 / 聚合只读
  cs: "cs-token", // CS_DETAIL：客服明细
  oncall: "oncall-token", // ALERT_OPS：告警查询与处置
  ingest: "ingest-token", // INGEST：事件上报
  admin: "admin-token", // ADMIN：字典 / 规则 / 管理
} as const;

export type TokenRole = keyof typeof TOKENS;

/** API 基址：默认相对路径（由代理转发）；可用 VITE_API_BASE 覆盖为绝对地址。 */
const API_BASE = (import.meta.env?.VITE_API_BASE as string | undefined)?.replace(/\/$/, "") || "";

export interface Freshness {
  freshness: string | null;
  partial: boolean;
  grain: string | null;
  dictVersion: string | null;
}

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  request_id?: string;
  server_time?: string;
  data: T;
}

export class ApiError extends Error {
  code: number;
  requestId?: string;
  httpStatus?: number;
  constructor(code: number, message: string, requestId?: string, httpStatus?: number) {
    super(message);
    this.name = "ApiError";
    this.code = code;
    this.requestId = requestId;
    this.httpStatus = httpStatus;
  }
}

export interface ApiResult<T> {
  data: T;
  serverTime?: string;
  requestId?: string;
  freshness: Freshness;
}

function readFreshness(headers: Headers): Freshness {
  return {
    freshness: headers.get("X-Monitor-Freshness"),
    partial: headers.get("X-Monitor-partial") === "true",
    grain: headers.get("X-Monitor-Grain"),
    dictVersion: headers.get("X-Monitor-Dict-Version"),
  };
}

export interface RequestOptions {
  role?: TokenRole;
  method?: string;
  query?: Record<string, string | number | boolean | undefined | null>;
  body?: unknown;
  idempotencyKey?: string;
  signal?: AbortSignal;
}

function buildUrl(path: string, query?: RequestOptions["query"]): string {
  const url = `${API_BASE}/api/v1${path.startsWith("/") ? path : `/${path}`}`;
  if (!query) return url;
  const qs = new URLSearchParams();
  for (const [k, v] of Object.entries(query)) {
    if (v === undefined || v === null || v === "") continue;
    qs.append(k, String(v));
  }
  const s = qs.toString();
  return s ? `${url}?${s}` : url;
}

/** 底层请求：解包响应外壳，附带口径水印。 */
export async function apiRequest<T>(path: string, opts: RequestOptions = {}): Promise<ApiResult<T>> {
  const { role = "dash", method = "GET", query, body, idempotencyKey, signal } = opts;
  const headers: Record<string, string> = {
    Authorization: `Bearer ${TOKENS[role]}`,
    Accept: "application/json",
  };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;

  // 某些预览反向代理会剥掉 Authorization 头（后端将返回 40101）。
  // 因此同时以 `_monitor_role` query 参数声明角色，由 Vite dev proxy 在服务端
  // 还原为 Bearer token（见 vite.config.ts）；正常携带鉴权头的环境不受影响。
  const url = buildUrl(path, { ...query, _monitor_role: role });

  const res = await fetch(url, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
    signal,
  });

  const freshness = readFreshness(res.headers);
  let payload: ApiEnvelope<T> | null = null;
  try {
    payload = (await res.json()) as ApiEnvelope<T>;
  } catch {
    // 非 JSON 响应（例如网关错误页）。
  }

  if (!payload) {
    throw new ApiError(res.status, `HTTP ${res.status}`, undefined, res.status);
  }
  if (payload.code !== 0) {
    throw new ApiError(payload.code, payload.message || `error ${payload.code}`, payload.request_id, res.status);
  }
  return {
    data: payload.data,
    serverTime: payload.server_time,
    requestId: payload.request_id,
    freshness,
  };
}

const bizParam = (biz?: BizLine) => (biz && biz !== "all" ? biz : biz === "all" ? "all" : undefined);

// ══════════════════════ 类型（与后端 snake_case DTO 对应） ══════════════════════

export interface MetricValue {
  metric: string;
  version?: string;
  label: string;
  value: number;
  unit: string;
  numerator?: number;
  denominator?: number;
  delta_pp?: number;
  delta?: number;
  delta_dir?: string;
  good: boolean;
  baseline_30d?: number;
  compare_note?: string;
  partial: boolean;
  degraded?: boolean;
  sample?: number;
}

export interface LinkNode {
  key: string;
  label: string;
  status: "ok" | "warn" | "bad" | string;
  detail?: string;
  metric?: string;
  alert_id?: number;
  metrics?: Record<string, number>;
  since?: string;
}

export interface LinkHealth {
  evaluated_at: string;
  window_delay_sec: number;
  abnormal: number;
  blind: boolean;
  silenced_by_backfill: boolean;
  nodes: LinkNode[];
  note?: string;
}

export interface BizCard {
  biz_line: BizLine;
  label: string;
  color: string;
  orders: number;
  delivered: number;
  delivery_rate: number;
  gtv_fen: number;
  delta: number;
  rate_good: boolean;
  top_alert?: { title: string; level: string } | null;
}

export interface AlertScope {
  city_id: number;
  city_name: string;
  seat_type: string;
  text: string;
}

export interface AlertView {
  alert_id: number;
  rule_id: string;
  level: "P0" | "P1" | "P2" | "P3" | string;
  title: string;
  biz_line: BizLine;
  scope: AlertScope;
  fired_at: string;
  duration_sec: number;
  periods: number;
  value: number;
  baseline: number;
  baseline_kind: string;
  delta_pp: number;
  sample?: { numerator: number; denominator: number };
  status: "firing" | "claimed" | "acked" | "resolved" | string;
  ack_by?: string | null;
  ack_at?: string | null;
  metric: { id: string; version: string; dict_url: string };
  links: { drilldown: string; runbook: string; notify_channels?: string[] };
  noise?: { deduped: number; aggregated_cities: number; suppressed_by?: string | null };
}

export interface Bootstrap {
  link_health: LinkHealth;
  kpi: MetricValue[];
  biz_cards: BizCard[];
  alerts: AlertView[];
  alert_meta: Record<string, number>;
}

export interface FunnelStep {
  key: string;
  label: string;
  value: number;
  rate?: number;
  numerator?: number;
  denominator?: number;
  prev_value?: number;
  leak?: number;
  leak_top_cities?: { city_id: number; name: string; delta_pp: number }[];
}

export interface Funnel {
  biz_line: BizLine;
  definition_version: string;
  rolling: boolean;
  note: string;
  steps: FunnelStep[];
}

export interface CompositionItem {
  biz_line: BizLine;
  label: string;
  value: number;
  share: number;
  color: string;
  delta: number;
}

export interface Composition {
  metric: string;
  total: number;
  items: CompositionItem[];
}

export interface CityRow {
  city_id: number;
  name: string;
  delivered: number;
  delivery_rate: number;
  gtv_fen: number;
  yoy: number;
  firing_alerts: number;
  priority: string;
}

export interface HeatCell {
  hour: string;
  value: number;
  intensity: number;
  sample: number;
  peak?: boolean;
}

export interface Heatmap {
  hours: string[];
  scale: Record<string, unknown>;
  rows: { city_id: number; name: string; cells: HeatCell[] }[];
}

export interface QualitySummaryItem {
  value: MetricValue;
  caliber?: string;
}

export interface CancelMatrix {
  totals: { cancel_cnt: number; denominator: number };
  marginal: { key: string; label: string; value: number; share: number; color: string }[];
  full: unknown[];
}

export interface RiskCity {
  city_id: number;
  name: string;
  score: number;
  level: string;
  main_metric: string;
  main_label: string;
  main_value: number;
  suggested_action: string;
}

export interface TechKpi {
  metric: string;
  label: string;
  value: number;
  unit: string;
  delta?: number;
  delta_pp?: number;
  good?: boolean;
}

export interface ApiView {
  api_id: string;
  path: string;
  service: string;
  service_label: string;
  biz_line: BizLine;
  qps: number;
  fail_rate: number;
  fail_rate_threshold: { warn: number; bad: number };
  status: "ok" | "warn" | "bad" | string;
  p50_ms: number;
  p99_ms: number;
  err_top: { code: string; share: number }[];
  trend: number[];
  upstream?: string;
  downstream: string[];
  linked_rule?: string;
  linked_alert_id?: number;
}

export interface Topology {
  window: string;
  generated_at: string;
  nodes: {
    id: string;
    label: string;
    sub: string;
    x: number;
    y: number;
    w: number;
    h: number;
    alerted: boolean;
    kind: string;
  }[];
  edges: {
    from: string;
    to: string;
    qps: number;
    error_rate: number;
    p99_ms: number;
    level: "ok" | "warn" | "bad" | string;
    alert_id?: number;
    metric?: string;
  }[];
  scale: Record<string, number>;
  suppression: unknown[];
}

export interface SlowCall {
  api_id: string;
  path: string;
  label: string;
  p99_ms: number;
  biz_line: BizLine;
}

export interface TimelineEvent {
  seq: number;
  event_time: string;
  event_type: string;
  label: string;
  note?: string;
  amount_fen?: number;
  props?: Record<string, unknown>;
  gap_from_prev_sec?: number;
  abnormal: boolean;
}

export interface Timeline {
  order_id: string;
  duration_sec: number;
  started_at: string;
  ended_at: string;
  query_cost_ms: number;
  events: TimelineEvent[];
  missing: string[];
}

export interface OrderView {
  order_id: string;
  biz_line: BizLine;
  status: string;
  status_label: string;
  city_id: number;
  city_name: string;
  seat_type: string;
  amount_fen: number;
  driver_id_hash_masked: string;
  trip_id: string;
  dt: string;
  event_count: number;
  completeness: { state: string; expected_nodes: number; present_nodes: number; missing: string[] };
  risk_flags: unknown[];
  privacy_note: string;
}

export interface PipelineComponent {
  component: string;
  status: string;
  throughput: string;
  heartbeat_at: string;
  detail?: string;
  extra?: string;
}

export interface MetaLag {
  consumer_lag: { p50_sec: number; p99_sec: number; max_partition: string };
  ingest_delay: { p50_sec: number; p99_sec: number; threshold_p99_sec: number; status: string };
  late: { definition: string; rate: number; cnt: number };
  buffered_fallback: { local_disk_events: number; replaying: boolean };
}

export interface AlertStats {
  week: string;
  fired: number;
  valid: number;
  precision: number;
  target: number;
  noise_rate: number;
  mtta_p50_sec: number;
  mttr_p50_sec: number;
  p0_ack_within5_min: number;
  top_noisy_rules: { rule_id: string; fired: number; valid: number; precision: number; suggestion: string }[];
  by_level: Record<string, number>;
}

// ══════════════════════ 端点封装 ══════════════════════

export const api = {
  health: () => fetch(`${API_BASE}/actuator/health`).then((r) => r.json()),

  // §05 值班哨
  sentinelBootstrap: (biz?: BizLine, signal?: AbortSignal) =>
    apiRequest<Bootstrap>("/sentinel/bootstrap", { role: "dash", query: { biz_line: bizParam(biz) }, signal }),
  overviewTimeseries: (biz?: BizLine, grain = "2h", signal?: AbortSignal) =>
    apiRequest<{ grain: string; partial: boolean; series: unknown[]; annotations: unknown[] }>(
      "/overview/timeseries",
      { role: "dash", query: { biz_line: bizParam(biz), grain }, signal }
    ),
  linkHealth: (signal?: AbortSignal) =>
    apiRequest<LinkHealth>("/overview/link-health", { role: "dash", signal }),

  // §06 经营大盘
  funnel: (biz?: BizLine, compareDate?: string, signal?: AbortSignal) =>
    apiRequest<Funnel>("/dashboard/funnel", {
      role: "dash",
      query: { biz_line: bizParam(biz), compare_date: compareDate },
      signal,
    }),
  composition: (metric?: string, signal?: AbortSignal) =>
    apiRequest<Composition>("/dashboard/composition", { role: "dash", query: { metric }, signal }),
  cityRank: (biz?: BizLine, sort = "delivered_desc", limit = 10, signal?: AbortSignal) =>
    apiRequest<CityRow[]>("/dashboard/city-rank", {
      role: "dash",
      query: { biz_line: bizParam(biz), sort, limit },
      signal,
    }),
  heatmap: (biz?: BizLine, metric = "accept_rate", signal?: AbortSignal) =>
    apiRequest<Heatmap>("/dashboard/heatmap", { role: "dash", query: { biz_line: bizParam(biz), metric }, signal }),

  // §07 履约质量
  qualitySummary: (biz?: BizLine, signal?: AbortSignal) =>
    apiRequest<QualitySummaryItem[]>("/quality/summary", { role: "dash", query: { biz_line: bizParam(biz) }, signal }),
  cancelMatrix: (biz?: BizLine, pivot = "by_fault", signal?: AbortSignal) =>
    apiRequest<CancelMatrix>("/quality/cancel-matrix", {
      role: "dash",
      query: { biz_line: bizParam(biz), pivot },
      signal,
    }),
  riskCities: (signal?: AbortSignal) =>
    apiRequest<RiskCity[]>("/quality/risk-cities", { role: "dash", signal }),

  // §08 客服工作台
  order: (orderId: string, signal?: AbortSignal) =>
    apiRequest<OrderView>(`/orders/${encodeURIComponent(orderId)}`, { role: "cs", signal }),
  orderEvents: (orderId: string, signal?: AbortSignal) =>
    apiRequest<Timeline>(`/orders/${encodeURIComponent(orderId)}/events`, { role: "cs", signal }),

  // §09 风控观测
  riskSummary: (biz?: BizLine, signal?: AbortSignal) =>
    apiRequest<MetricValue[]>("/risk/summary", { role: "dash", query: { biz_line: bizParam(biz) }, signal }),
  riskTimeseries: (biz?: BizLine, signal?: AbortSignal) =>
    apiRequest<unknown>("/risk/rules/timeseries", { role: "dash", query: { biz_line: bizParam(biz) }, signal }),
  riskTopEntities: (biz?: BizLine, signal?: AbortSignal) =>
    apiRequest<unknown[]>("/risk/top-entities", { role: "cs", query: { biz_line: bizParam(biz) }, signal }),

  // §10 接口监控
  techSummary: (biz?: BizLine, signal?: AbortSignal) =>
    apiRequest<TechKpi[]>("/tech/summary", { role: "dash", query: { biz_line: bizParam(biz) }, signal }),
  techApis: (biz?: BizLine, sort = "fail_rate_desc", signal?: AbortSignal) =>
    apiRequest<ApiView[]>("/tech/apis", { role: "dash", query: { biz_line: bizParam(biz), sort }, signal }),
  topology: (signal?: AbortSignal) =>
    apiRequest<Topology>("/tech/topology", { role: "dash", signal }),
  slowCalls: (signal?: AbortSignal) =>
    apiRequest<SlowCall[]>("/tech/slow-calls", { role: "dash", signal }),

  // §11 告警
  alerts: (status = "all", biz?: BizLine, signal?: AbortSignal) =>
    apiRequest<{ items: AlertView[]; meta: Record<string, number> }>("/alerts", {
      role: "dash",
      query: { status, biz_line: bizParam(biz) },
      signal,
    }),
  ackAlert: (alertId: number, user: string, note?: string) =>
    apiRequest<unknown>(`/alerts/${alertId}/ack`, {
      role: "oncall",
      method: "POST",
      body: { user, note },
      idempotencyKey: `ack-${alertId}-${Date.now()}`,
    }),
  alertStats: (signal?: AbortSignal) =>
    apiRequest<AlertStats>("/alerts/stats", { role: "dash", signal }),

  // §12 元监控
  pipeline: (signal?: AbortSignal) =>
    apiRequest<PipelineComponent[]>("/meta/pipeline", { role: "dash", signal }),
  lag: (signal?: AbortSignal) => apiRequest<MetaLag>("/meta/lag", { role: "dash", signal }),
  reconcile: (signal?: AbortSignal) => apiRequest<unknown>("/meta/reconcile", { role: "dash", signal }),

  // §04 字典
  bizLines: (signal?: AbortSignal) => apiRequest<unknown[]>("/dict/biz-lines", { role: "dash", signal }),
};
