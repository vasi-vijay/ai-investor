import { Rec } from '../api';
import { fmtInr, fmtNum, fmtPct, fmtTime, signClass } from '../format';
import { Badge, Card, DataTable, Empty, KV, LineChart, Meter, Skeleton, SourceTag, Stat, Tone, useApi } from '../ui';
import { OP_STATES, OpState, warnings } from '../shell/status';
import { useStatus } from '../shell/StatusContext';

const integTone = (s: string): Tone => (['OK', 'OPEN'].includes(s) ? 'ok' : ['NOT_USED', 'DISABLED', 'CONFIGURED_NO_CALLS_YET', 'NOT_CONFIGURED', 'DISCONNECTED'].includes(s) ? 'muted' : ['MOCK_STAND_IN', 'SIMULATED'].includes(s) || s?.startsWith('CLOSED') || s === 'PRE_OPEN' ? 'info' : s === 'DEGRADED' || s === 'AUTH_EXPIRED' ? 'warn' : 'bad');

export default function Overview({ bump }: { bump: number }) {
  const { overview: o, error } = useStatus();
  const daily = useApi<Rec[]>('/api/analytics/daily', 30000, bump);
  const signals = useApi<Rec[]>('/api/signals?limit=60', 20000, bump);
  const fills = useApi<Rec[]>('/api/executions?limit=8', 20000, bump);
  const orders = useApi<Rec[]>('/api/orders?limit=200', 20000, bump);
  const analytics = useApi<Rec>('/api/analytics/paper', 30000, bump);
  if (!o) return error ? <div className="note bad" role="alert">⚠ {error}</div> : <Skeleton lines={6} />;
  const cap = o.capital ?? {}, today = o.today ?? {}, ru = o.risk_utilization ?? {}, data = o.data ?? {}, cl = o.claude ?? {};
  const op = OP_STATES[(o.operating_state as OpState) ?? 'DISCONNECTED'];
  let cum = 0;
  const curve = [0, ...(daily.data ?? []).map((d) => (cum += Number(d.realized_pnl_net)))];
  const rejected = (signals.data ?? []).filter((s) => s.final_status === 'REJECTED').slice(0, 6);
  const recent = (signals.data ?? []).filter((s) => s.rule_decision === 'BUY_CANDIDATE' || s.rule_decision === 'SELL_CANDIDATE').slice(0, 6);
  const openOrders = orders.data ? orders.data.filter((x) => ['PENDING', 'PARTIAL', 'NEW', 'OPEN'].includes(x.status)).length : null;
  const warn = warnings(o);
  return (
    <div className="stack">
      <div className={'banner ' + (op.tone === 'bad' ? 'bad' : op.tone === 'warn' ? 'warn' : 'info')} role="status">
        <b>{op.glyph} {op.label}.</b> {op.help} {data.simulated && <b> Prices are simulated.</b>}
      </div>
      {o.engine_blocked_reason && <div className="note warn" role="alert"><b>Signals are not being generated:</b> {o.engine_blocked_reason}. Open <a href="#/broker">Broker Connection</a> to resolve this.</div>}
      <div className="grid stats">
        <Stat label="Groww / market data" value={<Badge tone={integTone(data.state)}>{data.simulated ? 'SIMULATED' : data.state}</Badge>} sub={`${data.mechanism} · ${data.detail ?? ''}`} />
        <Stat label="Claude API" value={<Badge tone={integTone(cl.status)}>{cl.status}</Badge>} sub={`${cl.analyst} · ${cl.model}`} />
        <Stat label="Market session" value={<Badge tone={integTone(o.market_session)}>{o.market_session}</Badge>} sub={o.clock?.now_local} />
        <Stat label="Data freshness" value={o.data_freshness?.seconds_since_last_cycle != null ? `${o.data_freshness.seconds_since_last_cycle}s` : '—'} sub="since last engine cycle" />
        <Stat label="Available funds" value={<a href="#/portfolio">Portfolio</a>} sub={<><SourceTag kind="LIVE" /> shown only when Groww verifies it</>} />
        <Stat label="Kill switch" value={<Badge tone={o.kill_switch ? 'bad' : 'ok'}>{o.kill_switch ? '■ ENGAGED' : '✓ off'}</Badge>} sub={`state store: ${o.transient_store}`} />
      </div>
      <div className="grid stats">
        <Stat label={<>Today's P&L (net) <SourceTag kind="SIMULATION" /></>} value={fmtInr(today.total_pnl_net, 0)} tone={signClass(today.total_pnl_net)} sub={`realised ${fmtInr(today.realized_pnl_net)} · unrealised ${fmtInr(today.unrealized_pnl)}`} />
        <Stat label={<>Est. trading costs <SourceTag kind="SIMULATION" /></>} value={fmtInr(today.trading_costs, 0)} sub={`${today.closed_trades} closed · ${today.wins}W/${today.losses}L`} />
        <Stat label="Open positions" value={ru.open_positions ?? 0} sub={`max ${ru.open_positions_max}`} />
        <Stat label="Open orders" value={openOrders ?? '—'} sub={<a href="#/orders">Orders</a>} />
        <Stat label="Daily loss limit" value={fmtInr(ru.daily_loss_limit)} sub={today.locked ? <Badge tone="bad">■ LOCKED</Badge> : `used ${fmtInr(ru.daily_loss_used)}`} />
        <Stat label={<>Paper equity <SourceTag kind="SIMULATION" /></>} value={fmtInr(cap.simulated_equity)} tone={signClass(cap.simulated_equity - cap.initial_simulated_capital)} sub={`start ${fmtInr(cap.initial_simulated_capital)}`} />
      </div>
      {today.locked && <div className="note bad">Trading is locked for today: {today.lock_reason}. The lock survives restarts.</div>}
      <div className="grid two">
        <Card title="Risk utilisation">
          <Meter pct={ru.daily_loss_used_pct} label="Daily loss limit used" />
          <Meter pct={(ru.trades_used / ru.trades_max) * 100} label={`Trades today (${ru.trades_used}/${ru.trades_max})`} />
          <Meter pct={(ru.open_positions / ru.open_positions_max) * 100} label="Open positions" />
          <Meter pct={ru.capital_allocation_used_pct} label="Capital allocation" />
        </Card>
        <Card title="Cumulative realised P&L by day" right={<SourceTag kind="SIMULATION" />}>
          <LineChart points={curve} label="cumulative realised P&L" />
          <KV rows={[['Realised total (net)', <span className={signClass(cap.realized_pnl_total_net)}>{fmtInr(cap.realized_pnl_total_net, 2)}</span>], ['Unrealised', fmtNum(cap.unrealized_pnl)]]} />
          <div className="muted small">Paper-trading results. Live account performance is never mixed into this series.</div>
        </Card>
      </div>
      <div className="grid two">
        <Card title="Recent AI decisions" right={<a className="small" href="#/ai">all</a>}>
          <DataTable filterable={false} pageSize={6} empty="No candidate decisions yet." rows={recent} cols={[
            { key: 'bar_time', head: 'Bar (IST)', render: (r) => fmtTime(r.bar_time) }, { key: 'instrument_symbol', head: 'Instrument' },
            { key: 'rule_decision', head: 'Rules', render: (r) => <Badge tone="info">{r.rule_decision === 'BUY_CANDIDATE' ? 'LONG' : 'SHORT'}</Badge> },
            { key: 'ai_decision', head: 'AI', render: (r) => r.ai_decision ? <Badge tone={r.ai_agrees === true ? 'ok' : r.ai_agrees === false ? 'bad' : 'muted'}>{r.ai_decision}</Badge> : <span className="muted">—</span> },
            { key: 'final_status', head: 'Outcome' }]} />
        </Card>
        <Card title="Recent rejected trades" right={<a className="small" href="#/audit">audit</a>}>
          <DataTable filterable={false} pageSize={6} empty="No rejected trades." rows={rejected} cols={[
            { key: 'bar_time', head: 'Bar (IST)', render: (r) => fmtTime(r.bar_time) }, { key: 'instrument_symbol', head: 'Instrument' },
            { key: 'rejection_reason', head: 'Reason', render: (r) => <span className="small">{r.rejection_reason ?? '—'}</span> }]} />
        </Card>
      </div>
      <div className="grid two">
        <Card title="Recent execution events" right={<SourceTag kind="SIMULATION" />}>
          <DataTable filterable={false} pageSize={8} empty="No executions yet." rows={fills.data ?? []} cols={[
            { key: 'executed_at', head: 'Time (IST)', render: (r) => fmtTime(r.executed_at) }, { key: 'instrument_symbol', head: 'Instrument' }, { key: 'side', head: 'Side' },
            { key: 'quantity', head: 'Qty', num: true }, { key: 'fill_price', head: 'Fill (sim)', num: true, render: (r) => fmtNum(r.fill_price) }]} />
        </Card>
        <Card title="Strategy performance (paper)" right={<SourceTag kind="SIMULATION" />}>
          {analytics.data ? <StrategyMini groups={analytics.data.by_strategy} /> : <Skeleton lines={3} />}
        </Card>
      </div>
      <Card title="System health & integration warnings">
        {warn.length === 0 ? <Empty title="No active warnings" /> : warn.map((w, i) => <div key={i} className={'note ' + (w.level === 'info' ? 'info' : w.level === 'warn' ? 'warn' : 'bad')}>{w.text}</div>)}
      </Card>
    </div>
  );
}

function StrategyMini({ groups }: { groups: Rec }) {
  const rows = Object.entries(groups ?? {}).map(([k, s]: [string, any]) => ({ k, ...s })); // eslint-disable-line @typescript-eslint/no-explicit-any
  return <DataTable filterable={false} pageSize={6} empty="No closed trades yet." rows={rows} cols={[
    { key: 'k', head: 'Strategy' }, { key: 'trade_count', head: 'Trades', num: true },
    { key: 'net_pnl', head: 'Net P&L', num: true, render: (r) => <span className={signClass(r.net_pnl)}>{fmtInr(r.net_pnl)}</span> },
    { key: 'win_rate', head: 'Win rate', num: true, render: (r) => (r.win_rate != null ? fmtPct(r.win_rate * 100, 0) : '—') }]} />;
}
