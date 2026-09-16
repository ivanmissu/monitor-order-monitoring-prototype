import { bizLines } from "@/entities/business/model";
import type { BizCard } from "@/services/monitor/client";
import { fmtGtvFen, fmtInt, fmtRatio } from "@/services/monitor/format";
import { bizOverview } from "../data/mock-dashboard";
import { businessIcons } from "./BusinessIcon";

export function BizOverviewCards({ cards }: { cards: BizCard[] | null }) {
  const entries = bizLines.filter(b => b.id !== "all");
  const byId = new Map((cards ?? []).map(c => [c.biz_line, c]));
  return (
    <div className="biz-cards">
      {entries.map(b => {
        const live = byId.get(b.id);
        const d = bizOverview[b.id];
        const orders = live ? fmtInt(live.orders) : d.orders;
        const rate = live ? fmtRatio(live.delivery_rate, 1) : d.rate;
        const rateGood = live ? live.rate_good : d.rateGood;
        const gtv = live ? fmtGtvFen(live.gtv_fen) : d.gtv;
        const Icon = businessIcons[b.id];
        return (
          <div className="biz-card" key={b.id} style={{ borderTopColor: b.color }}>
            <div className="biz-card-head">
              <span className="biz-card-icon" style={{ background: b.color + "18", color: b.color }}>{Icon && <Icon size={16} />}</span>
              <strong>{b.label}</strong>
            </div>
            <div className="biz-card-body">
              <div><span>订单</span><b>{orders}</b></div>
              <div><span>完单率</span><b style={rateGood ? {} : { color: "#d25555" }}>{rate}</b></div>
              <div><span>GTV</span><b>{gtv}</b></div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
