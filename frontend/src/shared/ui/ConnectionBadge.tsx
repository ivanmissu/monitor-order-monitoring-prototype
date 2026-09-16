import type { ConnState } from "@/services/monitor/use-api";

/** 后端连通状态徽标：live = 已对接实时接口；demo = 服务端不可达，回退静态演示数据。 */
export function ConnectionBadge({ state }: { state: ConnState }) {
  if (state === "live") return <span className="conn-badge live"><i />实时接口</span>;
  if (state === "loading" || state === "idle") return <span className="conn-badge loading"><i />加载中</span>;
  return <span className="conn-badge demo"><i />演示数据（服务端未连接）</span>;
}
