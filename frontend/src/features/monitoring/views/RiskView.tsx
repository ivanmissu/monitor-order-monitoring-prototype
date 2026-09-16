import { Bar, CartesianGrid, ComposedChart, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { bizLines, type BizLine } from "@/entities/business/model";
import { api } from "@/services/monitor/client";
import { fmtGtvFen, fmtInt, fmtRatio, metricDisplay } from "@/services/monitor/format";
import { useApi } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";
import { MetricCell } from "@/shared/ui/MetricCell";
import { riskData } from "../data/mock-dashboard";

export function RiskView({ biz }: { biz: BizLine }) {
  // 风控观测 KPI（60s 刷新）。命中/拦截/冻结/申诉推翻。
  const sumApi = useApi((s) => api.riskSummary(biz, s), [biz], { pollMs: 60_000 });
  const byMetric = new Map((sumApi.data ?? []).map(v => [v.metric, v]));
  const hit = byMetric.get("risk.risk_hit_cnt");
  const frozen = byMetric.get("risk.frozen_amount_sum");
  const appeal = byMetric.get("exp.appeal_overturn_rate");
  const intercept = byMetric.get("risk.intercepted_order_cnt");

  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <MetricCell label="今日规则命中" value={hit ? fmtInt(hit.value) : (biz === "all" ? "1,493" : "493")} delta={hit ? metricDisplay(hit).delta : "8.6%"} good={hit ? hit.good : false} />
        <MetricCell label="拦截订单" value={intercept ? fmtInt(intercept.value) : (biz === "all" ? "428" : "128")} delta={intercept ? metricDisplay(intercept).delta : "3.4%"} good={intercept ? intercept.good : false} />
        <MetricCell label="冻结金额" value={frozen ? fmtGtvFen(frozen.value) : (biz === "all" ? "¥ 62.8万" : "¥ 18.6万")} delta={frozen ? metricDisplay(frozen).delta : "5.1%"} good={frozen ? frozen.good : false} />
        <MetricCell label="申诉推翻率" value={appeal ? fmtRatio(appeal.value, 1) : "18.4%"} delta={appeal ? metricDisplay(appeal).delta : "2.2pp"} good={appeal ? appeal.good : true} />
      </section>
      <div className="business-grid">
        <section className="panel">
          <div className="panel-head"><div><h2>规则命中趋势 <ConnectionBadge state={sumApi.state} /></h2><p>{biz === "all" ? "全业务线" : bizLines.find(b => b.id === biz)?.label} · 最近 7 天</p></div></div>
          <div className="bar-chart">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={riskData} margin={{ top: 10, right: 10, left: -20 }}>
                <CartesianGrid vertical={false} stroke="#ececf1" />
                <XAxis dataKey="time" axisLine={false} tickLine={false} />
                <YAxis axisLine={false} tickLine={false} />
                <Tooltip />
                <Bar dataKey="hit" fill="#6558d3" radius={[4, 4, 0, 0]} barSize={24} />
                <Line dataKey="frozen" stroke="#e45e5e" strokeWidth={2} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </section>
        <section className="panel">
          <div className="panel-head"><div><h2>高频异常用户</h2><p>ODS 24 小时滑动窗口</p></div></div>
          <div className="city-table">
            <div className="city-row head"><span>用户标识</span><span>短单</span><span>命中</span><span>风险</span></div>
            {[["91ad...0fe2", "18", "12", "高"], ["4bc2...821a", "15", "9", "高"], ["72ef...d191", "12", "7", "中"], ["0a84...31dd", "11", "6", "中"]].map(r => (
              <div className="city-row" key={r[0]}><strong className="mono">{r[0]}</strong><span>{r[1]}</span><span>{r[2]}</span><em className={r[3] === "高" ? "negative" : ""}>{r[3]}</em></div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}
