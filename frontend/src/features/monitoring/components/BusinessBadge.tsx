import { Globe } from "lucide-react";
import { bizLines, type BizLine } from "@/entities/business/model";
import { BusinessIcon } from "./BusinessIcon";

export function BusinessBadge({ biz }: { biz: BizLine }) {
  const b = bizLines.find((item) => item.id === biz);
  if (!b) return null;
  if (b.id === "all") {
    return (
      <span className="biz-badge" style={{ background: "#6f707c18", color: "#6f707c" }}>
        <Globe size={9} /> 平台级
      </span>
    );
  }
  return (
    <span className="biz-badge" style={{ background: `${b.color}18`, color: b.color }}>
      <BusinessIcon id={biz} size={9} /> {b.short}
    </span>
  );
}
