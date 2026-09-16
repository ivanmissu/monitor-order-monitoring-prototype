import { AlertTriangle, Check, ChevronRight, Database } from "lucide-react";
import {
  Area,
  AreaChart,
  CartesianGrid,
  Line,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { bizLines, type BizLine } from "@/entities/business/model";
import { api } from "@/services/monitor/client";
import { fmtDateTime, fmtGtvFen, fmtInt, fmtRatio, metricDisplay, fmtClock } from "@/services/monitor/format";
import { useApi, type ConnState } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";
import { MetricCell } from "@/shared/ui/MetricCell";
import {
  bizOverview,
  statusNodes,
  volumeDataAll,
} from "../data/mock-dashboard";
import type { AlertItem } from "../model/alert";
import { BusinessBadge } from "../components/BusinessBadge";
import { BizOverviewCards } from "../components/BizOverviewCards";
import { LevelBadge } from "../components/LevelBadge";

export function SentinelView({ alerts, firing, onSelect, onAck, biz, alertsState }: { alerts: AlertItem[]; firing: number; onSelect: (a: AlertItem) => void; onAck: (id: number) => void; biz: BizLine; alertsState: ConnState }) {
  // 首屏聚合 BFF：链路健康 + KPI + 业务线卡片（值班哨 20s 刷新）。
  const boot = useApi((signal) => api.sentinelBootstrap(biz, signal), [biz], { pollMs: 20_000 });
  const live = boot.state === "live" && !!boot.data;

  // 链路健康节点（回退静态 statusNodes）。
  const healthNodes = live
    ? boot.data!.link_health.nodes.map(n => ({
        label: n.label,
        value: n.status === "ok" ? "正常" : n.status === "warn" ? "注意" : "异常",
        sub: n.detail || "",
        state: n.status === "ok" ? "ok" : n.status === "warn" ? "warn" : "bad",
      }))
    : statusNodes;
  const abnormal = live ? boot.data!.link_health.abnormal : statusNodes.filter(n => n.state !== "ok").length;
  const evaluatedAt = live ? fmtClock(boot.data!.link_health.evaluated_at) : "14:32:00";

  // KPI 指标带：从 bootstrap.kpi 按 metric id 取值，回退静态数据。
  const kpiMap = new Map((boot.data?.kpi ?? []).map(v => [v.metric, v]));
  const kpi = (id: string) => kpiMap.get(id);
  const orderKpi = kpi("core.order_created_cnt");
  const deliveredKpi = kpi("core.order_delivered_cnt");
  const rateKpi = kpi("core.delivery_rate");
  const gtvKpi = kpi("fund.gtv_net");
  const delayKpi = kpi("fund.settle_delay_rate");

  return (
    <div className="content-stack">
      {/* health pipeline */}
      <section className="health-section">
        <div className="section-title">
          <div><h2>链路健康 <ConnectionBadge state={boot.state} /></h2><p>最近求值 {evaluatedAt}，数据窗口延迟 1 分钟</p></div>
          <span className="health-summary"><i />{healthNodes.length} 个节点中 {abnormal} 个异常</span>
        </div>
        <div className="health-line">
          {healthNodes.map((n, i) => (
            <div className={`health-node ${n.state}`} key={n.label}>
              <div className="node-top">
                <span className="node-dot">{n.state === "ok" ? <Check size={14} /> : <AlertTriangle size={14} />}</span>
                {i < healthNodes.length - 1 && <span className="connector" />}
              </div>
              <strong>{n.label}</strong><b>{n.value}</b><span>{n.sub}</span>
            </div>
          ))}
        </div>
      </section>

      {/* biz line overview cards — only in "all" mode */}
      {biz === "all" && <BizOverviewCards cards={live ? boot.data!.biz_cards : null} />}

      {/* KPI band */}
      <section className="metrics-band">
        <MetricCell label="今日总订单"
          value={orderKpi ? fmtInt(orderKpi.value) : (biz === "all" ? "557,865" : (bizOverview[biz]?.orders || "—"))}
          delta={orderKpi ? metricDisplay(orderKpi).delta : (biz === "all" ? "5.6%" : (bizOverview[biz]?.delta || "—"))} />
        <MetricCell label="今日完单"
          value={deliveredKpi ? fmtInt(deliveredKpi.value) : (biz === "all" ? "440,707" : (bizOverview[biz]?.delivered || "—"))}
          delta={deliveredKpi ? metricDisplay(deliveredKpi).delta : "3.1%"} />
        <MetricCell label="综合完单率"
          value={rateKpi ? fmtRatio(rateKpi.value, 1) : (biz === "all" ? "88.2%" : (bizOverview[biz]?.rate || "—"))}
          delta={rateKpi ? metricDisplay(rateKpi).delta : "1.2pp"} note="30 日基线"
          good={rateKpi ? rateKpi.good : (biz === "all" ? true : bizOverview[biz]?.rateGood ?? true)} />
        <MetricCell label="净 GTV"
          value={gtvKpi ? fmtGtvFen(gtvKpi.value) : (biz === "all" ? "¥ 6,550万" : (bizOverview[biz]?.gtv || "—"))}
          delta={gtvKpi ? metricDisplay(gtvKpi).delta : "4.7%"} />
        <MetricCell label="结算延迟率"
          value={delayKpi ? fmtRatio(delayKpi.value, 2) : "0.42%"}
          delta={delayKpi ? metricDisplay(delayKpi).delta : "0.18pp"}
          good={delayKpi ? delayKpi.good : false} />
      </section>

      {/* main chart + alerts */}
      <div className="dashboard-grid">
        <section className="panel chart-panel">
          <div className="panel-head">
            <div><h2>订单主链路</h2><p>{biz === "all" ? "全业务线" : bizLines.find(b => b.id === biz)?.label} · 今日 · 每 2 小时聚合</p></div>
            <div className="legend"><span><i className="purple" />下单</span><span><i className="blue" />完单</span><span><i className="green" />结算</span></div>
          </div>
          <div className="main-chart">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={volumeDataAll} margin={{ top: 12, right: 8, left: -18, bottom: 0 }}>
                <defs>
                  <linearGradient id="pub" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#6558d3" stopOpacity={0.22} /><stop offset="100%" stopColor="#6558d3" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e8e8ef" />
                <XAxis dataKey="time" axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 11 }} />
                <YAxis axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 11 }} />
                <Tooltip />
                <Area type="monotone" dataKey="orders" stroke="#6558d3" strokeWidth={2} fill="url(#pub)" />
                <Line type="monotone" dataKey="delivered" stroke="#3587e7" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="settled" stroke="#25a579" strokeWidth={2} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </section>

        <section className="panel alert-panel">
          <div className="panel-head">
            <div><h2>活动告警 <span className="title-count">{firing}</span> <ConnectionBadge state={alertsState} /></h2><p>按优先级与触发时间排序</p></div>
            <button className="text-button">全部告警<ChevronRight size={14} /></button>
          </div>
          <div className="alert-list">
            {alerts.map(a => (
              <button className="alert-item" key={a.id} onClick={() => onSelect(a)}>
                <div className="alert-main">
                  <LevelBadge value={a.level} />
                  <div>
                    <strong>{a.title}</strong>
                    <span><BusinessBadge biz={a.biz} /> {a.scope} · {a.time}</span>
                  </div>
                  <ChevronRight size={16} />
                </div>
                <div className="alert-values">
                  <b>{a.value}</b><span>{a.delta}</span>
                  {a.status === "claimed"
                    ? <em><Check size={12} /> 已认领</em>
                    : <em className="claim" onClick={e => { e.stopPropagation(); onAck(a.id); }}>认领</em>
                  }
                </div>
              </button>
            ))}
          </div>
        </section>
      </div>

      <div className="freshness-note">
        <Database size={15} />
        <span>数据截至 {boot.serverTime ? fmtDateTime(boot.serverTime) : "2026-09-03 14:31:00"} (Asia/Shanghai)</span><span>·</span>
        <span>指标口径版本 {boot.freshness?.dictVersion ? `v${boot.freshness.dictVersion}` : "v2026.09"}</span>
      </div>
    </div>
  );
}
