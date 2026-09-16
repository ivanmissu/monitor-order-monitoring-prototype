import { Cell, Bar, BarChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { bizLines, type BizLine } from "@/entities/business/model";
import { api } from "@/services/monitor/client";
import { fmtRatio, metricDisplay } from "@/services/monitor/format";
import { useApi } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";
import { MetricCell } from "@/shared/ui/MetricCell";

export function QualityView({ biz }: { biz: BizLine }) {
  // 履约质量：并行 KPI / 取消矩阵 / 风险城市（60s 刷新）。
  const sumApi = useApi((s) => api.qualitySummary(biz, s), [biz], { pollMs: 60_000 });
  const cancelApi = useApi((s) => api.cancelMatrix(biz, "by_fault", s), [biz], { pollMs: 60_000 });
  const riskCityApi = useApi((s) => api.riskCities(s), [], { pollMs: 60_000 });

  const kpiByMetric = new Map((sumApi.data ?? []).map(it => [it.value.metric, it.value]));
  const kpiOf = (id: string) => kpiByMetric.get(id);
  const atFault = kpiOf("core.cancel_rate_atfault");
  const noShow = kpiOf("core.no_show_rate");
  const waitOver = kpiOf("core.wait_over8_rate");
  const complaint = kpiOf("exp.complaint_rate");

  const cancelData = cancelApi.data
    ? cancelApi.data.marginal.map(m => ({ name: m.label, value: m.value, color: m.color }))
    : [
        { name: "司机/服务方有责", value: biz === "all" ? 3824 : 824, color: "#e45e5e" },
        { name: "司机/服务方无责", value: biz === "all" ? 5175 : 1175, color: "#edae49" },
        { name: "用户取消", value: biz === "all" ? 9398 : 2398, color: "#6558d3" },
        { name: "系统取消", value: biz === "all" ? 1286 : 286, color: "#9b9ca8" },
      ];

  const riskCities: [string, string, string, string][] = riskCityApi.data && riskCityApi.data.length
    ? riskCityApi.data.map(c => [c.name, c.main_label, fmtRatio(c.main_value, c.main_value < 0.01 ? 2 : 1), c.level === "high" ? "高" : c.level === "mid" ? "中" : "低"])
    : [["成都", "等待超率", "17.3%", "高"], ["广州", "申诉推翻率", "32.8%", "高"], ["武汉", "客诉率", "0.53%", "中"], ["杭州", "差评率", "1.86%", "中"]];

  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <MetricCell label="服务方有责取消率" value={atFault ? fmtRatio(atFault.value, 2) : (biz === "all" ? "1.12%" : "1.34%")} delta={atFault ? metricDisplay(atFault).delta : "0.3pp"} good={atFault ? atFault.good : false} />
        <MetricCell label="爽约率" value={noShow ? fmtRatio(noShow.value, 2) : "0.62%"} delta={noShow ? metricDisplay(noShow).delta : "0.08pp"} good={noShow ? noShow.good : true} />
        <MetricCell label="等待超时率" value={waitOver ? fmtRatio(waitOver.value, 1) : "12.8%"} delta={waitOver ? metricDisplay(waitOver).delta : "1.6pp"} good={waitOver ? waitOver.good : false} />
        <MetricCell label="客诉率（7日）" value={complaint ? fmtRatio(complaint.value, 2) : "0.38%"} delta={complaint ? metricDisplay(complaint).delta : "0.05pp"} good={complaint ? complaint.good : true} />
      </section>
      <div className="business-grid">
        <section className="panel">
          <div className="panel-head"><div><h2>取消归因 <ConnectionBadge state={cancelApi.state} /></h2><p>{biz === "all" ? "全业务线" : bizLines.find(b => b.id === biz)?.label} · 按 责任方 × 阶段 × 归责</p></div></div>
          <div className="bar-chart">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={cancelData} layout="vertical" margin={{ left: 20, right: 28 }}>
                <XAxis type="number" hide /><YAxis dataKey="name" type="category" axisLine={false} tickLine={false} width={100} />
                <Tooltip /><Bar dataKey="value" radius={[0, 4, 4, 0]} barSize={18}>
                  {cancelData.map(e => <Cell key={e.name} fill={e.color} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>
        <section className="panel">
          <div className="panel-head"><div><h2>体验风险城市 <ConnectionBadge state={riskCityApi.state} /></h2><p>综合等待、客诉、差评与申诉推翻</p></div></div>
          <div className="risk-city-list">
            {riskCities.map(x => (
              <div key={x[0]}><strong>{x[0]}</strong><span>{x[1]}</span><b>{x[2]}</b><em className={x[3] === "高" ? "high" : ""}>{x[3]}</em></div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}
