import { ReactNode, useCallback, useEffect, useRef, useState } from 'react';
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

export function Stat({ label, value, sub, tone }: { label: string; value: ReactNode; sub?: ReactNode; tone?: string }) {
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
