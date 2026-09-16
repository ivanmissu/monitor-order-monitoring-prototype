export type BizLine = "all" | "driver" | "transfer" | "carpool" | "designated" | "airport";

export interface BizLineOption {
  id: BizLine;
  label: string;
  short: string;
  color: string;
}

export const bizLines: BizLineOption[] = [
  { id: "all", label: "全平台总览", short: "全平台", color: "#6558d3" },
  { id: "driver", label: "司机端", short: "司机端", color: "#3587e7" },
  { id: "transfer", label: "转单端", short: "转单端", color: "#e0923f" },
  { id: "carpool", label: "顺风车", short: "顺风车", color: "#25a579" },
  { id: "designated", label: "代驾", short: "代驾", color: "#c56ad0" },
  { id: "airport", label: "接送机", short: "接送机", color: "#dc5a58" },
];

export const bizLineLabels: Record<BizLine, string> = {
  all: "全平台",
  driver: "司机端",
  transfer: "转单端",
  carpool: "顺风车",
  designated: "代驾",
  airport: "接送机",
};

export function getBizLineOption(id: BizLine): BizLineOption {
  return bizLines.find((item) => item.id === id) ?? bizLines[0];
}
