import { FormEvent, useEffect, useState } from 'react';
import { api, Rec } from '../api';
import { fmtInr, fmtNum, fmtPct, signClass } from '../format';
import { Badge, Card, ErrorNote, List, useApi } from '../ui';
import { Report } from './Analytics';

const STRATS = ['VWAP_TREND', 'OPENING_RANGE_BREAKOUT', 'MOMENTUM_PULLBACK'];

export default function Backtests({ admin }: { admin: boolean }) {
  const [form, setForm] = useState({ name: 'backtest', from: '2025-03-03', to: '2025-03-14', maxAiCalls: 300, useSplit: false, dev: 6, val: 2, oos: 2 });
  const [strategies, setStrategies] = useState<string[]>(STRATS);
  const [job, setJob] = useState<Rec | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const runs = useApi<Rec[]>('/api/runs?mode=BACKTEST', 0, job?.status === 'COMPLETED' ? 1 : 0);

  useEffect(() => {
    if (!job || !['QUEUED', 'RUNNING'].includes(job.status)) return;
    const t = setInterval(async () => { try { setJob(await api.get(`/api/backtests/jobs/${job.id}`)); } catch (e) { setErr((e as Error).message); } }, 2000);
    return () => clearInterval(t);
  }, [job]);

  const submit = async (e: FormEvent) => {
    e.preventDefault(); setErr(null);
    try {
      const body: Rec = { name: form.name, from: form.from, to: form.to, maxAiCalls: form.maxAiCalls, strategies };
      if (form.useSplit) { body.developmentDays = form.dev; body.validationDays = form.val; body.outOfSampleDays = form.oos; }
      const { job_id } = await api.post<Rec>('/api/backtests', body);
      setJob({ id: job_id, status: 'QUEUED' });
    } catch (ex) { setErr((ex as Error).message); }
  };

  return (
    <div className="stack">
      <div className="banner warn">Backtests use the <b>same engine, risk rules and cost model</b> as paper trading on a look-ahead-free data view. Results are simulations; a short sample proves nothing. Tune on DEVELOPMENT, check on VALIDATION, look at OUT_OF_SAMPLE once.</div>
      <Card title="Run a backtest (compares rules-only, AI-shadow and AI-gated on identical data)">
        {!admin && <div className="note warn">Admin role required to run backtests.</div>}
        <form className="form" onSubmit={submit}>
          <label>Name<input value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label>From<input type="date" value={form.from} onChange={(e) => setForm({ ...form, from: e.target.value })} /></label>
          <label>To<input type="date" value={form.to} onChange={(e) => setForm({ ...form, to: e.target.value })} /></label>
          <label>Max live AI calls<input type="number" min={0} max={5000} value={form.maxAiCalls} onChange={(e) => setForm({ ...form, maxAiCalls: +e.target.value })} /></label>
          <fieldset><legend>Strategies</legend>{STRATS.map((s) => <label key={s} className="inline"><input type="checkbox" checked={strategies.includes(s)} onChange={(e) => setStrategies(e.target.checked ? [...strategies, s] : strategies.filter((x) => x !== s))} /> {s}</label>)}</fieldset>
          <fieldset><legend><label className="inline"><input type="checkbox" checked={form.useSplit} onChange={(e) => setForm({ ...form, useSplit: e.target.checked })} /> chronological split (trading days)</label></legend>
            <label className="inline">dev <input type="number" min={0} value={form.dev} onChange={(e) => setForm({ ...form, dev: +e.target.value })} disabled={!form.useSplit} /></label>
            <label className="inline">validation <input type="number" min={0} value={form.val} onChange={(e) => setForm({ ...form, val: +e.target.value })} disabled={!form.useSplit} /></label>
            <label className="inline">out-of-sample <input type="number" min={0} value={form.oos} onChange={(e) => setForm({ ...form, oos: +e.target.value })} disabled={!form.useSplit} /></label>
          </fieldset>
          <button className="btn primary" disabled={!admin || (job != null && ['QUEUED', 'RUNNING'].includes(job.status))}>Run</button>
        </form>
        <ErrorNote error={err} />
        {job && <div className="small">Job {job.id?.slice(0, 8)}: <Badge tone={job.status === 'COMPLETED' ? 'ok' : job.status === 'FAILED' ? 'bad' : 'info'}>{job.status}</Badge> {job.error}</div>}
      </Card>
      {job?.result && (job.result.periods ? job.result.periods.map((p: Rec, i: number) => <Comparison key={i} r={p} />) : <Comparison r={job.result} />)}
      <Card title="Stored backtest runs">
        <div className="table-wrap"><table><thead><tr><th>Name</th><th>Period</th><th>AI mode</th><th>Data</th><th>Status</th><th /></tr></thead>
          <tbody>{(runs.data ?? []).slice(0, 30).map((r) => <tr key={r.id}><td>{r.name}</td><td>{r.period_label ?? ''}</td><td>{r.ai_mode}</td><td>{r.data_source}</td><td>{r.status}</td><td><button className="btn small" onClick={() => setOpen(open === r.id ? null : r.id)}>{open === r.id ? 'Hide' : 'Open'}</button></td></tr>)}</tbody></table></div>
      </Card>
      {open && <StoredRun id={open} />}
    </div>
  );
}

function StoredRun({ id }: { id: string }) {
  const { data } = useApi<Rec>(`/api/analytics/runs/${id}`);
  return data ? <Report r={data} /> : <div className="muted">Loading…</div>;
}

function Comparison({ r }: { r: Rec }) {
  const c = r.comparison ?? {}, head = c.headline_by_mode ?? {}, p = c.paired_daily_ai_gated_vs_rules_only, coh = c.signal_cohorts_ai_shadow;
  return (
    <Card title={`Comparison · ${r.period_label ?? ''} · ${r.from} → ${r.to} (${r.trading_days} trading days) · analyst ${r.analyst} (${r.analyst_model}) · data ${r.data_source}`}>
      {c.data_caveat && <div className="note warn">{c.data_caveat}</div>}
      <div className="table-wrap"><table>
        <thead><tr><th>Mode</th><th>Trades</th><th>Net P&L</th><th>Return</th><th>Win rate (95% CI)</th><th>Profit factor</th><th>Avg trade (95% CI)</th><th>Max DD</th></tr></thead>
        <tbody>{Object.keys(head).map((m) => { const s = head[m]; return (
          <tr key={m}><td>{{ RULES_ONLY: 'A · rules only', AI_SHADOW: 'B · rules + AI review (same trades as A)', AI_GATED: 'C · AI-gated + same risk' }[m] ?? m}</td>
            <td className="num">{s.trade_count}</td><td className={'num ' + signClass(s.net_pnl)}>{fmtInr(s.net_pnl)}</td><td className="num">{fmtPct(s.return_on_capital_pct, 2)}</td>
            <td className="num">{s.win_rate != null ? fmtPct(s.win_rate * 100, 0) : '—'} {s.win_rate_ci95 && <span className="muted small">({fmtPct(s.win_rate_ci95[0] * 100, 0)}–{fmtPct(s.win_rate_ci95[1] * 100, 0)})</span>}</td>
            <td className="num">{s.profit_factor != null ? fmtNum(s.profit_factor) : '—'}</td>
            <td className="num">{fmtInr(s.avg_trade)} {s.avg_trade_ci95 && <span className="muted small">({fmtInr(s.avg_trade_ci95[0])} to {fmtInr(s.avg_trade_ci95[1])})</span>}</td><td className="num">{fmtInr(s.max_drawdown)}</td></tr>); })}</tbody></table></div>
      {p && (<><h4>Paired daily difference: AI-gated minus rules-only</h4>
        <div className="small">Mean {fmtInr(p.mean_daily_difference_inr)} / day {p.mean_daily_difference_ci95 && <>(95% CI {fmtInr(p.mean_daily_difference_ci95[0])} to {fmtInr(p.mean_daily_difference_ci95[1])})</>} · AI better on {p.days_ai_gated_better} days, worse on {p.days_ai_gated_worse}, equal {p.days_equal}.</div>
        {p.worst_days_for_ai_gated?.length > 0 && <><b className="small">Days where AI-gated underperformed rules-only most</b>
          <List items={p.worst_days_for_ai_gated.map((d: Rec) => `${d.date}: rules ${fmtInr(d.rules_only)} vs AI-gated ${fmtInr(d.ai_gated)} (${fmtInr(d.difference)})`)} /></>}</>)}
      {coh && (<><h4>Same signals, grouped by analyst verdict (hypothetical outcome of the strategy’s own levels, in R)</h4>
        <div className="table-wrap"><table><thead><tr><th>Cohort</th><th>n</th><th>Mean R</th><th>Total R</th><th>Hypothetical win rate (95% CI)</th><th>Sample</th></tr></thead>
          <tbody>{Object.entries(coh.hypothetical_outcome_by_ai_verdict ?? {}).map(([k, v]) => { const s = v as Rec; return (
            <tr key={k}><td>{k}</td><td className="num">{s.n}</td><td className="num">{fmtNum(s.mean_r, 3)}</td><td className="num">{fmtNum(s.total_r, 2)}</td><td className="num">{fmtPct(s.win_rate * 100, 0)} <span className="muted small">({fmtPct(s.win_rate_ci95[0] * 100, 0)}–{fmtPct(s.win_rate_ci95[1] * 100, 0)})</span></td><td>{s.sufficient_sample ? <Badge tone="ok">n ≥ 30</Badge> : <Badge tone="warn">small n</Badge>}</td></tr>); })}</tbody></table></div>
        <div className="small muted">{coh.note} Candidates without AI verdict: {coh.candidates_without_ai_verdict}.</div></>)}
      <div className="note info"><b>Conclusion:</b> {c.conclusion}</div>
    </Card>
  );
}
