import { useState } from 'react';
import { Rec } from '../api';
import { fmtInr, fmtNum, fmtPct, signClass } from '../format';
import { Badge, Card, ErrorNote, Stat, useApi } from '../ui';

export function StatsTable({ groups }: { groups: Rec }) {
  const keys = Object.keys(groups ?? {});
  return (
    <div className="table-wrap">
      <table>
        <thead><tr><th>Group</th><th>Trades</th><th>Net P&L</th><th>Win rate (95% CI)</th><th>Profit factor</th><th>Avg win</th><th>Avg loss</th><th>Max DD</th><th>Sample</th></tr></thead>
        <tbody>
          {keys.map((k) => { const s = groups[k]; return (
            <tr key={k}>
              <td>{k}</td><td className="num">{s.trade_count}</td><td className={'num ' + signClass(s.net_pnl)}>{fmtInr(s.net_pnl)}</td>
              <td className="num">{s.win_rate != null ? fmtPct(s.win_rate * 100, 0) : '—'} <span className="muted small">{s.win_rate_ci95 ? `(${fmtPct(s.win_rate_ci95[0] * 100, 0)}–${fmtPct(s.win_rate_ci95[1] * 100, 0)})` : ''}</span></td>
              <td className="num">{s.profit_factor != null ? fmtNum(s.profit_factor) : '—'}</td><td className="num">{fmtInr(s.avg_win)}</td><td className="num">{fmtInr(s.avg_loss)}</td>
              <td className="num">{fmtInr(s.max_drawdown)}</td>
              <td>{s.sample_size_sufficient ? <Badge tone="ok">n ≥ 30</Badge> : <Badge tone="warn" title={s.sample_size_warning}>small n</Badge>}</td>
            </tr>); })}
          {keys.length === 0 && <tr><td colSpan={9} className="muted">No closed trades yet.</td></tr>}
        </tbody>
      </table>
    </div>
  );
}

export function Report({ r }: { r: Rec }) {
  const o = r.overall ?? {}, sg = r.signals ?? {}, ai = r.ai ?? {}, c = r.costs_and_slippage ?? {};
  return (
    <div className="stack">
      <div className="banner info">{r.label} · mode <b>{r.run_mode}</b> · data <b>{r.data_source}</b> · AI mode <b>{r.ai_mode}</b></div>
      <div className="grid stats">
        <Stat label="Trades" value={o.trade_count} />
        <Stat label="Net P&L after costs" value={fmtInr(o.net_pnl)} tone={signClass(o.net_pnl)} sub={`return on capital ${fmtPct(o.return_on_capital_pct, 2)}`} />
        <Stat label="Win rate" value={o.win_rate != null ? fmtPct(o.win_rate * 100, 0) : '—'} sub={o.win_rate_ci95 ? `95% CI ${fmtPct(o.win_rate_ci95[0] * 100, 0)}–${fmtPct(o.win_rate_ci95[1] * 100, 0)}` : ''} />
        <Stat label="Profit factor" value={o.profit_factor != null ? fmtNum(o.profit_factor) : '—'} sub={o.profit_factor_note} />
        <Stat label="Max drawdown" value={fmtInr(o.max_drawdown)} sub={fmtPct(o.max_drawdown_pct_of_capital, 2) + ' of capital'} />
        <Stat label="Avg win / avg loss" value={`${fmtInr(o.avg_win)} / ${fmtInr(o.avg_loss)}`} sub={`avg trade ${fmtInr(o.avg_trade)}`} />
      </div>
      {o.sample_size_warning && <div className="note warn">{o.sample_size_warning}</div>}
      <div className="grid two">
        <Card title="Costs & slippage">
          <div className="small">Gross P&L {fmtInr(c.gross_pnl, 2)} − costs {fmtInr(c.transaction_costs, 2)} = net {fmtInr(c.net_pnl, 2)}. Slippage already included in fills: {fmtInr(c.slippage_incurred_included_in_fill_prices, 2)}.</div>
        </Card>
        <Card title="Signals">
          <div className="small">{sg.evaluations_total} evaluations · {sg.candidates} candidates · <b>{sg.approved}</b> accepted · <b>{sg.rejected}</b> rejected · NO_TRADE {fmtPct((sg.no_trade_frequency ?? 0) * 100, 0)} of evaluations</div>
          <ul className="list small">{(sg.rejection_reasons_top ?? []).map((x: Rec, i: number) => <li key={i}>{x.n}× {x.reason}</li>)}</ul>
        </Card>
      </div>
      <Card title="By strategy"><StatsTable groups={r.by_strategy} /></Card>
      <Card title="By instrument"><StatsTable groups={r.by_instrument} /></Card>
      <Card title="By instrument type (equity vs index options)"><StatsTable groups={r.by_instrument_type} /></Card>
      <Card title="By market regime (trend / volatility at signal time)"><StatsTable groups={r.by_market_regime} /></Card>
      <Card title="AI analysis">
        <div className="small">Analysts: {(ai.analysts ?? []).map((a: Rec) => `${a.analyst} (${a.model}) ×${a.n}`).join(', ') || '—'} · agree {ai.agreement?.agree ?? 0} / disagree {ai.agreement?.disagree ?? 0} / no verdict {ai.agreement?.no_verdict ?? 0}</div>
        <div className="table-wrap"><table><thead><tr><th>Status</th><th>Count</th><th>Avg latency ms</th><th>Tokens in/out</th></tr></thead>
          <tbody>{(ai.by_status ?? []).map((s: Rec) => <tr key={s.status}><td>{s.status}</td><td>{s.n}</td><td>{fmtNum(s.avg_latency, 0)}</td><td>{s.in_tok}/{s.out_tok}</td></tr>)}</tbody></table></div>
        <div className="small muted">{ai.confidence_note}</div>
      </Card>
    </div>
  );
}

export default function Analytics({ bump }: { bump: number }) {
  const [runId, setRunId] = useState<string>('');
  const paperRuns = useApi<Rec[]>('/api/runs?mode=PAPER', 0, bump);
  const path = runId ? `/api/analytics/runs/${runId}` : '/api/analytics/paper';
  const { data, error } = useApi<Rec>(path, 20000, bump);
  return (
    <div className="stack">
      <ErrorNote error={error} />
      <Card title="Performance" right={
        <label className="inline">Run <select value={runId} onChange={(e) => setRunId(e.target.value)}>
          <option value="">current paper run</option>
          {(paperRuns.data ?? []).map((r) => <option key={r.id} value={r.id}>{r.name} · {r.ai_mode} · {String(r.started_at).slice(0, 10)}</option>)}
        </select></label>}>
        <p className="muted small">Statistics are per run and per mode — paper, backtest and (future) live results are never combined. Paper runs started with different AI modes are separate runs.</p>
      </Card>
      {data && <Report r={data} />}
    </div>
  );
}
