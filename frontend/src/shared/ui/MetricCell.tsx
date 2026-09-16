import { ArrowDownRight, ArrowUpRight, CircleHelp, Info } from "lucide-react";

interface MetricCellProps {
  label: string;
  value: string;
  delta: string;
  good?: boolean;
  note?: string;
  helpIcon?: "circle" | "info";
}

export function MetricCell({
  label,
  value,
  delta,
  good = true,
  note,
  helpIcon = "circle",
}: MetricCellProps) {
  const HelpIcon = helpIcon === "info" ? Info : CircleHelp;
  return (
    <div className="metric-cell">
      <div className="metric-label">
        {label}
        <HelpIcon size={13} />
      </div>
      <div className="metric-value">{value}</div>
      <div className={`metric-delta ${good ? "good" : "bad"}`}>
        {good ? <ArrowUpRight size={14} /> : <ArrowDownRight size={14} />} {delta}
        <span>{note || "较昨日"}</span>
      </div>
    </div>
  );
}
