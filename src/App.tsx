import { useMemo, useState, type ComponentType } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  Activity, AlertTriangle, ArrowDownRight, ArrowRight, ArrowUpRight,
  Bell, BookOpen, Check, ChevronDown, ChevronRight, CircleHelp,
  Clock3, Database, ExternalLink, Filter, Gauge, Headphones,
  LayoutDashboard, Menu, MoreHorizontal, Network, RefreshCw,
  Search, ShieldCheck, Siren, SlidersHorizontal, X,
  Car, Repeat, Users, Wine, Plane, Globe, Workflow, Library, FileCode2,
} from "lucide-react";
import MetricLibrary from "./MetricLibrary";
import ApiDocs from "./ApiDocs";
import {
  Area, AreaChart, Bar, BarChart, CartesianGrid, Cell,
  ComposedChart, Line, PieChart, Pie, ResponsiveContainer,
  Tooltip, XAxis, YAxis,
} from "recharts";
import {
  type AlertItem, type BizLine, bizLines, initialAlerts,
  volumeDataAll, bizOverview, funnels, statusNodes,
  orderEvents, riskData, cityPerf, fmt,
  type TopoLevel, coreApis, apiTrend, slowCalls, topoNodes, topoEdges,
} from "./data";

/* ── nav config ── */
const navItems = [
  { id: "sentinel", label: "值班哨", icon: Siren, no: "01" },
  { id: "business", label: "经营大盘", icon: LayoutDashboard, no: "02" },
  { id: "quality", label: "履约质量", icon: Gauge, no: "03" },
  { id: "service", label: "客服工作台", icon: Headphones, no: "04" },
  { id: "risk", label: "风控观测", icon: ShieldCheck, no: "05" },
  { id: "tech", label: "接口监控", icon: Workflow, no: "06" },
  { id: "library", label: "指标库", icon: Library, no: "07" },
  { id: "system", label: "元监控", icon: Network, no: "08" },
  { id: "docs", label: "接口文档", icon: FileCode2, no: "API" },
];

const bizIcons: Record<BizLine, ComponentType<{ size?: number }>> = {
  all: Globe, driver: Car, transfer: Repeat, carpool: Users, designated: Wine, airport: Plane,
};

function BizIcon({ id, size = 13 }: { id: BizLine; size?: number }) {
  const Icon = bizIcons[id];
  return <Icon size={size} />;
}

/* ── helpers ── */
function Metric({ label, value, delta, good = true, note }: { label: string; value: string; delta: string; good?: boolean; note?: string }) {
  return (
    <div className="metric-cell">
      <div className="metric-label">{label}<CircleHelp size={13} /></div>
      <div className="metric-value">{value}</div>
      <div className={`metric-delta ${good ? "good" : "bad"}`}>
        {good ? <ArrowUpRight size={14} /> : <ArrowDownRight size={14} />} {delta}
        <span>{note || "较昨日"}</span>
      </div>
    </div>
  );
}

function Level({ value }: { value: AlertItem["level"] }) {
  return <span className={`level level-${value.toLowerCase()}`}>{value}</span>;
}

function BizBadge({ biz }: { biz: BizLine }) {
  const b = bizLines.find(x => x.id === biz);
  if (!b) return null;
  if (b.id === "all")
    return <span className="biz-badge" style={{ background: "#6f707c18", color: "#6f707c" }}><Globe size={9} /> 平台级</span>;
  return <span className="biz-badge" style={{ background: b.color + "18", color: b.color }}><BizIcon id={biz} size={9} /> {b.short}</span>;
}

function pageSubtitle(active: string) {
  return ({
    business: "从下单到结算，各业务线经营健康与城市归因。",
    quality: "聚焦取消、等待、客诉与申诉的履约体验。",
    service: "按订单号还原完整事件时间线，快速处理纠纷。",
    risk: "观察规则命中趋势与异常特征，不影响业务链路。",
    tech: "核心接口失败率、上下游依赖异常率与调用链延迟健康。",
    library: "指标定义的统一真源，业务系统按事件契约上报即可产出指标。",
    system: "监控监控系统本身，避免数据断流被误读为业务正常。",
  } as Record<string, string>)[active];
}

/* ══════════ APP ══════════ */
export default function App() {
  const [active, setActive] = useState("sentinel");
  const [biz, setBiz] = useState<BizLine>("all");
  const [alerts, setAlerts] = useState(initialAlerts);
  const [selectedAlert, setSelectedAlert] = useState<AlertItem | null>(null);
  const [mobileNav, setMobileNav] = useState(false);
  const [orderId, setOrderId] = useState("CP20260903018462");
  const [searchedOrder, setSearchedOrder] = useState("CP20260903018462");
  const [bizDropdown, setBizDropdown] = useState(false);

  const filteredAlerts = biz === "all" ? alerts : alerts.filter(a => a.biz === biz);
  const firing = filteredAlerts.filter(a => a.status === "firing").length;
  const totalFiring = alerts.filter(a => a.status === "firing").length;
  const pageTitle = useMemo(() => navItems.find(i => i.id === active)?.label || "值班哨", [active]);
  const currentBiz = bizLines.find(b => b.id === biz)!;

  const acknowledge = (id: number) => {
    setAlerts(v => v.map(a => a.id === id ? { ...a, status: "claimed" } : a));
    setSelectedAlert(v => v?.id === id ? { ...v, status: "claimed" } : v);
  };

  if (active === "docs") return <ApiDocs onExit={() => setActive("sentinel")} />;

  return (
    <div className="app-shell">
      <AnimatePresence>
        {mobileNav && <motion.div className="mobile-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setMobileNav(false)} />}
      </AnimatePresence>

      {/* ── Sidebar ── */}
      <aside className={`sidebar ${mobileNav ? "open" : ""}`}>
        <div className="brand">
          <div className="brand-mark"><Activity size={21} strokeWidth={2.5} /></div>
          <div><strong>业务监控平台</strong><span>Monitor · 多业务线订单监控</span></div>
          <button className="mobile-close" onClick={() => setMobileNav(false)}><X size={18} /></button>
        </div>

        {/* biz line switcher */}
        <div className="biz-switcher">
          <button className="biz-current" onClick={() => setBizDropdown(!bizDropdown)}>
            <span className="biz-dot" style={{ background: currentBiz.color }} />
            <span className="biz-name"><BizIcon id={biz} size={13} />{currentBiz.label}</span>
            <ChevronDown size={14} className={bizDropdown ? "rotate" : ""} />
          </button>
          <AnimatePresence>
            {bizDropdown && (
              <motion.div className="biz-menu" initial={{ opacity: 0, y: -6 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -6 }}>
                {bizLines.map(b => (
                  <button key={b.id} className={`biz-option ${biz === b.id ? "active" : ""}`}
                    onClick={() => { setBiz(b.id); setBizDropdown(false); }}>
                    <span className="biz-dot" style={{ background: b.color }} />
                    <span className="biz-name"><BizIcon id={b.id} size={13} />{b.label}</span>
                    {biz === b.id && <Check size={14} />}
                  </button>
                ))}
              </motion.div>
            )}
          </AnimatePresence>
        </div>

        <div className="nav-caption">监控视图</div>
        <nav>
          {navItems.map(item => (
            <button key={item.id} className={`nav-item ${active === item.id ? "active" : ""}`}
              onClick={() => { setActive(item.id); setMobileNav(false); }}>
              <item.icon size={18} /><span className="nav-no">{item.no}</span><span>{item.label}</span>
              {item.id === "sentinel" && totalFiring > 0 && <span className="nav-count">{totalFiring}</span>}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <button className={`nav-item ${active === "library" ? "active" : ""}`} onClick={() => setActive("library")}><BookOpen size={18} /><span>指标字典</span><ExternalLink size={13} className="ml-auto" /></button>
          <button className="nav-item"><SlidersHorizontal size={18} /><span>告警规则</span></button>
          <div className="user-row">
            <div className="avatar">林</div>
            <div><strong>林舟</strong><span>今日值班 · 研发</span></div>
            <MoreHorizontal size={17} />
          </div>
        </div>
      </aside>

      {/* ── Main ── */}
      <main className="main">
        <header className="topbar">
          <button className="icon-button menu-button" onClick={() => setMobileNav(true)}><Menu size={20} /></button>
          <div className="breadcrumb">
            <span>业务监控平台</span><ChevronRight size={14} />
            <span className="biz-crumb" style={{ color: currentBiz.color }}><BizIcon id={biz} size={11} /> {currentBiz.short}</span>
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
              <p className="eyebrow">{navItems.find(i => i.id === active)?.no} / {currentBiz.short.toUpperCase()} MONITOR</p>
              <h1>{pageTitle}</h1>
              <p>{active === "sentinel" ? "实时链路健康，全业务线订单主干一目了然。" : pageSubtitle(active)}</p>
            </div>
            <div className="filters">
              <button><Clock3 size={15} />今日<ChevronDown size={14} /></button>
              <button><Filter size={15} />全国<ChevronDown size={14} /></button>
              <button className="refresh"><RefreshCw size={15} /></button>
            </div>
          </div>

          <AnimatePresence mode="wait">
            <motion.div key={`${active}-${biz}`} initial={{ opacity: 0, y: 8 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -5 }} transition={{ duration: 0.24 }}>
              {active === "sentinel" && <Sentinel alerts={filteredAlerts} firing={firing} onSelect={setSelectedAlert} onAck={acknowledge} biz={biz} />}
              {active === "business" && <Business biz={biz} />}
              {active === "quality" && <Quality biz={biz} />}
              {active === "service" && <Service orderId={orderId} setOrderId={setOrderId} searchedOrder={searchedOrder} search={() => setSearchedOrder(orderId || "CP20260903018462")} />}
              {active === "risk" && <Risk biz={biz} />}
              {active === "tech" && <Tech biz={biz} />}
              {active === "library" && <MetricLibrary biz={biz} />}
              {active === "system" && <SystemView />}
            </motion.div>
          </AnimatePresence>
        </div>
      </main>

      {/* ── Alert drawer ── */}
      <AnimatePresence>
        {selectedAlert && (
          <>
            <motion.div className="drawer-backdrop" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={() => setSelectedAlert(null)} />
            <motion.aside className="drawer" initial={{ x: "100%" }} animate={{ x: 0 }} exit={{ x: "100%" }} transition={{ type: "spring", damping: 28, stiffness: 280 }}>
              <button className="drawer-close" onClick={() => setSelectedAlert(null)}><X size={18} /></button>
              <div className="drawer-head">
                <div className="drawer-head-row"><Level value={selectedAlert.level} /><BizBadge biz={selectedAlert.biz} /><span className="drawer-subtitle">活动告警</span></div>
                <h2>{selectedAlert.title}</h2>
                <p>{selectedAlert.scope} · {selectedAlert.time}</p>
              </div>
              <div className="alert-reading">
                <span>当前值<strong>{selectedAlert.value}</strong></span>
                <ArrowRight size={18} />
                <span>告警基线<strong>{selectedAlert.baseline.replace("基线 ", "").replace("阈值 ", "")}</strong></span>
              </div>
              <section className="drawer-section">
                <h3>触发说明</h3>
                <p>该指标已连续 3 个求值周期满足规则条件。当前偏离幅度 <b>{selectedAlert.delta}</b>，样本分母满足最小门槛。</p>
                <code>{selectedAlert.metric}</code>
              </section>
              <section className="drawer-section">
                <h3>快速定位</h3>
                {["打开带参数的下钻面板", "查看指标定义与口径", "查看处理 Runbook"].map((x, i) => (
                  <button className="link-row" key={x}>{i ? <BookOpen size={17} /> : <Activity size={17} />}<span>{x}</span><ExternalLink size={15} /></button>
                ))}
              </section>
              <div className="drawer-footer">
                {selectedAlert.status === "firing"
                  ? <button className="primary-button" onClick={() => acknowledge(selectedAlert.id)}><Check size={17} />认领告警</button>
                  : <button className="claimed-button"><Check size={17} />林舟已认领</button>
                }
                <button className="secondary-button">创建静默</button>
              </div>
            </motion.aside>
          </>
        )}
      </AnimatePresence>
    </div>
  );
}

/* ══════════ SENTINEL ══════════ */
function Sentinel({ alerts, firing, onSelect, onAck, biz }: { alerts: AlertItem[]; firing: number; onSelect: (a: AlertItem) => void; onAck: (id: number) => void; biz: BizLine }) {
  return (
    <div className="content-stack">
      {/* health pipeline */}
      <section className="health-section">
        <div className="section-title">
          <div><h2>链路健康</h2><p>最近求值 14:32:00，数据窗口延迟 1 分钟</p></div>
          <span className="health-summary"><i />5 个节点中 1 个异常</span>
        </div>
        <div className="health-line">
          {statusNodes.map((n, i) => (
            <div className={`health-node ${n.state}`} key={n.label}>
              <div className="node-top">
                <span className="node-dot">{n.state === "ok" ? <Check size={14} /> : <AlertTriangle size={14} />}</span>
                {i < statusNodes.length - 1 && <span className="connector" />}
              </div>
              <strong>{n.label}</strong><b>{n.value}</b><span>{n.sub}</span>
            </div>
          ))}
        </div>
      </section>

      {/* biz line overview cards — only in "all" mode */}
      {biz === "all" && <BizOverviewCards />}

      {/* KPI band */}
      <section className="metrics-band">
        <Metric label="今日总订单" value={biz === "all" ? "557,865" : (bizOverview[biz]?.orders || "—")} delta={biz === "all" ? "5.6%" : (bizOverview[biz]?.delta || "—")} />
        <Metric label="今日完单" value={biz === "all" ? "440,707" : (bizOverview[biz]?.delivered || "—")} delta="3.1%" />
        <Metric label="综合完单率" value={biz === "all" ? "88.2%" : (bizOverview[biz]?.rate || "—")} delta="1.2pp" note="30 日基线" good={biz === "all" ? true : bizOverview[biz]?.rateGood ?? true} />
        <Metric label="净 GTV" value={biz === "all" ? "¥ 6,550万" : (bizOverview[biz]?.gtv || "—")} delta="4.7%" />
        <Metric label="结算延迟率" value="0.42%" delta="0.18pp" good={false} />
      </section>

      {/* main chart + alerts */}
      <div className="dashboard-grid">
        <section className="panel chart-panel">
          <div className="panel-head">
            <div><h2>订单主链路</h2><p>{biz === "all" ? "全业务线" : bizLines.find(b => b.id === biz)?.label} · 今日 · 每 2 小时聚合</p></div>
            <div className="legend"><span><i className="purple" />下单</span><span><i className="blue" />完单</span><span><i className="green" />结算</span></div>
          </div>
          <div className="main-chart">
            <ResponsiveContainer width="100%" height="100%">
              <AreaChart data={volumeDataAll} margin={{ top: 12, right: 8, left: -18, bottom: 0 }}>
                <defs>
                  <linearGradient id="pub" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#6558d3" stopOpacity={0.22} /><stop offset="100%" stopColor="#6558d3" stopOpacity={0} />
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="#e8e8ef" />
                <XAxis dataKey="time" axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 11 }} />
                <YAxis axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 11 }} />
                <Tooltip />
                <Area type="monotone" dataKey="orders" stroke="#6558d3" strokeWidth={2} fill="url(#pub)" />
                <Line type="monotone" dataKey="delivered" stroke="#3587e7" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="settled" stroke="#25a579" strokeWidth={2} dot={false} />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        </section>

        <section className="panel alert-panel">
          <div className="panel-head">
            <div><h2>活动告警 <span className="title-count">{firing}</span></h2><p>按优先级与触发时间排序</p></div>
            <button className="text-button">全部告警<ChevronRight size={14} /></button>
          </div>
          <div className="alert-list">
            {alerts.map(a => (
              <button className="alert-item" key={a.id} onClick={() => onSelect(a)}>
                <div className="alert-main">
                  <Level value={a.level} />
                  <div>
                    <strong>{a.title}</strong>
                    <span><BizBadge biz={a.biz} /> {a.scope} · {a.time}</span>
                  </div>
                  <ChevronRight size={16} />
                </div>
                <div className="alert-values">
                  <b>{a.value}</b><span>{a.delta}</span>
                  {a.status === "claimed"
                    ? <em><Check size={12} /> 已认领</em>
                    : <em className="claim" onClick={e => { e.stopPropagation(); onAck(a.id); }}>认领</em>
                  }
                </div>
              </button>
            ))}
          </div>
        </section>
      </div>

      <div className="freshness-note">
        <Database size={15} />
        <span>数据截至 2026-09-03 14:31:00 (Asia/Shanghai)</span><span>·</span>
        <span>指标口径版本 v2026.09</span>
      </div>
    </div>
  );
}

/* ── Biz Overview Cards ── */
function BizOverviewCards() {
  const entries = bizLines.filter(b => b.id !== "all");
  return (
    <div className="biz-cards">
      {entries.map(b => {
        const d = bizOverview[b.id];
        const Icon = bizIcons[b.id];
        return (
          <div className="biz-card" key={b.id} style={{ borderTopColor: b.color }}>
            <div className="biz-card-head">
              <span className="biz-card-icon" style={{ background: b.color + "18", color: b.color }}>{Icon && <Icon size={16} />}</span>
              <strong>{b.label}</strong>
            </div>
            <div className="biz-card-body">
              <div><span>订单</span><b>{d.orders}</b></div>
              <div><span>完单率</span><b style={d.rateGood ? {} : { color: "#d25555" }}>{d.rate}</b></div>
              <div><span>GTV</span><b>{d.gtv}</b></div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

/* ══════════ BUSINESS ══════════ */
function Business({ biz }: { biz: BizLine }) {
  const activeBiz = biz === "all" ? "carpool" : biz;
  const currentFunnel = funnels[activeBiz] || funnels.carpool;
  const d = bizOverview[activeBiz] || bizOverview.carpool;

  // composition donut
  const compData = bizLines.filter(b => b.id !== "all").map(b => ({
    name: b.label, value: parseInt(bizOverview[b.id].orders.replace(/,/g, "")), color: b.color,
  }));

  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <Metric label="周完成订单" value={biz === "all" ? "2,486,420" : "356,842"} delta="6.8%" />
        <Metric label="综合完单率" value={biz === "all" ? "88.2%" : d.rate} delta="2.4pp" good={d.rateGood} />
        <Metric label="客单价" value={biz === "all" ? "¥117.4" : "¥86.5"} delta="3.2%" />
        <Metric label="净 GTV" value={biz === "all" ? "¥ 6,550万" : d.gtv} delta="4.7%" />
      </section>

      <div className="business-grid">
        <section className="panel">
          <div className="panel-head">
            <div><h2>订单履约漏斗</h2><p>{biz === "all" ? "全平台" : bizLines.find(b => b.id === biz)?.label} · 今日事件口径</p></div>
            <span className="version">口径 v2</span>
          </div>
          <div className="funnel-list">
            {currentFunnel.map((x, i) => (
              <motion.div className="funnel-row" key={x.label} initial={{ opacity: 0, x: -8 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: i * 0.05 }}>
                <span>{x.label}</span>
                <div className="funnel-track"><i style={{ width: `${Math.max(30, x.value / currentFunnel[0].value * 100)}%` }} /></div>
                <b>{fmt(x.value)}</b><em>{i ? `${x.rate}%` : "基数"}</em>
              </motion.div>
            ))}
          </div>
        </section>

        {biz === "all" ? (
          <section className="panel">
            <div className="panel-head"><div><h2>业务线构成</h2><p>今日订单量占比</p></div></div>
            <div className="composition-chart">
              <ResponsiveContainer width="100%" height={200}>
                <PieChart><Pie data={compData} cx="50%" cy="50%" innerRadius={55} outerRadius={80} dataKey="value" paddingAngle={2}>
                  {compData.map(e => <Cell key={e.name} fill={e.color} />)}
                </Pie><Tooltip /></PieChart>
              </ResponsiveContainer>
              <div className="comp-legend">
                {compData.map(c => <span key={c.name}><i style={{ background: c.color }} />{c.name}</span>)}
              </div>
            </div>
          </section>
        ) : (
          <section className="panel">
            <div className="panel-head"><div><h2>城市经营表现</h2><p>按今日完单量排序</p></div><button className="text-button">查看全部<ChevronRight size={14} /></button></div>
            <CityRows />
          </section>
        )}
      </div>

      <section className="panel heatmap">
        <div className="panel-head"><div><h2>城市 × 时段供需热力</h2><p>颜色越深表示完单率越高</p></div></div>
        <div className="heat-grid">
          {["杭州", "广州", "成都", "上海", "南京"].map((name, row) => (
            <div className="heat-row" key={name}><span>{name}</span>
              {Array.from({ length: 12 }).map((_, col) => <i key={col} style={{ opacity: 0.16 + ((row * 3 + col * 5) % 8) / 10 }} title={`${name} ${col * 2}:00`} />)}
            </div>
          ))}
        </div>
      </section>
    </div>
  );
}

function CityRows() {
  return (
    <div className="city-table">
      <div className="city-row head"><span>城市</span><span>完单量</span><span>完单率</span><span>同比</span></div>
      {cityPerf.map(x => (
        <div className="city-row" key={x[0]}><strong>{x[0]}</strong><span>{x[1]}</span><span>{x[2]}</span><em className={x[3].startsWith("-") ? "negative" : ""}>{x[3]}</em></div>
      ))}
    </div>
  );
}

/* ══════════ QUALITY ══════════ */
function Quality({ biz }: { biz: BizLine }) {
  const cancelData = [
    { name: "司机/服务方有责", value: biz === "all" ? 3824 : 824, color: "#e45e5e" },
    { name: "司机/服务方无责", value: biz === "all" ? 5175 : 1175, color: "#edae49" },
    { name: "用户取消", value: biz === "all" ? 9398 : 2398, color: "#6558d3" },
    { name: "系统取消", value: biz === "all" ? 1286 : 286, color: "#9b9ca8" },
  ];
  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <Metric label="服务方有责取消率" value={biz === "all" ? "1.12%" : "1.34%"} delta="0.3pp" good={false} />
        <Metric label="爽约率" value="0.62%" delta="0.08pp" />
        <Metric label="等待超时率" value="12.8%" delta="1.6pp" good={false} />
        <Metric label="客诉率（7日）" value="0.38%" delta="0.05pp" />
      </section>
      <div className="business-grid">
        <section className="panel">
          <div className="panel-head"><div><h2>取消归因</h2><p>{biz === "all" ? "全业务线" : bizLines.find(b => b.id === biz)?.label} · 按 责任方 × 阶段 × 归责</p></div></div>
          <div className="bar-chart">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={cancelData} layout="vertical" margin={{ left: 20, right: 28 }}>
                <XAxis type="number" hide /><YAxis dataKey="name" type="category" axisLine={false} tickLine={false} width={100} />
                <Tooltip /><Bar dataKey="value" radius={[0, 4, 4, 0]} barSize={18}>
                  {cancelData.map(e => <Cell key={e.name} fill={e.color} />)}
                </Bar>
              </BarChart>
            </ResponsiveContainer>
          </div>
        </section>
        <section className="panel">
          <div className="panel-head"><div><h2>体验风险城市</h2><p>综合等待、客诉、差评与申诉推翻</p></div></div>
          <div className="risk-city-list">
            {[["成都", "等待超率", "17.3%", "高"], ["广州", "申诉推翻率", "32.8%", "高"], ["武汉", "客诉率", "0.53%", "中"], ["杭州", "差评率", "1.86%", "中"]].map(x => (
              <div key={x[0]}><strong>{x[0]}</strong><span>{x[1]}</span><b>{x[2]}</b><em className={x[3] === "高" ? "high" : ""}>{x[3]}</em></div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

/* ══════════ SERVICE ══════════ */
function Service({ orderId, setOrderId, searchedOrder, search }: { orderId: string; setOrderId: (v: string) => void; searchedOrder: string; search: () => void }) {
  return (
    <div className="content-stack">
      <section className="order-search">
        <Search size={20} />
        <input value={orderId} onChange={e => setOrderId(e.target.value)} onKeyDown={e => e.key === "Enter" && search()} placeholder="输入订单号查询（支持全业务线，最长 90 天）" />
        <button onClick={search}>查询订单</button>
      </section>
      <section className="order-layout">
        <div className="order-summary">
          <div className="summary-head"><span>订单快照</span><b>已完成</b></div>
          <h2>{searchedOrder}</h2>
          <dl>
            {[["业务线", "顺风车"], ["城市", "杭州市"], ["座型", "独享"], ["订单金额", "¥86.50"], ["司机标识", "8a7f...21de"], ["业务归属日", "2026-09-03"]].map(x => (
              <div key={x[0]}><dt>{x[0]}</dt><dd>{x[1]}</dd></div>
            ))}
            <div><dt>事件完整性</dt><dd className="complete"><Check size={13} /> 完整</dd></div>
          </dl>
          <p><ShieldCheck size={14} />仅展示脱敏后的维度快照</p>
        </div>
        <div className="timeline-panel">
          <div className="panel-head">
            <div><h2>订单事件时间线</h2><p>共 {orderEvents.length} 个领域事件 · 链路耗时 2h 06m</p></div>
            <button className="text-button">查看原始 JSON</button>
          </div>
          <div className="timeline">
            {orderEvents.map((e, i) => (
              <div className="timeline-item" key={e.time}>
                <span className="timeline-time">{e.time}</span>
                <i>{i === orderEvents.length - 1 ? <Check size={12} /> : null}</i>
                <div><strong>{e.name}</strong><code>{e.event}</code><p>{e.note}</p></div>
              </div>
            ))}
          </div>
        </div>
      </section>
    </div>
  );
}

/* ══════════ RISK ══════════ */
function Risk({ biz }: { biz: BizLine }) {
  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <Metric label="今日规则命中" value={biz === "all" ? "1,493" : "493"} delta="8.6%" good={false} />
        <Metric label="拦截订单" value={biz === "all" ? "428" : "128"} delta="3.4%" good={false} />
        <Metric label="冻结金额" value={biz === "all" ? "¥ 62.8万" : "¥ 18.6万"} delta="5.1%" good={false} />
        <Metric label="申诉推翻率" value="18.4%" delta="2.2pp" />
      </section>
      <div className="business-grid">
        <section className="panel">
          <div className="panel-head"><div><h2>规则命中趋势</h2><p>{biz === "all" ? "全业务线" : bizLines.find(b => b.id === biz)?.label} · 最近 7 天</p></div></div>
          <div className="bar-chart">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={riskData} margin={{ top: 10, right: 10, left: -20 }}>
                <CartesianGrid vertical={false} stroke="#ececf1" />
                <XAxis dataKey="time" axisLine={false} tickLine={false} />
                <YAxis axisLine={false} tickLine={false} />
                <Tooltip />
                <Bar dataKey="hit" fill="#6558d3" radius={[4, 4, 0, 0]} barSize={24} />
                <Line dataKey="frozen" stroke="#e45e5e" strokeWidth={2} />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        </section>
        <section className="panel">
          <div className="panel-head"><div><h2>高频异常用户</h2><p>ODS 24 小时滑动窗口</p></div></div>
          <div className="city-table">
            <div className="city-row head"><span>用户标识</span><span>短单</span><span>命中</span><span>风险</span></div>
            {[["91ad...0fe2", "18", "12", "高"], ["4bc2...821a", "15", "9", "高"], ["72ef...d191", "12", "7", "中"], ["0a84...31dd", "11", "6", "中"]].map(r => (
              <div className="city-row" key={r[0]}><strong className="mono">{r[0]}</strong><span>{r[1]}</span><span>{r[2]}</span><em className={r[3] === "高" ? "negative" : ""}>{r[3]}</em></div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

/* ══════════ TECH (接口监控) ══════════ */
const topoColors: Record<TopoLevel, string> = { ok: "#25a579", warn: "#e0923f", bad: "#dc5a58" };

function Spark({ data, color, w = 72, h = 22 }: { data: number[]; color: string; w?: number; h?: number }) {
  const max = Math.max(...data), min = Math.min(...data);
  const pts = data.map((v, i) => `${(i / (data.length - 1)) * w},${h - ((v - min) / ((max - min) || 1)) * (h - 2) - 1}`).join(" ");
  return <svg width={w} height={h} className="spark"><polyline points={pts} fill="none" stroke={color} strokeWidth={1.5} strokeLinejoin="round" /></svg>;
}

function TopoChart() {
  const nodes = Object.fromEntries(topoNodes.map(n => [n.id, n]));
  return (
    <svg viewBox="0 0 900 430" role="img" aria-label="上下游依赖拓扑">
      {topoEdges.map(e => {
        const f = nodes[e.from], t = nodes[e.to];
        const x1 = f.x + f.w, y1 = f.y + f.h / 2, x2 = t.x, y2 = t.y + t.h / 2;
        const dx = Math.max((x2 - x1) / 2, 44);
        const col = topoColors[e.level];
        const mx = (x1 + x2) / 2, my = (y1 + y2) / 2;
        return (
          <g key={e.from + "→" + e.to}>
            <path
              d={`M ${x1} ${y1} C ${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`}
              fill="none" stroke={col} strokeWidth={e.level === "ok" ? 1.5 : 2.2}
              strokeDasharray="5 5" className="topo-flow" opacity={e.level === "ok" ? 0.55 : 0.9}
            />
            <g transform={`translate(${mx},${my})`}>
              <rect x={-44} y={-11} width={88} height={22} rx={4} fill="#fff" stroke={col} strokeOpacity={0.4} />
              <text textAnchor="middle" dominantBaseline="central" fontSize={9.5} fill={col} fontWeight={600}>
                {`异常率 ${e.rate.toFixed(2)}%`}
              </text>
            </g>
          </g>
        );
      })}
      {topoNodes.map(n => {
        const badIn = topoEdges.some(e => e.to === n.id && e.level === "bad");
        return (
          <g key={n.id} transform={`translate(${n.x},${n.y})`}>
            <rect width={n.w} height={n.h} rx={8} className={`topo-node ${badIn ? "alert" : ""}`} />
            <text x={n.w / 2} y={17} textAnchor="middle" fontSize={11} fill="#2c2c38" fontWeight={600}>{n.label}</text>
            <text x={n.w / 2} y={31} textAnchor="middle" fontSize={8.5} fill="#8a8b96">{n.sub}</text>
            {badIn && <circle cx={n.w - 8} cy={8} r={3.5} fill="#dc5a58" className="topo-blink" />}
          </g>
        );
      })}
    </svg>
  );
}

function Tech({ biz }: { biz: BizLine }) {
  const apis = biz === "all" ? coreApis : coreApis.filter(a => a.biz === biz || a.biz === "all");
  const badCount = coreApis.filter(a => a.status === "bad").length;
  return (
    <div className="content-stack">
      <section className="metrics-band">
        <Metric label="监控核心接口" value="48 个" delta="2 个" note="较上周" />
        <Metric label="当前异常接口" value={`${badCount} 个`} delta="2 个" good={false} />
        <Metric label="平均失败率" value="0.34%" delta="0.11pp" good={false} />
        <Metric label="调用总量（上游入口）" value="8,420 QPS" delta="6.4%" />
        <Metric label="链路 P99 延迟" value="640ms" delta="120ms" good={false} />
      </section>

      <section className="panel">
        <div className="panel-head">
          <div>
            <h2>上下游依赖异常率</h2>
            <p>调用链实时标注 · 虚线流动方向即调用方向</p>
          </div>
          <div className="topo-legend">
            <span><i style={{ color: "#25a579" }} />正常 &lt;0.1%</span>
            <span><i style={{ color: "#e0923f" }} />波动 0.1–2%</span>
            <span><i style={{ color: "#dc5a58" }} />异常 &gt;2%</span>
          </div>
        </div>
        <div className="tech-topo"><TopoChart /></div>
      </section>

      <div className="business-grid">
        <section className="panel">
          <div className="panel-head">
            <div>
              <h2>核心接口失败率{biz !== "all" && <span className="panel-filter-note">{bizLines.find(b => b.id === biz)?.label}线 + 平台级</span>}</h2>
              <p>按失败率倒序 · 1 分钟粒度实时聚合</p>
            </div>
            <button className="text-button">全部接口<ChevronRight size={14} /></button>
          </div>
          <div className="api-table">
            <div className="api-row head">
              <span>接口 / 服务</span><span>业务线</span><span className="ta-r">QPS</span>
              <span className="ta-r">失败率</span><span className="ta-r">P99</span><span className="ta-r">30min 趋势</span><span>状态</span>
            </div>
            {apis.map(a => (
              <div className="api-row" key={a.name}>
                <div className="api-name"><strong>{a.name}</strong><span>{a.service}</span></div>
                <div className="c-biz"><BizBadge biz={a.biz} /></div>
                <span className="c-qps">{fmt(a.qps)}</span>
                <span className={`fail-num ${a.failRate >= 2 ? "bad" : a.failRate >= 0.5 ? "warn" : ""}`}>{a.failRate.toFixed(2)}%</span>
                <span className="c-p99">{a.p99}ms</span>
                <div className="c-trend"><Spark data={a.trend} color={a.status === "bad" ? "#dc5a58" : a.status === "warn" ? "#e0923f" : "#25a579"} /></div>
                <span className={`status-chip ${a.status}`}>{a.status === "bad" ? "异常" : a.status === "warn" ? "波动" : "正常"}</span>
              </div>
            ))}
          </div>
        </section>

        <section className="panel">
          <div className="panel-head">
            <div><h2>失败率趋势</h2><p>核心下游接口 · 近 6 小时</p></div>
            <div className="legend">
              <span><i style={{ background: "#dc5a58" }} />支付</span>
              <span><i style={{ background: "#e0923f" }} />派单</span>
              <span><i style={{ background: "#6558d3" }} />下单</span>
            </div>
          </div>
          <div style={{ height: 190 }}>
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={apiTrend} margin={{ top: 8, right: 6, left: -14, bottom: 0 }}>
                <CartesianGrid vertical={false} stroke="#ececf1" />
                <XAxis dataKey="t" axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 10 }} />
                <YAxis axisLine={false} tickLine={false} tick={{ fill: "#8b8c99", fontSize: 10 }} tickFormatter={(v: number) => `${v}%`} />
                <Tooltip formatter={((v: number | undefined) => `${v ?? 0}%`) as never} />
                <Line type="monotone" dataKey="pay" name="支付回调" stroke="#dc5a58" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="dispatch" name="派单响应" stroke="#e0923f" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="order" name="创建订单" stroke="#6558d3" strokeWidth={2} dot={false} />
                <Line type="monotone" dataKey="transfer" name="转单提交" stroke="#25a579" strokeWidth={1.5} dot={false} strokeDasharray="4 3" />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
          <h3 className="minor-head">慢调用 TOP</h3>
          <div className="slow-list">
            {slowCalls.map(s => (
              <div key={s[0]}><code>{s[0]}</code><b>{s[1]}</b><span>{s[2]}</span></div>
            ))}
          </div>
        </section>
      </div>
    </div>
  );
}

/* ══════════ SYSTEM ══════════ */
function SystemView() {
  return (
    <div className="content-stack">
      <section className="metrics-band four">
        <Metric label="消费延迟 P99" value="1.2s" delta="0.4s" />
        <Metric label="迟到事件率" value="0.018%" delta="0.003pp" />
        <Metric label="状态缺口" value="23" delta="7 笔" good={false} />
        <Metric label="告警有效率" value="84.6%" delta="6.2pp" note="较上周" />
      </section>
      <section className="panel system-table">
        <div className="panel-head"><div><h2>数据链路组件</h2><p>全业务线共享旁路链路</p></div></div>
        <div className="component-row head"><span>组件</span><span>状态</span><span>吞吐 / 延迟</span><span>最近心跳</span><span>备注</span></div>
        {[
          ["order-domain-topic (5 线)", "正常", "8,420 msg/s", "14:32:08", "lag 2,210"],
          ["monitor-consumer ×4", "正常", "8,406 msg/s", "14:32:08", "本地缓冲 0"],
          ["ClickHouse replica-01", "正常", "写入 42ms", "14:32:07", "8C / 32G"],
          ["ClickHouse replica-02", "正常", "复制延迟 0.3s", "14:32:07", "8C / 32G"],
          ["RuleEvaluator", "正常", "48 条 / min", "14:32:00", "求值 328ms"],
          ["T+1 reconciler", "注意", "缺口 23 笔", "06:18:42", "等待重算"],
        ].map((r, i) => (
          <div className="component-row" key={r[0]}>
            <strong>{r[0]}</strong>
            <span className={i === 5 ? "status-warn" : "status-ok"}><i />{r[1]}</span>
            <span>{r[2]}</span><span>{r[3]}</span><span>{r[4]}</span>
          </div>
        ))}
      </section>
    </div>
  );
}
