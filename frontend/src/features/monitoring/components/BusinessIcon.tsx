import { Car, Globe, Plane, Repeat, Users, Wine, type LucideIcon } from "lucide-react";
import type { BizLine } from "@/entities/business/model";

export const businessIcons: Record<BizLine, LucideIcon> = {
  all: Globe,
  driver: Car,
  transfer: Repeat,
  carpool: Users,
  designated: Wine,
  airport: Plane,
};

export function BusinessIcon({ id, size = 13 }: { id: BizLine; size?: number }) {
  const Icon = businessIcons[id];
  return <Icon size={size} />;
}
