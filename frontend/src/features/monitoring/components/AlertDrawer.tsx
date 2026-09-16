import { Activity, ArrowRight, BookOpen, Check, ExternalLink, X } from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import type { AlertItem } from "../model/alert";
import { BusinessBadge } from "./BusinessBadge";
import { LevelBadge } from "./LevelBadge";

interface AlertDrawerProps {
  alert: AlertItem | null;
  onClose: () => void;
  onAcknowledge: (id: number) => void;
}

export function AlertDrawer({ alert, onClose, onAcknowledge }: AlertDrawerProps) {
  return (
    <AnimatePresence>
      {alert && (
        <>
          <motion.div
            className="drawer-backdrop"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={onClose}
          />
          <motion.aside
            className="drawer"
            initial={{ x: "100%" }}
            animate={{ x: 0 }}
            exit={{ x: "100%" }}
            transition={{ type: "spring", damping: 28, stiffness: 280 }}
          >
            <button className="drawer-close" onClick={onClose}>
              <X size={18} />
            </button>
            <div className="drawer-head">
              <div className="drawer-head-row">
                <LevelBadge value={alert.level} />
                <BusinessBadge biz={alert.biz} />
                <span className="drawer-subtitle">活动告警</span>
              </div>
              <h2>{alert.title}</h2>
              <p>{alert.scope} · {alert.time}</p>
            </div>
            <div className="alert-reading">
              <span>
                当前值<strong>{alert.value}</strong>
              </span>
              <ArrowRight size={18} />
              <span>
                告警基线<strong>{alert.baseline.replace("基线 ", "").replace("阈值 ", "")}</strong>
              </span>
            </div>
            <section className="drawer-section">
              <h3>触发说明</h3>
              <p>
                该指标已连续 3 个求值周期满足规则条件。当前偏离幅度 <b>{alert.delta}</b>，样本分母满足最小门槛。
              </p>
              <code>{alert.metric}</code>
            </section>
            <section className="drawer-section">
              <h3>快速定位</h3>
              {["打开带参数的下钻面板", "查看指标定义与口径", "查看处理 Runbook"].map((item, index) => (
                <button className="link-row" key={item}>
                  {index ? <BookOpen size={17} /> : <Activity size={17} />}
                  <span>{item}</span>
                  <ExternalLink size={15} />
                </button>
              ))}
            </section>
            <div className="drawer-footer">
              {alert.status === "firing" ? (
                <button className="primary-button" onClick={() => onAcknowledge(alert.id)}>
                  <Check size={17} />认领告警
                </button>
              ) : (
                <button className="claimed-button">
                  <Check size={17} />林舟已认领
                </button>
              )}
              <button className="secondary-button">创建静默</button>
            </div>
          </motion.aside>
        </>
      )}
    </AnimatePresence>
  );
}
