import { ChevronRight } from "lucide-react";
import { CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { bizLineLabels, bizLines, type BizLine } from "@/entities/business/model";
import { api, type Topology } from "@/services/monitor/client";
import { fmtDelta, fmtDeltaPp, fmtInt, fmtRatio } from "@/services/monitor/format";
import { useApi } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";
import { MetricCell } from "@/shared/ui/MetricCell";
import { BusinessBadge } from "../components/BusinessBadge";
import { apiTrend, coreApis, fmt, slowCalls, topoEdges, type TopoLevel, topoNodes } from "../data/mock-dashboard";

const topoColors: Record<TopoLevel, string> = { ok: "#25a579", warn: "#e0923f", bad: "#dc5a58" };

function Spark({ data, color, w = 72, h = 22 }: { data: number[]; color: string; w?: number; h?: number }) {
  const max = Math.max(...data), min = Math.min(...data);
  const pts = data.map((v, i) => `${(i / (data.length - 1)) * w},${h - ((v - min) / ((max - min) || 1)) * (h - 2) - 1}`).join(" ");
  return <svg width={w} height={h} className="spark"><polyline points={pts} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" /></svg>;
}

function TopoChart({ topo }: { topo?: Topology | null }) {
  // live 时用后端布局坐标与异常率；否则回退静态拓扑。
  const liveNodes = topo?.nodes?.map(n => ({ id: n.id, x: n.x, y: n.y, w: n.w, h: n.h, label: n.label, sub: n.sub }));
  const liveEdges = topo?.edges?.map(e => ({
    from: e.from, to: e.to, rate: e.error_rate * 100,
    level: (e.level === "bad" ? "bad" : e.level === "warn" ? "warn" : "ok") as TopoLevel,
  }));
  const usedNodes = liveNodes && liveNodes.length ? liveNodes : topoNodes;
  const usedEdges = liveEdges && liveEdges.length ? liveEdges : topoEdges;
  const nodes = Object.fromEntries(usedNodes.map(n => [n.id, n]));
  return (
    <svg viewBox="0 0 900 430" role="img" aria-label="上下游依赖拓扑">
      {usedEdges.map(e => {
        const f = nodes[e.from], t = nodes[e.to];
        const x1 = f.x + f.w, y1 = f.y + f.h / 2, x2 = t.x, y2 = t.y + t.h / 2;
        const dx = Math.max((x2 - x1) / 2, 44);
        const col = topoColors[e.level];
        const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
        return (
          <g key={e.from + "→" + e.to}>
            <path
              d={`M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`}
              fill="none" stroke={col} strokeWidth={e.level === "ok" ? 1.5 : 2.2}
              strokeDasharray="5 5" className="topo-flow" opacity={e.level === "ok" ? 0.55 : 0.9}
            />
            <g transform={`translate(${mx},${my})`}>
              <rect x={-44} y={-11} width={88} height={22} rx={4} fill="#fff" stroke={col} strokeOpacity={0.4} />
              <text textAnchor="middle" dominantBaseline="central" fontSize={9.5} fill={col} fontWeight={600}>
                {`异常率 ${e.rate.toFixed(2)}%`}
              </text>
            </g>
          </g>
        );
      })}
      {usedNodes.map(n => {
        const badIn = usedEdges.some(e => e.to === n.id && e.level === "bad");
        return (
          <g key={n.id} transform={`translate(${n.x},${n.y})`}>
            <rect width={n.w} height={n.h} rx={8} className={`topo-node ${badIn ? "alert" : ""}`} />
            <text x={n.w / 2} y={17} textAnchor="middle" fontSize={11} fill="#2c2c38" fontWeight={600}>{n.label}</text>
            <text x={n.w / 2} y={31} textAnchor="middle" fontSize={8.5} fill="#8a8b96">{n.sub}</text>
            {badIn && <circle cx={n.w - 8} cy={8} r={3.5} fill="#dc5a58" className="topo-blink" />}
          </g>
        );
      })}
    </svg>
  );
}

export function TechView({ biz }: { biz: BizLine }) {
  // 接口监控：KPI / 接口清单 / 拓扑 / 慢调用（topology 15s 轮询）。
  const kpiApi = useApi((s) => api.techSummary(biz, s), [biz], { pollMs: 60_000 });
  const apisApi = useApi((s) => api.techApis(biz, "fail_rate_desc", s), [biz], { pollMs: 15_000 });
  const topoApi = useApi((s) => api.topology(s), [], { pollMs: 15_000 });
  const slowApi = useApi((s) => api.slowCalls(s), [], { pollMs: 30_000 });

  const kByMetric = new Map((kpiApi.data ?? []).map(k => [k.metric, k]));
  const kd = (id: string) => {
    const k = kByMetric.get(id);
    if (!k) return null;
    let value: string;
    if (k.unit === "ratio") value = fmtRatio(k.value, 2);
    else if (k.unit === "ms") value = `${fmtInt(k.value)}ms`;
    else value = `${fmtInt(k.value)}`;
    const delta = k.delta_pp !== undefined ? fmtDeltaPp(k.delta_pp) : (k.delta !== undefined ? (Math.abs(k.delta) < 1 ? fmtDelta(k.delta) : `${fmtInt(k.delta)} 个`) : "—");
    return { value, delta, good: k.good };
  };
  const monitored = kd("api.monitored_cnt");
  const abnormal = kd("api.abnormal_cnt");
  const avgFail = kd("api.avg_fail_rate");
  const ingress = kd("api.ingress_qps");
  const linkP99 = kd("api.link_p99_ms");

  // 接口清单：live 时用后端 ApiView，否则回退静态 coreApis。
  type Row = { name: string; service: string; biz: BizLine; qps: number; failRate: number; p99: number; status: string; trend: number[] };
  const apis: Row[] = apisApi.data
    ? apisApi.data.map(a => ({
        name: a.path, service: `${a.service} · ${a.service_label}`, biz: a.biz_line,
        qps: a.qps, failRate: a.fail_rate * 100, p99: a.p99_ms, status: a.status, trend: a.trend,
      }))
    : (biz === "all" ? coreApis : coreApis.filter(a => a.biz === biz || a.biz === "all")).map(a => ({ ...a }));

  const badCount = abnormal ? abnormal.value : `${coreApis.filter(a => a.status === "bad").length} 个`;

  return (
    <div className="content-stack">
      <section className="metrics-band">
        <MetricCell label="监控核心接口" value={monitored ? `${monitored.value} 个` : "48 个"} delta={monitored ? monitored.delta : "2 个"} note="较上周" />
        <MetricCell label="当前异常接口" value={abnormal ? `${abnormal.value} 个` : badCount} delta={abnormal ? abnormal.delta : "2 个"} good={false} />
        <MetricCell label="平均失败率" value={avgFail ? avgFail.value : "0.34%"} delta={avgFail ? avgFail.delta : "0.11pp"} good={false} />
        <MetricCell label="调用总量（上游入口）" value={ingress ? `${ingress.value} QPS` : "8,420 QPS"} delta={ingress ? ingress.delta : "6.4%"} good={ingress ? ingress.good : true} />
        <MetricCell label="链路 P99 延迟" value={linkP99 ? linkP99.value : "640ms"} delta={linkP99 ? linkP99.delta : "120ms"} good={false} />
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>上下游依赖异常率 <ConnectionBadge state={topoApi.state} /></h2>
            <p>调用链实时标注 · 虚线流动方向即调用方向</p>
          </div>
          <div className="topo-legend">
            <span><i style={{ color: "#25a579" }} />正常 &lt;0.1%</span>
            <span><i style={{ color: "#e0923f" }} />波动 0.1–2%</span>
            <span><i style={{ color: "#dc5a58" }} />异常 &gt;2%</span>
          </div>
        </div>
        <div className="tech-topo"><TopoChart topo={topoApi.data} /></div>
      </section>

      <div className="business-grid">
        <section className="panel">
          <div className="panel-head">
            <div>
              <h2>核心接口失败率 <ConnectionBadge state={apisApi.state} />{biz !== "all" && <span className="panel-filter-note">{bizLines.find(b => b.id === biz)?.label}线 + 平台级</span>}</h2>
              <p>按失败率倒序 · 1 分钟粒度实时聚合</p>
            </div>
            <button className="text-button">全部接口<ChevronRight size={14} /></button>
          </div>
          <div className="api-table">
            <div className="api-row head">
              <span>接口 / 服务</span><span>业务线</span><span className="ta-r">QPS</span>
              <span className="ta-r">失败率</span><span className="ta-r">P99</span><span className="ta-r">30min 趋势</span><span>状态</span>
            </div>
            {apis.map(a => (
              <div className="api-row" key={a.name}>
                <div className="api-name"><strong>{a.name}</strong><span>{a.service}</span></div>
                <div className="c-biz"><BusinessBadge biz={a.biz} /></div>
                <span className="c-qps">{fmt(a.qps)}</span>
                <span className={`fail-num ${a.failRate >= 2 ? "bad" : a.failRate >= 0.5 ? "warn" : ""}`}>{a.failRate.toFixed(2)}%</span>
                <span className="c-p99">{a.p99}ms</span>
                <div className="c-trend"><Spark data={a.trend} color={a.status === "bad" ? "#dc5a58" : a.status === "warn" ? "#e0923f" : "#25a579"} /></div>
                <span className={`status-chip ${a.status}`}>{a.status === "bad" ? "异常" : a.status === "warn" ? "波动" : "正常"}</span>
              </div>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-head">
            <div><h2>失败率趋势</h2><p>核心下游接口 · 近 6 小时</p></div>
            <div className="legend">
              <span><i style={{ background: "#dc5a58" }} />支付</span>
              <span><i style={{ background: "#e0923f" }} />派单</span>
              <span><i style={{ background: "#6558d3" }} />下单</span>
            </div>
          </div>
          <div style={{ height: 190 }}>
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={apiTrend} margin={{ top: 8, right: 6, left: -14, bottom: 0 }}>
                <CartesianGrid vertical={false} stroke="#ececf1" />
                <XAxis dataKey="t" axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 10 }} />
                <YAxis axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 10 }} tickFormatter={(v: number) => `${v}%`} />
                <Tooltip formatter={((v: number | undefined) => `${v ?? 0}%`) as never} />
                <Line type="monotone" dataKey="pay" name="支付回调" stroke="#dc5a58" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="dispatch" name="派单响应" stroke="#e0923f" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="order" name="创建订单" stroke="#6558d3" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="transfer" name="转单提交" stroke="#25a579" strokeWidth={1.5} dot={false} strokeDasharray="4 3" />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          <h3 className="minor-head">慢调用 TOP <ConnectionBadge state={slowApi.state} /></h3>
          <div className="slow-list">
            {(slowApi.data && slowApi.data.length
              ? slowApi.data.map(s => [`${s.path} ${s.label}`, `${fmtInt(s.p99_ms)}ms`, bizLineLabels[s.biz_line] || s.biz_line] as [string, string, string])
              : slowCalls
            ).map(s => (
              <div key={s[0]}><code>{s[0]}</code><b>{s[1]}</b><span>{s[2]}</span></div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}
