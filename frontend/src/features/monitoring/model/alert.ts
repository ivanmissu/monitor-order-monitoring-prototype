import type { BizLine } from "@/entities/business/model";

export interface AlertItem {
  id: number;
  level: "P0" | "P1" | "P2";
  title: string;
  scope: string;
  time: string;
  value: string;
  baseline: string;
  delta: string;
  status: "firing" | "claimed";
  metric: string;
  biz: BizLine;
}
