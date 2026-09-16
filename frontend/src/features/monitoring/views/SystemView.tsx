import { api } from "@/services/monitor/client";
import { fmtClock, fmtRatio } from "@/services/monitor/format";
import { useApi } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";
import { MetricCell } from "@/shared/ui/MetricCell";

export function SystemView() {
  // 元监控：pipeline + lag 30s 轮询；alerts/stats 供告警有效率卡。
  const pipeApi = useApi((s) => api.pipeline(s), [], { pollMs: 30_000 });
  const lagApi = useApi((s) => api.lag(s), [], { pollMs: 30_000 });
  const statsApi = useApi((s) => api.alertStats(s), [], { pollMs: 60_000 });
  const reconcileApi = useApi((s) => api.reconcile(s), []);

  const lag = lagApi.data;
  const stats = statsApi.data;
  const reconcile = reconcileApi.data as { state_gap_cnt?: number } | null;

  const STATUS_LABEL: Record<string, string> = { ok: "正常", warn: "注意", bad: "异常" };
  const components: [string, string, string, string, string, string][] = pipeApi.data && pipeApi.data.length
    ? pipeApi.data.map(c => [c.component, c.status, STATUS_LABEL[c.status] || c.status, c.throughput, fmtClock(c.heartbeat_at), c.detail || c.extra || ""])
    : [
        ["order-domain-topic (5 线)", "ok", "正常", "8,420 msg/s", "14:32:08", "lag 2,210"],
        ["monitor-consumer ×4", "ok", "正常", "8,406 msg/s", "14:32:08", "本地缓冲 0"],
        ["ClickHouse replica-01", "ok", "正常", "写入 42ms", "14:32:07", "8C / 32G"],
        ["ClickHouse replica-02", "ok", "正常", "复制延迟 0.3s", "14:32:07", "8C / 32G"],
        ["RuleEvaluator", "ok", "正常", "48 条 / min", "14:32:00", "求值 328ms"],
        ["T+1 reconciler", "warn", "注意", "缺口 23 笔", "06:18:42", "等待重算"],
      ];

  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <MetricCell label="消费延迟 P99" value={lag ? `${lag.consumer_lag.p99_sec}s` : "1.2s"} delta="0.4s" />
        <MetricCell label="迟到事件率" value={lag ? fmtRatio(lag.late.rate, 3) : "0.018%"} delta="0.003pp" />
        <MetricCell label="状态缺口" value={reconcile?.state_gap_cnt != null ? String(reconcile.state_gap_cnt) : "23"} delta="7 笔" good={false} />
        <MetricCell label="告警有效率" value={stats ? fmtRatio(stats.precision, 1) : "84.6%"} delta="6.2pp" note="较上周" good={stats ? stats.precision >= stats.target : true} />
      </section>
      <section className="panel system-table">
        <div className="panel-head"><div><h2>数据链路组件 <ConnectionBadge state={pipeApi.state} /></h2><p>全业务线共享旁路链路</p></div></div>
        <div className="component-row head"><span>组件</span><span>状态</span><span>吞吐 / 延迟</span><span>最近心跳</span><span>备注</span></div>
        {components.map((r) => (
          <div className="component-row" key={r[0]}>
            <strong>{r[0]}</strong>
            <span className={r[1] === "ok" ? "status-ok" : "status-warn"}><i />{r[2]}</span>
            <span>{r[3]}</span><span>{r[4]}</span><span>{r[5]}</span>
          </div>
        ))}
      </section>
    </div>
  );
}
