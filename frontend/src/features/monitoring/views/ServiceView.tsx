import { AlertTriangle, Check, Search, ShieldCheck } from "lucide-react";
import { api, ApiError } from "@/services/monitor/client";
import { fmtClock, fmtDuration, fmtYuan } from "@/services/monitor/format";
import { useApi } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";
import { orderEvents } from "../data/mock-dashboard";

const BIZ_LABEL: Record<string, string> = {
  driver: "司机端", transfer: "转单端", carpool: "顺风车", designated: "代驾", airport: "接送机", all: "全平台",
};
const SEAT_LABEL: Record<string, string> = {
  exclusive: "独享", two_seat: "2 座", three_seat: "3 座", all: "全部座型",
};

export function ServiceView({ orderId, setOrderId, searchedOrder, search }: { orderId: string; setOrderId: (v: string) => void; searchedOrder: string; search: () => void }) {
  // 客服工作台：手动查询触发（禁止轮询）。订单快照 + 事件时间线。
  const orderApi = useApi((s) => api.order(searchedOrder, s), [searchedOrder]);
  const eventsApi = useApi((s) => api.orderEvents(searchedOrder, s), [searchedOrder]);
  const o = orderApi.data;
  const tl = eventsApi.data;

  const notFound = orderApi.state === "error" && (orderApi.error as ApiError)?.code === 40401;

  const summaryRows: [string, string][] = o
    ? [
        ["业务线", BIZ_LABEL[o.biz_line] || o.biz_line],
        ["城市", o.city_name],
        ["座型", SEAT_LABEL[o.seat_type] || o.seat_type],
        ["订单金额", fmtYuan(o.amount_fen)],
        ["司机标识", o.driver_id_hash_masked],
        ["业务归属日", o.dt],
      ]
    : [["业务线", "顺风车"], ["城市", "杭州市"], ["座型", "独享"], ["订单金额", "¥86.50"], ["司机标识", "8a7f...21de"], ["业务归属日", "2026-09-03"]];

  const events = tl
    ? tl.events.map(e => ({ time: fmtClock(e.event_time), name: e.label, event: e.event_type, note: e.note || "" }))
    : orderEvents;

  return (
    <div className="content-stack">
      <section className="order-search">
        <Search size={20} />
        <input value={orderId} onChange={e => setOrderId(e.target.value)} onKeyDown={e => e.key === "Enter" && search()} placeholder="输入订单号查询（支持全业务线，最长 90 天）" />
        <button onClick={search}>查询订单</button>
      </section>
      {notFound && (
        <div className="freshness-note" style={{ color: "#d25555" }}>
          <AlertTriangle size={15} /><span>未找到订单 {searchedOrder}，请核对订单号（演示订单：CP20260903018462）。</span>
        </div>
      )}
      <section className="order-layout">
        <div className="order-summary">
          <div className="summary-head"><span>订单快照 </span><b>{o ? o.status_label : "已完成"}</b></div>
          <h2>{searchedOrder} <ConnectionBadge state={orderApi.state} /></h2>
          <dl>
            {summaryRows.map(x => (
              <div key={x[0]}><dt>{x[0]}</dt><dd>{x[1]}</dd></div>
            ))}
            <div><dt>事件完整性</dt><dd className="complete"><Check size={13} /> {o ? (o.completeness.state === "complete" ? "完整" : `缺 ${o.completeness.missing.length} 个节点`) : "完整"}</dd></div>
          </dl>
          <p><ShieldCheck size={14} />{o ? o.privacy_note : "仅展示脱敏后的维度快照"}</p>
        </div>
        <div className="timeline-panel">
          <div className="panel-head">
            <div><h2>订单事件时间线 <ConnectionBadge state={eventsApi.state} /></h2><p>共 {events.length} 个领域事件{tl ? ` · 链路耗时 ${fmtDuration(tl.duration_sec)} · 查询 ${tl.query_cost_ms}ms` : " · 链路耗时 2h 06m"}</p></div>
            <button className="text-button">查看原始 JSON</button>
          </div>
          <div className="timeline">
            {events.map((e, i) => (
              <div className="timeline-item" key={`${e.time}-${i}`}>
                <span className="timeline-time">{e.time}</span>
                <i>{i === events.length - 1 ? <Check size={12} /> : null}</i>
                <div><strong>{e.name}</strong><code>{e.event}</code><p>{e.note}</p></div>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}
