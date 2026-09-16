import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  Activity, ArrowLeft, BookOpen, Check, ChevronDown, ChevronRight, Copy,
  Database, Info, Layers, Search, Server, Terminal, X,
} from "lucide-react";
import { docSections, docMeta, type Endpoint, type Method } from "./data/api-doc-data";

const METHOD_COLOR: Record<Method, string> = {
  GET: "#3587e7", POST: "#6558d3", PUT: "#e0923f", DELETE: "#dc5a58", SSE: "#25a579",
};
const ROLE_LABEL: Record<string, string> = {
  dash_read: "大盘只读", cs_detail: "客服明细", alert_ops: "值班处置", ingest: "事件上报", admin: "管理员",
};
const ALL_METHODS: Method[] = ["GET", "POST", "PUT", "DELETE", "SSE"];

function CodeBlock({ src, lang }: { src: string; lang: string }) {
  const [ok, setOk] = useState(false);
  return (
    <div className="doc-code">
      <div className="doc-code-head">
        <span>{lang}</span>
        <button onClick={() => { navigator.clipboard?.writeText(src).catch(() => {}); setOk(true); setTimeout(() => setOk(false), 1500); }}>
          {ok ? <Check size={12} /> : <Copy size={12} />}{ok ? "已复制" : "复制"}
        </button>
      </div>
      <pre>{src}</pre>
    </div>
  );
}

function EndpointCard({ ep, open, onToggle }: { ep: Endpoint; open: boolean; onToggle: () => void }) {
  const hasBody = !!(ep.params || ep.req || ep.resp || ep.desc || ep.note || ep.perf);
  return (
    <div className={`doc-ep ${open ? "open" : ""}`} id={ep.path.replace(/[^a-z0-9]/gi, "-")}>
      <button className="doc-ep-head" onClick={onToggle} disabled={!hasBody}>
        <span className="doc-method" style={{ background: METHOD_COLOR[ep.method] + "14", color: METHOD_COLOR[ep.method], borderColor: METHOD_COLOR[ep.method] + "40" }}>
          {ep.method}
        </span>
        <code className="doc-path">{ep.path}</code>
        <span className="doc-ep-title">{ep.title}</span>
        <span className="doc-role">{ROLE_LABEL[ep.role]}</span>
        {hasBody && <ChevronDown size={15} className="doc-ep-caret" />}
      </button>
      <AnimatePresence initial={false}>
        {open && hasBody && (
          <motion.div
            className="doc-ep-body-wrap"
            initial={{ height: 0, opacity: 0 }} animate={{ height: "auto", opacity: 1 }} exit={{ height: 0, opacity: 0 }}
            transition={{ duration: 0.24, ease: [0.22, 0.7, 0.3, 1] }}
          >
            <div className="doc-ep-body">
              {ep.desc && <p className="doc-ep-desc">{ep.desc}</p>}
              {ep.params && ep.params.length > 0 && (
                <div className="doc-sub">
                  <h5>{ep.method === "GET" ? "请求参数" : "请求字段"}</h5>
                  <div className="doc-table-wrap">
                    <table className="doc-table">
                      <thead><tr><th>名称</th><th>位置</th><th>类型</th><th></th><th>说明</th></tr></thead>
                      <tbody>
                        {ep.params.map(p => (
                          <tr key={p.n}>
                            <td><code>{p.n}</code></td>
                            <td className="doc-muted">{p.in}</td>
                            <td className="doc-muted">{p.t}</td>
                            <td>{p.r && <em className="doc-req">必填</em>}</td>
                            <td>{p.d}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              )}
              {ep.req && <div className="doc-sub"><h5>请求体示例</h5><CodeBlock lang="json · request" src={ep.req} /></div>}
              {ep.resp && <div className="doc-sub"><h5>{ep.method === "SSE" ? "推送帧示例" : ep.method === "GET" ? "响应示例" : "响应示例"}</h5><CodeBlock lang={ep.method === "SSE" ? "text/event-stream" : "json · response"} src={ep.resp} /></div>}
              {(ep.note || ep.perf) && (
                <div className="doc-ep-notes">
                  {ep.perf && <p><Server size={13} /><span>{ep.perf}</span></p>}
                  {ep.note && <p><Info size={13} /><span>{ep.note}</span></p>}
                </div>
              )}
            </div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}



export function ApiDocsPage({ onExit }: { onExit: () => void }) {
  const totalEndpoints = useMemo(() => docSections.reduce((n, s) => n + (s.endpoints?.length || 0), 0), []);
  const [methods, setMethods] = useState<Method[]>([]);
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<Record<string, boolean>>({ "POST /api/v1/ingest/events": true, "GET /api/v1/sentinel/bootstrap": true });
  const [activeId, setActiveId] = useState(docSections[0].id);
  const refs = useRef<Record<string, HTMLElement | null>>({});
  // 点击目录跳转时锁定：平滑滚动途中忽略 IntersectionObserver 的自动判定，
  // 否则滚动经过的每个 section 都会被短暂高亮，导致选中项「乱跳」。
  const scrollLockUntil = useRef(0);

  const filtered = useMemo(() => {
    const kw = q.trim().toLowerCase();
    return docSections.map(s => {
      if (!s.endpoints) return s;
      let eps = s.endpoints;
      if (methods.length) eps = eps.filter(e => methods.includes(e.method));
      if (kw) eps = eps.filter(e => (e.path + e.title + (e.desc || "")).toLowerCase().includes(kw));
      return { ...s, endpoints: eps };
    }).filter(s => (s.endpoints ? s.endpoints.length > 0 : (!kw && !methods.length) || (s.title.toLowerCase().includes(kw) || (s.lead || "").toLowerCase().includes(kw))));
  }, [q, methods]);

  const kw = q.trim().toLowerCase();
  const autoOpen = !!kw || methods.length > 0;

  useEffect(() => {
    const obs = new IntersectionObserver(
      entries => {
        // 跳转动画进行中：跳过自动判定，选中项以点击目标为准
        if (Date.now() < scrollLockUntil.current) return;
        const visible = entries.filter(e => e.isIntersecting).sort((a, b) => a.boundingClientRect.top - b.boundingClientRect.top)[0];
        if (visible?.target.id) setActiveId(visible.target.id);
      },
      { rootMargin: "-84px 0px -68% 0px", threshold: [0, 0.15] }
    );
    Object.values(refs.current).forEach(el => el && obs.observe(el));
    return () => obs.disconnect();
  }, [filtered.length]);

  const jump = (id: string) => {
    const el = refs.current[id];
    if (!el) return;
    // 立即锁定并高亮目标；平滑滚动最多约 1s，锁定期内 observer 不改写选中项
    scrollLockUntil.current = Date.now() + 1200;
    setActiveId(id);
    el.scrollIntoView({ behavior: "smooth", block: "start" });
    // scrollend 支持时精确解锁；不支持则由上面的时间戳兜底
    const scroller = document.scrollingElement || document.documentElement;
    const onEnd = () => {
      scrollLockUntil.current = 0;
      scroller.removeEventListener("scrollend", onEnd);
    };
    scroller.addEventListener("scrollend", onEnd);
  };

  return (
    <div className="doc-shell">
      <div className="doc-bg" aria-hidden />

      <header className="doc-topbar">
        <div className="doc-top-left">
          <div className="brand-mark"><Activity size={19} strokeWidth={2.5} /></div>
          <div>
            <strong>业务监控平台</strong>
            <span>后端接口文档 · monitor-server API Specification</span>
          </div>
        </div>
        <div className="doc-top-mid">
          <button className="doc-base" onClick={() => navigator.clipboard?.writeText(docMeta.base + "/api/v1").catch(() => {})}>
            <Terminal size={13} />
            <span>{docMeta.base}<b>/api/v1</b></span>
            <Copy size={12} />
          </button>
        </div>
        <div className="doc-top-right">
          <span className="doc-chip">版本 {docMeta.version}</span>
          <span className="doc-chip amber">{docMeta.status}</span>
          <span className="doc-chip muted">{docMeta.date}</span>
          <button className="doc-back" onClick={onExit}><ArrowLeft size={15} />返回平台</button>
        </div>
      </header>

      <div className="doc-hero">
        <div className="doc-hero-main">
          <p className="doc-kicker">SPEC · 按监控视图逐屏梳理 · 1 份契约</p>
          <h1>旁路监控的<br />读写两侧接口合同</h1>
          <p className="doc-lead">
            读侧只查 ClickHouse 聚合层与事件明细层，写侧只接收领域事件。比率不落库、口径不硬编码、权限即数据源——
            这三条约束贯穿下面全部 {totalEndpoints} 个端点。
          </p>
          <div className="doc-stats">
            {[
              { k: "接口域", v: String(docSections.filter(s => s.kind === "api").length), s: "module" },
              { k: "端点", v: String(totalEndpoints), s: "endpoints" },
              { k: "Token 角色", v: String(docMeta.stats.roles), s: "roles" },
              { k: "聚合查询 SLO", v: docMeta.stats.slo, s: "P99 latency" },
              { k: "明细查询 SLO", v: "P99 < 3s", s: "order timeline" },
            ].map(x => (
              <div className="doc-stat" key={x.k}>
                <b>{x.v}</b><span>{x.k}</span><em>{x.s}</em>
              </div>
            ))}
          </div>
        </div>
        <div className="doc-hero-side">
          <div className="doc-hero-card">
            <div className="doc-hero-card-head"><Database size={14} /><span>数据源边界</span></div>
            <ul>
              <li><code>agg_1m/5m/1h/1d</code><span>大盘、告警、接口监控</span></li>
              <li><code>ods_order_event</code><span>客服时间线、风控窗口</span></li>
              <li><code>dict_metric</code><span>口径、阈值、维度枚举</span></li>
              <li><code>alert_event + audit</code><span>认领、根因、有效率</span></li>
              <li className="deny"><code>业务库</code><span>永不连接 · 只读旁路</span></li>
            </ul>
          </div>
          <CodeBlock
            lang="shell · 鉴权"
            src={`# 五类 token，前端按视图选凭据；越权维度返回 40301（不静默过滤）
curl -H "Authorization: Bearer $MONITOR_DASH_TOKEN" \\
     "${docMeta.base}/api/v1/overview/summary?metrics=core.delivery_rate"

# 响应头携带口径水印，图例必须展示
# X-Monitor-Freshness: 2026-09-03T14:31:00+08:00
# X-Monitor-partial:    true
# X-Monitor-Dict-Version: 2026.09`}
          />
        </div>
      </div>

      <div className="doc-body">
        <nav className="doc-rail">
          <div className="doc-rail-search">
            <Search size={14} />
            <input value={q} onChange={e => setQ(e.target.value)} placeholder="搜索端点 / 路径" />
            {q && <button onClick={() => setQ("")}><X size={12} /></button>}
          </div>
          <div className="doc-rail-label">METHOD</div>
          <div className="doc-methods">
            {ALL_METHODS.map(m => (
              <button key={m} className={`doc-mchip ${methods.includes(m) ? "active" : ""}`}
                style={methods.includes(m) ? { background: METHOD_COLOR[m] + "1f", color: METHOD_COLOR[m], borderColor: METHOD_COLOR[m] + "55" } : {}}
                onClick={() => setMethods(v => v.includes(m) ? v.filter(x => x !== m) : [...v, m])}>
                {m}
              </button>
            ))}
          </div>
          <div className="doc-rail-label">目录</div>
          {docSections.map(s => {
            const shown = filtered.find(f => f.id === s.id);
            const count = shown?.endpoints?.length ?? 0;
            return (
              <button key={s.id} className={`doc-rail-item ${activeId === s.id ? "active" : ""} ${!shown ? "dim" : ""}`}
                onClick={() => jump(s.id)}>
                <span className="doc-rail-no">{s.no}</span>
                <span className="doc-rail-text">{s.title}</span>
                {s.kind === "api" && <em>{count}</em>}
              </button>
            );
          })}
          <div className="doc-rail-foot">
            <BookOpen size={13} />
            <span>
              文档 <code>docs/monitor-api.md</code>
              <br />实现 <code>backend/</code> · Spring Boot 4.1.1
            </span>
          </div>
        </nav>

        <main className="doc-content">
          <div className="doc-toolbar">
            <span>
              {methods.length ? `已筛选 ${methods.join(" / ")}` : "全部方法"}
              {kw && ` · 关键词「${kw}」`}
            </span>
            <div>
              <button onClick={() => setOpen(Object.fromEntries(docSections.flatMap(s => (s.endpoints || []).map(e => [e.method + " " + e.path, true]))))}>展开全部</button>
              <button onClick={() => setOpen({})}>收起全部</button>
            </div>
          </div>

          {filtered.map(s => (
            <section key={s.id} id={s.id} ref={el => { refs.current[s.id] = el; }} className="doc-section">
              <div className="doc-section-head">
                <span className="doc-section-no">{s.no}</span>
                <div>
                  <h2>{s.title}</h2>
                  {s.lead && <p>{s.lead}</p>}
                </div>
                {s.kind === "api" && <span className="doc-section-count">{s.endpoints?.length || 0} 个端点</span>}
              </div>

              {s.prose?.map((p, i) => <p className="doc-prose" key={i}>{p}</p>)}

              {s.tables?.map((t, i) => (
                <div className="doc-sub" key={i}>
                  {t.title && <h5>{t.title}</h5>}
                  <div className="doc-table-wrap">
                    <table className="doc-table">
                      <thead><tr>{t.head.map(h => <th key={h}>{h}</th>)}</tr></thead>
                      <tbody>
                        {t.rows.map((r, ri) => (
                          <tr key={ri}>{r.map((c, ci) => <td key={ci} className={ci === 0 ? "doc-key" : ""}>{c}</td>)}</tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </div>
              ))}

              {s.endpoints && (
                <div className="doc-eps">
                  {s.endpoints.map(e => {
                    const key = e.method + " " + e.path;
                    return (
                      <EndpointCard key={key} ep={e} open={autoOpen || !!open[key]}
                        onToggle={() => setOpen(v => ({ ...v, [key]: !(autoOpen ? false : !!v[key]) }))} />
                    );
                  })}
                </div>
              )}

              {s.code?.map((c, i) => (
                <div className="doc-sub" key={i}>
                  <h5>{c.title}</h5>
                  <CodeBlock lang={c.lang} src={c.src} />
                </div>
              ))}
            </section>
          ))}

          <footer className="doc-foot">
            <Layers size={14} />
            <span>
              文档与前端应用同源：新增面板时先在此登记端点，再实现。字段冻结后只加不改。
              服务端实现见 <code>backend/</code>（Java 25 · Spring Boot 4.1.1），
              零依赖启动：<code>mvn spring-boot:run -Dspring-boot.run.profiles=demo</code>
            </span>
            <button onClick={onExit}>回到平台验证<ChevronRight size={13} /></button>
          </footer>
        </main>
      </div>
    </div>
  );
}
