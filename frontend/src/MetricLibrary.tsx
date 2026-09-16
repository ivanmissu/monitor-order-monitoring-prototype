import { useMemo, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import {
  ArrowUpRight, ArrowDownRight, BookOpen, Cog, Check, ChevronRight,
  CheckCircle2, Copy, FileCode2, Globe, Hash, Info, Layers,
  Search, Terminal, X,
} from "lucide-react";
import { type BizLine, bizLines } from "./data";
import {
  type MetricDef, type MetricDomain, metricDefs, domainMeta, typeMeta,
  eventEnvelopeJson, codeSamples, integrationSteps,
} from "./metrics-data";

function Metric({ label, value, delta, good = true, note }: { label: string; value: string; delta: string; good?: boolean; note?: string }) {
  return (
    <div className="metric-cell">
      <div className="metric-label">{label}<Info size={13} /></div>
      <div className="metric-value">{value}</div>
      <div className={`metric-delta ${good ? "good" : "bad"}`}>
        {good ? <ArrowUpRight size={14} /> : <ArrowDownRight size={14} />} {delta}
        <span>{note || ""}</span>
      </div>
    </div>
  );
}

function tbadge(label: string, color: string) {
  return <span className="t-badge" style={{ background: color + "16", color }}>{label}</span>;
}

/* copy button */
function CopyBtn({ text }: { text: string }) {
  const [ok, setOk] = useState(false);
  return (
    <button className="copy-btn" onClick={() => {
      navigator.clipboard?.writeText(text).then(() => setOk(true)).catch(() => setOk(false));
      setTimeout(() => setOk(false), 1600);
    }}>
      {ok ? <Check size={13} /> : <Copy size={13} />}{ok ? "已复制" : "复制"}
    </button>
  );
}

/* ══════════ MetricLibrary ══════════ */
export default function MetricLibrary({ biz }: { biz: BizLine }) {
  const [domain, setDomain] = useState<MetricDomain | "all">("all");
  const [q, setQ] = useState("");
  const [selected, setSelected] = useState<MetricDef | null>(
    () => metricDefs.find(m => m.id === "core.delivery_rate") || null
  );
  const [detailOpen, setDetailOpen] = useState(true);
  const [codeTab, setCodeTab] = useState("java");

  const currentBizLabel = bizLines.find(b => b.id === biz)?.label || "全平台";
  const list = useMemo(() => {
    let rows = metricDefs;
    if (domain !== "all") rows = rows.filter(m => m.domain === domain);
    if (biz !== "all") rows = rows.filter(m => m.biz.includes(biz) || m.biz.includes("all"));
    const kw = q.trim().toLowerCase();
    if (kw) rows = rows.filter(m => m.name.includes(kw) || m.id.toLowerCase().includes(kw));
    return rows;
  }, [domain, biz, q]);

  const stats = useMemo(() => {
    const atomic = metricDefs.filter(m => m.type === "atomic").length;
    const derived = metricDefs.filter(m => m.type === "derived").length;
    const tech = metricDefs.filter(m => m.type === "tech").length;
    return { total: metricDefs.length, atomic, derived, tech, biz: bizLines.length - 1 };
  }, []);

  const activeSample = codeSamples.find(s => s.id === codeTab) || codeSamples[0];

  return (
    <div className="content-stack">
      {/* stats */}
      <section className="metrics-band">
        <Metric label="指标总数" value={String(stats.total)} delta="+3" note="本月新增" />
        <Metric label="原子指标" value={String(stats.atomic)} delta="0" note="可加性子指标" />
        <Metric label="派生指标" value={String(stats.derived)} delta="0" note="查询期派生" />
        <Metric label="技术 / 链路指标" value={String(stats.tech)} delta="2" note="接口/新鲜度" />
        <Metric label="覆盖业务线" value={`${stats.biz} 条`} delta="2" note="最近 90 天" />
      </section>

      {/* filter bar */}
      <section className="panel ml-toolbar">
        <div className="ml-filters-row">
          <div className="ml-search">
            <Search size={15} />
            <input value={q} onChange={e => setQ(e.target.value)} placeholder="搜索指标名或 ID…" />
            {q && <button onClick={() => setQ("")}><X size={13} /></button>}
          </div>
          <span className="ml-biz-note">{biz === "all" ? <>当前显示 全平台 · 共 {list.length} 项</> : <>{currentBizLabel} · 共 {list.length} 项</>}</span>
        </div>
        <div className="ml-chips">
          <button className={`ml-chip ${domain === "all" ? "active" : ""}`} onClick={() => setDomain("all")}>全部</button>
          {(Object.keys(domainMeta) as MetricDomain[]).map(d => (
            <button key={d} className={`ml-chip ${domain === d ? "active" : ""}`}
              style={domain === d ? { background: domainMeta[d].color + "18", color: domainMeta[d].color, borderColor: domainMeta[d].color + "44" } : {}}
              onClick={() => setDomain(d)}>
              {domainMeta[d].label}
            </button>
          ))}
        </div>
      </section>

      {/* list + detail */}
      <div className="ml-grid">
        <section className="panel ml-list">
          <div className="ml-row head">
            <span>指标</span><span>域</span><span>类型</span><span>业务线</span><span>粒度</span><span>状态</span>
          </div>
          {list.map(m => (
            <button key={m.id} className={`ml-row ${selected?.id === m.id ? "active" : ""}`}
              onClick={() => { setSelected(m); setDetailOpen(true); }}>
              <div className="ml-name">
                <Hash size={11} />
                <div>
                  <strong>{m.name}</strong>
                  <code>{m.id}</code>
                </div>
              </div>
              <div>{tbadge(domainMeta[m.domain].label, domainMeta[m.domain].color)}</div>
              <div>{tbadge(typeMeta[m.type].label, typeMeta[m.type].color)}</div>
              <div className="ml-bizcell">
                {m.biz.includes("all")
                  ? <span className="biz-badge" style={{ background: "#6f707c18", color: "#6f707c" }}><Globe size={9} /> 全部</span>
                  : m.biz.map(b => <span key={b} className="biz-badge" style={{ background: (bizLines.find(x => x.id === b)?.color || "#999") + "16", color: bizLines.find(x => x.id === b)?.color }}>{bizLines.find(x => x.id === b)?.short}</span>)}
              </div>
              <span className="ml-grain">{m.grain}</span>
              <span className={`ml-status ${m.status}`}>{m.status === "online" ? "上线" : "试用"}</span>
            </button>
          ))}
          {list.length === 0 && <div className="ml-empty">未找到匹配指标，试试调整筛选</div>}
        </section>

        <AnimatePresence>
          {selected && detailOpen && (
            <motion.aside className="panel ml-detail" key={selected.id}
              initial={{ opacity: 0, x: 12 }} animate={{ opacity: 1, x: 0 }} exit={{ opacity: 0, x: 12 }}>
              <div className="ml-detail-top">
                <div>
                  <div className="ml-detail-badges">
                    {tbadge(typeMeta[selected.type].label, typeMeta[selected.type].color)}
                    <span className="ml-version">口径 {selected.version}</span>
                  </div>
                  <h3>{selected.name}</h3>
                  <code>{selected.id}</code>
                </div>
                <button className="drawer-close" onClick={() => setDetailOpen(false)}><X size={16} /></button>
              </div>

              <p className="ml-desc">{selected.desc}</p>

              <section className="ml-block">
                <h4>定义 / 公式</h4>
                <div className="ml-formula">{selected.formula}</div>
              </section>

              <section className="ml-block">
                <h4>来源事件（{selected.events.length}）</h4>
                <div className="ml-events">
                  {selected.events.map(e => <code key={e}>{e}</code>)}
                </div>
              </section>

              <div className="ml-meta">
                <div><span>更新粒度</span><b>{selected.grain}</b></div>
                <div><span>负责人</span><b>{selected.owner}</b></div>
                <div><span>更新时间</span><b>{selected.updatedAt}</b></div>
                <div><span>使用处</span><b>{selected.usedIn.join(" / ")}</b></div>
              </div>

              {selected.alarmExample && (
                <section className="ml-block alarm">
                  <CheckCircle2 size={14} /><div><h4>关联告警示例</h4><p>{selected.alarmExample}</p></div>
                </section>
              )}

              <button className="ml-goto" onClick={() => document.getElementById("integration")?.scrollIntoView({ behavior: "smooth" })}>
                <Layers size={14} /> 查看该指标集成方式 <ChevronRight size={13} />
              </button>
            </motion.aside>
          )}
        </AnimatePresence>
      </div>

      {/* integration guide */}
      <section className="panel" id="integration">
        <div className="panel-head">
          <div>
            <h2><Layers size={15} /> 业务系统集成指南</h2>
            <p>旁路只读、只消费事件，业务侧最少改动即可接入</p>
          </div>
        </div>

        <div className="ml-steps">
          {integrationSteps.map(s => (
            <div className="ml-step" key={s.no}>
              <span className="ml-step-no">{s.no}</span>
              <strong>{s.title}</strong>
              <p>{s.desc}</p>
              <ul>{s.points.map(p => <li key={p}><Check size={12} />{p}</li>)}</ul>
            </div>
          ))}
        </div>

        <div className="ml-integrate-grid">
          <div>
            <div className="panel-head" style={{ marginBottom: 8 }}>
              <div><h2 style={{ fontSize: 13 }}>统一事件信封</h2><p>维度快照 + 私有字段白名单（props），禁止 PII 明文</p></div>
            </div>
            <pre className="code-block">{eventEnvelopeJson}</pre>
            <div className="ml-events mt">
              {["event_type 稳定不改语义", "维度快照发生时刻值", "at-least-once + 消费端幂等", "Semantic 变更换枚举 + 登记"].map(t => <span key={t} className="ml-tag"><Check size={11} />{t}</span>)}
            </div>
          </div>
          <div>
            <div className="panel-head" style={{ marginBottom: 8 }}>
              <div><h2 style={{ fontSize: 13 }}>接入样例</h2><p>Kafka 推荐 / HTTP 直连 / 指标查询 API</p></div>
              <div className="code-tabs">
                {codeSamples.map(s => (
                  <button key={s.id} className={codeTab === s.id ? "active" : ""} onClick={() => setCodeTab(s.id)}>
                    {s.id === "java" ? <FileCode2 size={12} /> : s.id === "query" ? <Cog size={12} /> : s.id === "http" ? <Terminal size={12} /> : <BookOpen size={12} />}
                    <span>{s.label}</span>
                  </button>
                ))}
              </div>
            </div>
            <div className="code-wrap">
              <div className="code-head">
                <span>{activeSample.lang}</span>
                <CopyBtn text={activeSample.code} />
              </div>
              <pre className="code-block tall">{activeSample.code}</pre>
            </div>
          </div>
        </div>
      </section>
    </div>
  );
}
