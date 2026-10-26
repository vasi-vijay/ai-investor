import { Component, ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, Rec } from './api';

export function useApi<T = Rec>(path: string | null, refreshMs = 0, bump = 0) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const alive = useRef(true);
  const load = useCallback(async () => {
    if (!path) return;
    setLoading(true);
    try {
      const d = await api.get<T>(path);
      if (alive.current) { setData(d); setError(null); }
    } catch (e) {
      if (alive.current) setError((e as Error).message);
    } finally {
      if (alive.current) setLoading(false);
    }
  }, [path]);
  useEffect(() => {
    alive.current = true;
    load();
    const t = refreshMs ? setInterval(load, refreshMs) : undefined;
    return () => { alive.current = false; if (t) clearInterval(t); };
  }, [load, refreshMs, bump]);
  return { data, error, loading, reload: load };
}

export function Card({ title, children, right, className = '' }: { title?: string; children: ReactNode; right?: ReactNode; className?: string }) {
  return (
    <section className={'card ' + className}>
      {(title || right) && (
        <header className="card-h">
          <h3>{title}</h3>
          {right}
        </header>
      )}
      {children}
    </section>
  );
}

export function Stat({ label, value, sub, tone }: { label: ReactNode; value: ReactNode; sub?: ReactNode; tone?: string }) {
  return (
    <div className="stat">
      <div className="stat-l">{label}</div>
      <div className={'stat-v ' + (tone ?? '')}>{value}</div>
      {sub && <div className="stat-s">{sub}</div>}
    </div>
  );
}

export type Tone = 'ok' | 'warn' | 'bad' | 'info' | 'muted';
export function Badge({ tone = 'muted', children, title }: { tone?: Tone; children: ReactNode; title?: string }) {
  return <span className={'badge ' + tone} title={title}>{children}</span>;
}

export function ErrorNote({ error }: { error: string | null }) {
  return error ? <div className="note bad">⚠ {error}</div> : null;
}

export function Meter({ pct, label }: { pct: number; label: string }) {
  const p = Math.max(0, Math.min(100, pct || 0));
  const tone = p >= 90 ? 'bad' : p >= 60 ? 'warn' : 'ok';
  return (
    <div className="meter" role="img" aria-label={`${label} ${p}%`}>
      <div className="meter-t"><span>{label}</span><span>{p.toFixed(0)}%</span></div>
      <div className="meter-bar"><div className={'meter-fill ' + tone} style={{ width: p + '%' }} /></div>
    </div>
  );
}

/** Minimal SVG line chart for cumulative P&L (no external chart library). */
export function LineChart({ points, label }: { points: number[]; label: string }) {
  if (points.length < 2) return <div className="muted small">Not enough closed trades yet for a curve.</div>;
  const w = 600, h = 140, pad = 6;
  const min = Math.min(0, ...points), max = Math.max(0, ...points);
  const span = max - min || 1;
  const x = (i: number) => pad + (i / (points.length - 1)) * (w - 2 * pad);
  const y = (v: number) => h - pad - ((v - min) / span) * (h - 2 * pad);
  const d = points.map((v, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)},${y(v).toFixed(1)}`).join(' ');
  return (
    <svg viewBox={`0 0 ${w} ${h}`} className="chart" role="img" aria-label={label} preserveAspectRatio="none">
      <line x1={pad} x2={w - pad} y1={y(0)} y2={y(0)} className="chart-zero" />
      <path d={d} className="chart-line" fill="none" />
    </svg>
  );
}

export function KV({ rows }: { rows: [string, ReactNode][] }) {
  return (
    <dl className="kv">
      {rows.map(([k, v]) => (
        <div key={k}><dt>{k}</dt><dd>{v}</dd></div>
      ))}
    </dl>
  );
}

export function List({ items, empty = '—' }: { items?: string[] | null; empty?: string }) {
  if (!items || items.length === 0) return <div className="muted small">{empty}</div>;
  return <ul className="list">{items.map((s, i) => <li key={i}>{s}</li>)}</ul>;
}

export function SimTag() {
  return <Badge tone="info" title="Simulated: no real order was sent to any broker">SIMULATED</Badge>;
}

// ------------------------------------------------------------------ production UI kit

export function useDebounced<T>(value: T, ms = 250): T {
  const [v, setV] = useState(value);
  useEffect(() => { const t = setTimeout(() => setV(value), ms); return () => clearTimeout(t); }, [value, ms]);
  return v;
}

export function Skeleton({ lines = 3 }: { lines?: number }) {
  return <div className="skeleton" aria-busy="true" aria-label="Loading">{Array.from({ length: lines }, (_, i) => <div key={i} className="sk-line" />)}</div>;
}

export function Empty({ title, hint }: { title: string; hint?: ReactNode }) {
  return <div className="empty"><div className="empty-t">{title}</div>{hint && <div className="muted small">{hint}</div>}</div>;
}

/** Source attribution for any financial figure: LIVE broker data, HISTORICAL data or SIMULATION. */
export function SourceTag({ kind }: { kind: 'LIVE' | 'HISTORICAL' | 'SIMULATION' }) {
  const tone: Tone = kind === 'LIVE' ? 'ok' : kind === 'SIMULATION' ? 'info' : 'muted';
  const glyph = kind === 'LIVE' ? '●' : kind === 'SIMULATION' ? '◇' : '◷';
  return <Badge tone={tone} title={{ LIVE: 'Real data from the broker', HISTORICAL: 'Recorded historical data', SIMULATION: 'Simulated — not a real account figure' }[kind]}>{glyph} {kind}</Badge>;
}

export type Col<R> = { key: string; head: string; render?: (r: R) => ReactNode; sort?: (r: R) => string | number | null | undefined; num?: boolean; text?: (r: R) => string };

/** Sortable, text-filterable (debounced), paginated table. Large data sets render one page at a time. */
export function DataTable<R extends Record<string, any>>({ cols, rows, empty, pageSize = 25, filterable = true, rowKey, onRow }: { // eslint-disable-line @typescript-eslint/no-explicit-any
  cols: Col<R>[]; rows: R[]; empty: string; pageSize?: number; filterable?: boolean; rowKey?: (r: R, i: number) => string; onRow?: (r: R) => void;
}) {
  const [q, setQ] = useState('');
  const dq = useDebounced(q).trim().toLowerCase();
  const [sort, setSort] = useState<{ key: string; dir: 1 | -1 } | null>(null);
  const [page, setPage] = useState(0);
  const filtered = useMemo(() => dq ? rows.filter((r) => cols.some((c) => String(c.text ? c.text(r) : c.sort ? c.sort(r) : r[c.key] ?? '').toLowerCase().includes(dq))) : rows, [rows, dq, cols]);
  const sorted = useMemo(() => {
    if (!sort) return filtered;
    const c = cols.find((x) => x.key === sort.key);
    const get = (r: R) => (c?.sort ? c.sort(r) : r[sort.key]) ?? '';
    return [...filtered].sort((a, b) => { const x = get(a), y = get(b); return (typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y))) * sort.dir; });
  }, [filtered, sort, cols]);
  useEffect(() => setPage(0), [dq, sort, rows.length]);
  const pages = Math.max(1, Math.ceil(sorted.length / pageSize));
  const view = sorted.slice(page * pageSize, page * pageSize + pageSize);
  return (
    <div>
      {filterable && rows.length > 0 && <div className="row"><input type="search" placeholder="Filter…" aria-label="Filter rows" value={q} onChange={(e) => setQ(e.target.value)} /><span className="muted small">{sorted.length} of {rows.length}</span></div>}
      <div className="table-wrap">
        <table>
          <thead><tr>{cols.map((c) => (
            <th key={c.key} aria-sort={sort?.key === c.key ? (sort.dir === 1 ? 'ascending' : 'descending') : 'none'} className={c.num ? 'num' : ''}>
              <button className="th-btn" onClick={() => setSort((s) => (s?.key === c.key ? (s.dir === 1 ? { key: c.key, dir: -1 } : null) : { key: c.key, dir: 1 }))}>
                {c.head}{sort?.key === c.key ? (sort.dir === 1 ? ' ▲' : ' ▼') : ''}
              </button></th>))}</tr></thead>
          <tbody>
            {view.map((r, i) => (
              <tr key={rowKey ? rowKey(r, i) : i} className={onRow ? 'click' : ''} onClick={onRow ? () => onRow(r) : undefined}>
                {cols.map((c) => <td key={c.key} className={c.num ? 'num' : ''}>{c.render ? c.render(r) : String(r[c.key] ?? '—')}</td>)}
              </tr>))}
            {view.length === 0 && <tr><td colSpan={cols.length} className="muted">{rows.length ? 'No rows match the filter.' : empty}</td></tr>}
          </tbody>
        </table>
      </div>
      {pages > 1 && (
        <div className="pager"><button className="btn small" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span className="small muted">Page {page + 1} / {pages}</span>
          <button className="btn small" disabled={page >= pages - 1} onClick={() => setPage(page + 1)}>Next</button></div>)}
    </div>
  );
}

export class ErrorBoundary extends Component<{ children: ReactNode; name: string }, { error: Error | null }> {
  state = { error: null as Error | null };
  static getDerivedStateFromError(error: Error) { return { error }; }
  render() {
    if (!this.state.error) return this.props.children;
    return <div className="note bad" role="alert"><b>{this.props.name} failed to render.</b> {this.state.error.message}
      <div><button className="btn small" onClick={() => this.setState({ error: null })}>Retry</button></div></div>;
  }
}

export function ConfirmButton({ label, confirmText, onConfirm, tone = '', disabled }: { label: string; confirmText: string; onConfirm: () => void; tone?: string; disabled?: boolean }) {
  const [open, setOpen] = useState(false);
  if (!open) return <button className={'btn ' + tone} disabled={disabled} onClick={() => setOpen(true)}>{label}</button>;
  return <span className="confirm" role="alertdialog" aria-label={label}><span className="small">{confirmText}</span>
    <button className={'btn small ' + tone} onClick={() => { setOpen(false); onConfirm(); }}>Confirm</button>
    <button className="btn small" onClick={() => setOpen(false)}>Cancel</button></span>;
}
