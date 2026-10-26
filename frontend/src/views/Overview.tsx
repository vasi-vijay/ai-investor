import { Rec } from '../api';
import { fmtInr, fmtNum, signClass } from '../format';
import { Badge, Card, ErrorNote, KV, LineChart, Meter, Stat, Tone, useApi } from '../ui';

const statusTone = (s: string): Tone => (['OK', 'OPEN'].includes(s) ? 'ok' : ['NOT_USED', 'MOCK_STAND_IN', 'DISABLED', 'CONFIGURED_NO_CALLS_YET'].includes(s) ? 'muted' : s?.startsWith('CLOSED') || s === 'PRE_OPEN' ? 'info' : 'bad');

export default function Overview({ bump }: { bump: number }) {
  const { data: o, error } = useApi<Rec>('/api/overview', 10000, bump);
  const { data: daily } = useApi<Rec[]>('/api/analytics/daily', 30000, bump);
  if (!o) return <><ErrorNote error={error} /><div className="muted">Loading…</div></>;
  const cap = o.capital ?? {}, today = o.today ?? {}, ru = o.risk_utilization ?? {}, run = o.paper_run;
  let cum = 0;
  const curve = [0, ...(daily ?? []).map((d) => (cum += Number(d.realized_pnl_net)))];
  return (
    <div className="stack">
      <ErrorNote error={error} />
      <div className="banner info">
        <b>PAPER MODE</b> — every order and fill below is simulated; no real order is ever sent. {o.mode_note}
        {o.clock?.mode === 'REPLAY_SIMULATED_TIME' && <> · <b>Replay clock</b> (synthetic days stepping faster than real time)</>}
      </div>
      <div className="grid stats">
        <Stat label="Mode" value={<Badge tone="info">PAPER</Badge>} sub={run ? `AI mode ${run.ai_mode}` : ''} />
        <Stat label="Market session" value={<Badge tone={statusTone(o.market_session)}>{o.market_session}</Badge>} sub={o.clock?.now_local} />
        <Stat label="Groww" value={<Badge tone={statusTone(o.groww?.status)}>{o.groww?.status}</Badge>} sub={o.groww?.detail} />
        <Stat label="Claude / AI" value={<Badge tone={statusTone(o.claude?.status)}>{o.claude?.status}</Badge>} sub={`${o.claude?.analyst} · ${o.claude?.model}`} />
        <Stat label="Data source" value={o.data_source} sub={o.data_freshness?.seconds_since_last_cycle != null ? `last cycle ${o.data_freshness.seconds_since_last_cycle}s ago` : 'no cycle yet'} />
        <Stat label="Kill switch" value={<Badge tone={o.kill_switch ? 'bad' : 'ok'}>{o.kill_switch ? 'ENGAGED' : 'off'}</Badge>} sub={`state: ${o.transient_store}`} />
      </div>
      {o.data_freshness?.last_error && <div className="note bad">Last cycle error: {o.data_freshness.last_error}</div>}
      <div className="grid stats">
        <Stat label="Simulated capital" value={fmtInr(cap.initial_simulated_capital)} />
        <Stat label="Simulated equity" value={fmtInr(cap.simulated_equity)} tone={signClass(cap.simulated_equity - cap.initial_simulated_capital)} />
        <Stat label="Today's P&L (net)" value={fmtInr(today.total_pnl_net, 0)} tone={signClass(today.total_pnl_net)} sub={`realised ${fmtInr(today.realized_pnl_net)} · unrealised ${fmtInr(today.unrealized_pnl)}`} />
        <Stat label="Trading costs today" value={fmtInr(today.trading_costs, 0)} sub={`${today.closed_trades} closed · ${today.wins}W/${today.losses}L`} />
        <Stat label="Open paper positions" value={ru.open_positions ?? 0} sub={`max ${ru.open_positions_max}`} />
        <Stat label="Daily loss limit" value={fmtInr(ru.daily_loss_limit)} sub={today.locked ? <Badge tone="bad">LOCKED</Badge> : `used ${fmtInr(ru.daily_loss_used)}`} />
      </div>
      {today.locked && <div className="note bad">Trading is locked for today: {today.lock_reason}. The lock survives restarts.</div>}
      <div className="grid two">
        <Card title="Risk utilisation">
          <Meter pct={ru.daily_loss_used_pct} label="Daily loss limit used" />
          <Meter pct={(ru.trades_used / ru.trades_max) * 100} label={`Trades today (${ru.trades_used}/${ru.trades_max})`} />
          <Meter pct={(ru.open_positions / ru.open_positions_max) * 100} label="Open positions" />
          <Meter pct={ru.capital_allocation_used_pct} label="Capital allocation" />
        </Card>
        <Card title="Cumulative realised P&L by day (simulated, net of costs)">
          <LineChart points={curve} label="cumulative realised P&L" />
          <KV rows={[['Realised total (net)', <span className={signClass(cap.realized_pnl_total_net)}>{fmtInr(cap.realized_pnl_total_net, 2)}</span>], ['Unrealised', fmtNum(cap.unrealized_pnl)]]} />
        </Card>
      </div>
    </div>
  );
}
