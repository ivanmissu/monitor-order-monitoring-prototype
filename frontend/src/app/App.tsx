import { lazy, Suspense, useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { type BizLine } from "@/entities/business/model";
import { AlertDrawer } from "@/features/monitoring/components/AlertDrawer";
import { initialAlerts } from "@/features/monitoring/data/mock-dashboard";
import { toAlertItem } from "@/features/monitoring/model/alert-adapter";
import type { AlertItem } from "@/features/monitoring/model/alert";
import { api } from "@/services/monitor/client";
import { useApi } from "@/services/monitor/use-api";
import { AppLayout } from "./layout/AppLayout";
import type { MonitorRouteId } from "./config/navigation";

const ApiDocsPage = lazy(() => import("@/features/api-docs/ApiDocsPage").then((module) => ({ default: module.ApiDocsPage })));
const MetricLibraryPage = lazy(() => import("@/features/metrics/MetricLibraryPage").then((module) => ({ default: module.MetricLibraryPage })));
const BusinessView = lazy(() => import("@/features/monitoring/views/BusinessView").then((module) => ({ default: module.BusinessView })));
const QualityView = lazy(() => import("@/features/monitoring/views/QualityView").then((module) => ({ default: module.QualityView })));
const RiskView = lazy(() => import("@/features/monitoring/views/RiskView").then((module) => ({ default: module.RiskView })));
const SentinelView = lazy(() => import("@/features/monitoring/views/SentinelView").then((module) => ({ default: module.SentinelView })));
const ServiceView = lazy(() => import("@/features/monitoring/views/ServiceView").then((module) => ({ default: module.ServiceView })));
const SystemView = lazy(() => import("@/features/monitoring/views/SystemView").then((module) => ({ default: module.SystemView })));
const TechView = lazy(() => import("@/features/monitoring/views/TechView").then((module) => ({ default: module.TechView })));

const DEFAULT_ORDER_ID = "CP20260903018462";
const loadingFallback = <div className="panel page-loading">正在加载监控视图…</div>;

export default function App() {
  const [active, setActive] = useState<MonitorRouteId>("sentinel");
  const [biz, setBiz] = useState<BizLine>("all");
  const [localAlerts, setLocalAlerts] = useState(initialAlerts);
  const [selectedAlert, setSelectedAlert] = useState<AlertItem | null>(null);
  const [orderId, setOrderId] = useState(DEFAULT_ORDER_ID);
  const [searchedOrder, setSearchedOrder] = useState(DEFAULT_ORDER_ID);
  const [ackedIds, setAckedIds] = useState<Set<number>>(new Set());

  // 告警列表：优先读后端 /api/v1/alerts（30s 轮询），失败回退静态演示数据。
  const alertsApi = useApi((signal) => api.alerts("all", "all", signal), [], { pollMs: 30_000 });
  const backendAlerts = useMemo<AlertItem[] | null>(() => {
    if (!alertsApi.data?.items) return null;
    return alertsApi.data.items.map(toAlertItem);
  }, [alertsApi.data]);

  // live 时用后端数据（叠加本地已认领态），否则回退本地静态数据。
  const alerts = useMemo<AlertItem[]>(() => {
    const base = backendAlerts ?? localAlerts;
    return base.map((alert) => (ackedIds.has(alert.id) ? { ...alert, status: "claimed" } : alert));
  }, [ackedIds, backendAlerts, localAlerts]);

  const filteredAlerts = biz === "all" ? alerts : alerts.filter((alert) => alert.biz === biz);
  const firing = filteredAlerts.filter((alert) => alert.status === "firing").length;
  const totalFiring = alerts.filter((alert) => alert.status === "firing").length;

  const acknowledge = (id: number) => {
    // 乐观更新 UI；若后端可用则调用认领接口（幂等）。
    setAckedIds((prev) => new Set(prev).add(id));
    setLocalAlerts((items) => items.map((alert) => (alert.id === id ? { ...alert, status: "claimed" } : alert)));
    setSelectedAlert((alert) => (alert?.id === id ? { ...alert, status: "claimed" } : alert));
    if (backendAlerts) {
      api.ackAlert(id, "林舟", "值班认领").catch(() => {
        /* 认领失败保留乐观态，等待轮询纠正 */
      });
    }
  };

  if (active === "docs") {
    return (
      <Suspense fallback={loadingFallback}>
        <ApiDocsPage onExit={() => setActive("sentinel")} />
      </Suspense>
    );
  }

  return (
    <>
      <AppLayout active={active} biz={biz} totalFiring={totalFiring} onBizChange={setBiz} onNavigate={setActive}>
        <Suspense fallback={loadingFallback}>
          <AnimatePresence mode="wait">
            <motion.div
              key={`${active}-${biz}`}
              initial={{ opacity: 0, y: 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0, y: -5 }}
              transition={{ duration: 0.24 }}
            >
              {active === "sentinel" && (
                <SentinelView
                  alerts={filteredAlerts}
                  firing={firing}
                  onSelect={setSelectedAlert}
                  onAck={acknowledge}
                  biz={biz}
                  alertsState={alertsApi.state}
                />
              )}
              {active === "business" && <BusinessView biz={biz} />}
              {active === "quality" && <QualityView biz={biz} />}
              {active === "service" && (
                <ServiceView
                  orderId={orderId}
                  setOrderId={setOrderId}
                  searchedOrder={searchedOrder}
                  search={() => setSearchedOrder(orderId || DEFAULT_ORDER_ID)}
                />
              )}
              {active === "risk" && <RiskView biz={biz} />}
              {active === "tech" && <TechView biz={biz} />}
              {active === "library" && <MetricLibraryPage biz={biz} />}
              {active === "system" && <SystemView />}
            </motion.div>
          </AnimatePresence>
        </Suspense>
      </AppLayout>

      <AlertDrawer alert={selectedAlert} onClose={() => setSelectedAlert(null)} onAcknowledge={acknowledge} />
    </>
  );
}
