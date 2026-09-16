import { useState, type ReactNode } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  Activity,
  Bell,
  BookOpen,
  Check,
  ChevronDown,
  ChevronRight,
  Clock3,
  ExternalLink,
  Filter,
  Menu,
  MoreHorizontal,
  RefreshCw,
  Search,
  SlidersHorizontal,
  X,
} from "lucide-react";
import { bizLines, getBizLineOption, type BizLine } from "@/entities/business/model";
import { getRouteSubtitle, getRouteTitle, navItems, type MonitorRouteId } from "../config/navigation";
import { BusinessIcon } from "@/features/monitoring/components/BusinessIcon";

interface AppLayoutProps {
  active: MonitorRouteId;
  biz: BizLine;
  totalFiring: number;
  children: ReactNode;
  onBizChange: (biz: BizLine) => void;
  onNavigate: (route: MonitorRouteId) => void;
}

export function AppLayout({ active, biz, totalFiring, children, onBizChange, onNavigate }: AppLayoutProps) {
  const [mobileNav, setMobileNav] = useState(false);
  const [bizDropdown, setBizDropdown] = useState(false);
  const currentBiz = getBizLineOption(biz);
  const pageTitle = getRouteTitle(active);
  const activeNo = navItems.find((item) => item.id === active)?.no;

  const navigate = (route: MonitorRouteId) => {
    onNavigate(route);
    setMobileNav(false);
  };

  return (
    <div className="app-shell">
      <AnimatePresence>
        {mobileNav && (
          <motion.div
            className="mobile-backdrop"
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            onClick={() => setMobileNav(false)}
          />
        )}
      </AnimatePresence>

      <aside className={`sidebar ${mobileNav ? "open" : ""}`}>
        <div className="brand">
          <div className="brand-mark"><Activity size={21} strokeWidth={2.5} /></div>
          <div><strong>业务监控平台</strong><span>Monitor · 多业务线订单监控</span></div>
          <button className="mobile-close" onClick={() => setMobileNav(false)}><X size={18} /></button>
        </div>

        <div className="biz-switcher">
          <button className="biz-current" onClick={() => setBizDropdown((open) => !open)}>
            <span className="biz-dot" style={{ background: currentBiz.color }} />
            <span className="biz-name"><BusinessIcon id={biz} size={13} />{currentBiz.label}</span>
            <ChevronDown size={14} className={bizDropdown ? "rotate" : ""} />
          </button>
          <AnimatePresence>
            {bizDropdown && (
              <motion.div
                className="biz-menu"
                initial={{ opacity: 0, y: -6 }}
                animate={{ opacity: 1, y: 0 }}
                exit={{ opacity: 0, y: -6 }}
              >
                {bizLines.map((item) => (
                  <button
                    key={item.id}
                    className={`biz-option ${biz === item.id ? "active" : ""}`}
                    onClick={() => {
                      onBizChange(item.id);
                      setBizDropdown(false);
                    }}
                  >
                    <span className="biz-dot" style={{ background: item.color }} />
                    <span className="biz-name"><BusinessIcon id={item.id} size={13} />{item.label}</span>
                    {biz === item.id && <Check size={14} />}
                  </button>
                ))}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        <div className="nav-caption">监控视图</div>
        <nav>
          {navItems.map((item) => (
            <button
              key={item.id}
              className={`nav-item ${active === item.id ? "active" : ""}`}
              onClick={() => navigate(item.id)}
            >
              <item.icon size={18} />
              <span className="nav-no">{item.no}</span>
              <span>{item.label}</span>
              {item.id === "sentinel" && totalFiring > 0 && <span className="nav-count">{totalFiring}</span>}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <button className={`nav-item ${active === "library" ? "active" : ""}`} onClick={() => navigate("library")}>
            <BookOpen size={18} /><span>指标字典</span><ExternalLink size={13} className="ml-auto" />
          </button>
          <button className="nav-item"><SlidersHorizontal size={18} /><span>告警规则</span></button>
          <div className="user-row">
            <div className="avatar">林</div>
            <div><strong>林舟</strong><span>今日值班 · 研发</span></div>
            <MoreHorizontal size={17} />
          </div>
        </div>
      </aside>

      <main className="main">
        <header className="topbar">
          <button className="icon-button menu-button" onClick={() => setMobileNav(true)}><Menu size={20} /></button>
          <div className="breadcrumb">
            <span>业务监控平台</span><ChevronRight size={14} />
            <span className="biz-crumb" style={{ color: currentBiz.color }}><BusinessIcon id={biz} size={11} /> {currentBiz.short}</span>
            <ChevronRight size={14} />
            <strong>{pageTitle}</strong>
          </div>
          <div className="top-actions">
            <div className="live"><i />实时更新中</div>
            <button className="icon-button"><Search size={18} /></button>
            <button className="icon-button notification"><Bell size={18} /><span /></button>
          </div>
        </header>

        <div className="page-wrap">
          <div className="page-heading">
            <div>
              <p className="eyebrow">{activeNo} / {currentBiz.short.toUpperCase()} MONITOR</p>
              <h1>{pageTitle}</h1>
              <p>{getRouteSubtitle(active)}</p>
            </div>
            <div className="filters">
              <button><Clock3 size={15} />今日<ChevronDown size={14} /></button>
              <button><Filter size={15} />全国<ChevronDown size={14} /></button>
              <button className="refresh"><RefreshCw size={15} /></button>
            </div>
          </div>

          {children}
        </div>
      </main>
    </div>
  );
}
