import type { AlertItem } from "../model/alert";

export function LevelBadge({ value }: { value: AlertItem["level"] }) {
  return <span className={`level level-${value.toLowerCase()}`}>{value}</span>;
}
