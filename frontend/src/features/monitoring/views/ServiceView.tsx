import { useEffect, useState } from "react";
import { AlertTriangle, Check, Database, LoaderCircle, Search, ShieldCheck } from "lucide-react";
import { api, ApiError } from "@/services/monitor/client";
import { fmtClock, fmtDuration, fmtYuan } from "@/services/monitor/format";
import { useApi } from "@/services/monitor/use-api";
import { ConnectionBadge } from "@/shared/ui/ConnectionBadge";

const BIZ_LABEL: Record<string, string> = {
  driver: "司机端", transfer: "转单端", carpool: "顺风车", designated: "代驾", airport: "接送机", all: "全平台",
};
const SEAT_LABEL: Record<string, string> = {
  exclusive: "独享", shared_2: "2 座", shared_4: "4 座", two_seat: "2 座", three_seat: "3 座", all: "全部座型",
  express: "快车", express_pool: "拼车", pickup: "接机", dropoff: "送机", designated: "代驾",
};

export function ServiceView() {
  const [orderId, setOrderId] = useState("");
  const [searchedOrder, setSearchedOrder] = useState("");

  // 页面进入时从 ClickHouse 获取最近入库订单，首项作为默认查询；不再写死演示订单号。
  const recentApi = useApi((signal) => api.recentOrders(8, signal), []);
  useEffect(() => {
    const latest = recentApi.data?.[0]?.order_id;
    if (latest && !searchedOrder) {
      setOrderId(latest);
      setSearchedOrder(latest);
    }
  }, [recentApi.data, searchedOrder]);

  // 客服工作台只在订单被选中/查询时调用聚合接口，禁止轮询，不回退静态演示数据。
  const workbenchApi = useApi(
    (signal) => api.orderWorkbench(searchedOrder, signal),
    [searchedOrder],
    { enabled: Boolean(searchedOrder) },
  );
  const current = workbenchApi.data?.order.order_id.toLowerCase() === searchedOrder.toLowerCase()
    ? workbenchApi.data
    : null;
  const order = current?.order;
  const timeline = current?.timeline;
  const error = workbenchApi.error as ApiError | null;
  const notFound = workbenchApi.state === "error" && error?.code === 40401;

  const selectOrder = (value: string) => {
    setOrderId(value);
    setSearchedOrder(value);
  };
  const search = () => {
    const value = orderId.trim();
    if (value) setSearchedOrder(value);
  };

  const summaryRows: [string, string][] = order ? [
    ["业务线", BIZ_LABEL[order.biz_line] || order.biz_line],
    ["城市", order.city_name || `城市 ${order.city_id}`],
    ["座型", SEAT_LABEL[order.seat_type] || order.seat_type],
    ["订单金额", fmtYuan(order.amount_fen)],
    ["司机标识", order.driver_id_hash_masked || "—"],
    ["业务归属日", order.dt],
  ] : [];

  const openRawEvents = () => {
    window.open(api.orderEventsExportUrl(searchedOrder), "_blank", "noopener,noreferrer");
  };

  return (
    <div className="content-stack">
      <section className="order-search">
        <Search size={20} />
        <input
          value={orderId}
          onChange={(event) => setOrderId(event.target.value)}
          onKeyDown={(event) => event.key === "Enter" && search()}
          placeholder="输入订单号查询（支持全业务线，最长 90 天）"
        />
        <button onClick={search} disabled={!orderId.trim() || workbenchApi.state === "loading"}>查询订单</button>
      </section>

      <div className="freshness-note" style={{ alignItems: "center", flexWrap: "wrap", gap: 8 }}>
        <Database size={15} />
        <span>ClickHouse 最近入库订单：</span>
        {recentApi.state === "loading" && <span>加载中…</span>}
        {recentApi.data?.map((item) => (
          <button
            key={item.order_id}
            className="text-button"
            onClick={() => selectOrder(item.order_id)}
            title={`${BIZ_LABEL[item.biz_line] || item.biz_line} · ${item.city_name} · ${item.event_count} 个事件 · ${fmtClock(item.updated_at)}`}
            style={{ fontFamily: "var(--mono)", opacity: item.order_id === searchedOrder ? 1 : 0.72 }}
          >
            {item.order_id}
          </button>
        ))}
        {recentApi.state === "error" && <span style={{ color: "#d25555" }}>近期订单接口不可用</span>}
      </div>

      {workbenchApi.state === "loading" && !current && (
        <div className="freshness-note"><LoaderCircle size={15} className="spin" /><span>正在从实时接口查询订单…</span></div>
      )}
      {workbenchApi.state === "error" && (
        <div className="freshness-note" style={{ color: "#d25555" }}>
          <AlertTriangle size={15} />
          <span>{notFound ? `未找到订单 ${searchedOrder}，请核对订单号。` : `实时接口查询失败：${error?.message || "未知错误"}`}</span>
        </div>
      )}

      {order && timeline && (
        <section className="order-layout">
          <div className="order-summary">
            <div className="summary-head"><span>订单快照 </span><b>{order.status_label}</b></div>
            <h2>{order.order_id} <ConnectionBadge state={workbenchApi.state} /></h2>
            <dl>
              {summaryRows.map(([label, value]) => (
                <div key={label}><dt>{label}</dt><dd>{value}</dd></div>
              ))}
              <div>
                <dt>事件完整性</dt>
                <dd className={order.completeness.state === "complete" ? "complete" : undefined}>
                  {order.completeness.state === "complete" ? <Check size={13} /> : <AlertTriangle size={13} />}
                  {order.completeness.state === "complete" ? "完整" : `${order.event_count} 个事件，链路仍在进行`}
                </dd>
              </div>
            </dl>
            <p><ShieldCheck size={14} />{order.privacy_note}</p>
          </div>

          <div className="timeline-panel">
            <div className="panel-head">
              <div>
                <h2>订单事件时间线 <ConnectionBadge state={workbenchApi.state} /></h2>
                <p>共 {timeline.events.length} 个领域事件 · 链路耗时 {fmtDuration(timeline.duration_sec)} · 查询 {timeline.query_cost_ms}ms</p>
              </div>
              <button className="text-button" onClick={openRawEvents}>查看原始 JSON</button>
            </div>
            <div className="timeline">
              {timeline.events.map((event, index) => (
                <div className="timeline-item" key={`${event.seq}-${event.event_time}`}>
                  <span className="timeline-time">{fmtClock(event.event_time)}</span>
                  <i>{index === timeline.events.length - 1 ? <Check size={12} /> : null}</i>
                  <div>
                    <strong>{event.label}</strong>
                    <code>{event.event_type}</code>
                    {event.note && <p>{event.note}</p>}
                  </div>
                </div>
              ))}
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
