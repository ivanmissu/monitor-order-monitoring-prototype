import { ChevronRight } from "lucide-react";
import { motion } from "motion/react";
import { Cell, Pie, PieChart, ResponsiveContainer, Tooltip } from "recharts";
import { bizLines, type BizLine } from "@/entities/business/model";
import { api, type CityRow } from "@/services/monitor/client";
import { fmtDelta, fmtInt, fmtRatio } from "@/services/monitor/format";
import { useApi } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";
import { MetricCell } from "@/shared/ui/MetricCell";
import { bizOverview, cityPerf, fmt, funnels } from "../data/mock-dashboard";

export function BusinessView({ biz }: { biz: BizLine }) {
  const activeBiz = biz === "all" ? "carpool" : biz;
  const d = bizOverview[activeBiz] || bizOverview.carpool;

  // 经营大盘：并行拉取漏斗 / 构成 / 城市排行（60s 刷新）。
  const funnelApi = useApi((s) => api.funnel(biz, undefined, s), [biz], { pollMs: 60_000 });
  const compApi = useApi((s) => api.composition("core.order_created_cnt", s), [], { pollMs: 60_000 });
  const cityApi = useApi((s) => api.cityRank(biz, "delivered_desc", 6, s), [biz], { pollMs: 60_000 });

  // 漏斗数据：live 时用后端 steps，否则回退静态 funnels。
  const currentFunnel = funnelApi.data
    ? funnelApi.data.steps.map(s => ({ label: s.label, value: s.value, rate: s.rate != null ? +(s.rate * 100).toFixed(1) : 100 }))
    : (funnels[activeBiz] || funnels.carpool);
  const funnelVersion = funnelApi.data?.definition_version || "v2";

  // 业务线构成饼图：live 时用后端 composition items。
  const compData = compApi.data
    ? compApi.data.items.filter(i => i.biz_line !== "all").map(i => ({ name: i.label, value: i.value, color: i.color }))
    : bizLines.filter(b => b.id !== "all").map(b => ({ name: b.label, value: parseInt(bizOverview[b.id].orders.replace(/,/g, "")), color: b.color }));

  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <MetricCell label="周完成订单" value={biz === "all" ? "2,486,420" : "356,842"} delta="6.8%" />
        <MetricCell label="综合完单率" value={biz === "all" ? "88.2%" : d.rate} delta="2.4pp" good={d.rateGood} />
        <MetricCell label="客单价" value={biz === "all" ? "¥117.4" : "¥86.5"} delta="3.2%" />
        <MetricCell label="净 GTV" value={biz === "all" ? "¥ 6,550万" : d.gtv} delta="4.7%" />
      </section>

      <div className="business-grid">
        <section className="panel">
          <div className="panel-head">
            <div><h2>订单履约漏斗 <ConnectionBadge state={funnelApi.state} /></h2><p>{biz === "all" ? "全平台" : bizLines.find(b => b.id === biz)?.label} · 今日事件口径</p></div>
            <span className="version">口径 {funnelVersion}</span>
          </div>
          <div className="funnel-list">
            {currentFunnel.map((x, i) => (
              <motion.div className="funnel-row" key={x.label} initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: i * 0.05 }}>
                <span>{x.label}</span>
                <div className="funnel-track"><i style={{ width: `${Math.max(30, x.value / currentFunnel[0].value * 100)}%` }} /></div>
                <b>{fmt(x.value)}</b><em>{i ? `${x.rate}%` : "基数"}</em>
              </motion.div>
            ))}
          </div>
        </section>

        {biz === "all" ? (
          <section className="panel">
            <div className="panel-head"><div><h2>业务线构成 <ConnectionBadge state={compApi.state} /></h2><p>今日订单量占比</p></div></div>
            <div className="composition-chart">
              <ResponsiveContainer width="100%" height={200}>
                <PieChart><Pie data={compData} cx="50%" cy="50%" innerRadius={55} outerRadius={80} dataKey="value" paddingAngle={2}>
                  {compData.map(e => <Cell key={e.name} fill={e.color} />)}
                </Pie><Tooltip /></PieChart>
              </ResponsiveContainer>
              <div className="comp-legend">
                {compData.map(c => <span key={c.name}><i style={{ background: c.color }} />{c.name}</span>)}
              </div>
            </div>
          </section>
        ) : (
          <section className="panel">
            <div className="panel-head"><div><h2>城市经营表现 <ConnectionBadge state={cityApi.state} /></h2><p>按今日完单量排序</p></div><button className="text-button">查看全部<ChevronRight size={14} /></button></div>
            <CityRows rows={cityApi.data} />
          </section>
        )}
      </div>

      <section className="panel heatmap">
        <div className="panel-head"><div><h2>城市 × 时段供需热力</h2><p>颜色越深表示完单率越高</p></div></div>
        <div className="heat-grid">
          {["杭州", "广州", "成都", "上海", "南京"].map((name, row) => (
            <div className="heat-row" key={name}><span>{name}</span>
              {Array.from({ length: 12 }).map((_, col) => <i key={col} style={{ opacity: 0.16 + ((row * 3 + col * 5) % 8) / 10 }} title={`${name} ${col * 2}:00`} />)}
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}

function CityRows({ rows }: { rows?: CityRow[] | null }) {
  const data: [string, string, string, string][] = rows && rows.length
    ? rows.map(r => [r.name, fmtInt(r.delivered), fmtRatio(r.delivery_rate, 1), fmtDelta(r.yoy)])
    : cityPerf as [string, string, string, string][];
  return (
    <div className="city-table">
      <div className="city-row head"><span>城市</span><span>完单量</span><span>完单率</span><span>同比</span></div>
      {data.map(x => (
        <div className="city-row" key={x[0]}><strong>{x[0]}</strong><span>{x[1]}</span><span>{x[2]}</span><em className={x[3].startsWith("-") ? "negative" : ""}>{x[3]}</em></div>
      ))}
    </div>
  );
}
