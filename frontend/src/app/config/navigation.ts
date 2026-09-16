import {
  FileCode2,
  Gauge,
  Headphones,
  LayoutDashboard,
  Library,
  Network,
  ShieldCheck,
  Siren,
  Workflow,
  type LucideIcon,
} from "lucide-react";

export type MonitorRouteId =
  | "sentinel"
  | "business"
  | "quality"
  | "service"
  | "risk"
  | "tech"
  | "library"
  | "system"
  | "docs";

export interface NavigationItem {
  id: MonitorRouteId;
  label: string;
  icon: LucideIcon;
  no: string;
}

export const navItems: NavigationItem[] = [
  { id: "sentinel", label: "值班哨", icon: Siren, no: "01" },
  { id: "business", label: "经营大盘", icon: LayoutDashboard, no: "02" },
  { id: "quality", label: "履约质量", icon: Gauge, no: "03" },
  { id: "service", label: "客服工作台", icon: Headphones, no: "04" },
  { id: "risk", label: "风控观测", icon: ShieldCheck, no: "05" },
  { id: "tech", label: "接口监控", icon: Workflow, no: "06" },
  { id: "library", label: "指标库", icon: Library, no: "07" },
  { id: "system", label: "元监控", icon: Network, no: "08" },
  { id: "docs", label: "接口文档", icon: FileCode2, no: "API" },
];

export function getRouteTitle(active: MonitorRouteId): string {
  return navItems.find((item) => item.id === active)?.label ?? "值班哨";
}

export function getRouteSubtitle(active: MonitorRouteId): string {
  return {
    sentinel: "实时链路健康，全业务线订单主干一目了然。",
    business: "从下单到结算，各业务线经营健康与城市归因。",
    quality: "聚焦取消、等待、客诉与申诉的履约体验。",
    service: "按订单号还原完整事件时间线，快速处理纠纷。",
    risk: "观察规则命中趋势与异常特征，不影响业务链路。",
    tech: "核心接口失败率、上下游依赖异常率与调用链延迟健康。",
    library: "指标定义的统一真源，业务系统按事件契约上报即可产出指标。",
    system: "监控监控系统本身，避免数据断流被误读为业务正常。",
    docs: "面向前端、服务端与业务系统接入方的接口契约。",
  }[active];
}
