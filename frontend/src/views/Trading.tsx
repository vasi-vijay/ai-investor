import { useState } from 'react';
import { Rec } from '../api';
import { fmtInr, fmtNum, fmtTime, signClass } from '../format';
import { Badge, Card, ErrorNote, SimTag, Tone, useApi } from '../ui';

const orderTone = (s: string): Tone => (s === 'FILLED' ? 'ok' : s === 'PENDING' || s === 'PARTIAL' ? 'info' : s === 'REJECTED' ? 'bad' : 'muted');

export default function Trading({ bump }: { bump: number }) {
  const [tab, setTab] = useState<'open' | 'closed' | 'orders' | 'fills'>('open');
  const open = useApi<Rec[]>('/api/positions?status=OPEN', 10000, bump);
  const closed = useApi<Rec[]>(tab === 'closed' ? '/api/positions?status=CLOSED&limit=200' : null, 0, bump);
  const orders = useApi<Rec[]>(tab === 'orders' ? '/api/orders?limit=200' : null, 0, bump);
  const fills = useApi<Rec[]>(tab === 'fills' ? '/api/executions?limit=200' : null, 0, bump);
  return (
    <div className="stack">
      <div className="banner info"><SimTag /> Fill prices are <b>simulated</b> (observed market price ± configured slippage, partial fills by volume participation). They are never Groww executions.</div>
      <div className="seg">
        {(['open', 'closed', 'orders', 'fills'] as const).map((t) => <button key={t} className={t === tab ? 'tab active' : 'tab'} onClick={() => setTab(t)}>{{ open: 'Open positions', closed: 'Closed trades', orders: 'Orders', fills: 'Executions' }[t]}</button>)}
      </div>
      <ErrorNote error={open.error || closed.error || orders.error || fills.error} />
      {tab === 'open' && (
        <Card title="Open paper positions">
          <Table head={['Instrument', 'Strategy', 'Side', 'Qty', 'Entry', 'Mark', 'Stop', 'Target', 'Unrealised', 'Opened (IST)']}
            rows={(open.data ?? []).map((p) => [
              <>{p.instrument_symbol}{p.instrument_type === 'OPTION' && <Badge tone="info">option</Badge>}</>, <span className="small">{p.strategy_name}</span>, p.side, p.open_quantity,
              fmtNum(p.avg_entry_price), p.mark_price != null ? fmtNum(p.mark_price) : '—', fmtNum(p.stop_loss), fmtNum(p.target_price),
              <span className={signClass(p.unrealized_pnl)}>{p.unrealized_pnl != null ? fmtInr(p.unrealized_pnl) : '—'}</span>, fmtTime(p.opened_at)])}
            empty="No open positions." />
        </Card>
      )}
      {tab === 'closed' && (
        <Card title="Closed paper trades (net of simulated costs)">
          <Table head={['Instrument', 'Strategy', 'Side', 'Qty', 'Entry', 'Exit', 'Exit reason', 'Gross', 'Costs', 'Net', 'Closed (IST)']}
            rows={(closed.data ?? []).map((p) => [p.instrument_symbol, <span className="small">{p.strategy_name}</span>, p.side, p.quantity, fmtNum(p.avg_entry_price), fmtNum(p.avg_exit_price), p.exit_reason,
              fmtInr(p.realized_pnl_gross, 2), fmtInr(p.total_costs, 2), <b className={signClass(p.realized_pnl_net)}>{fmtInr(p.realized_pnl_net, 2)}</b>, fmtTime(p.closed_at)])}
            empty="No closed trades yet." />
        </Card>
      )}
      {tab === 'orders' && (
        <Card title="Simulated orders">
          <Table head={['Created (IST)', 'Instrument', 'Purpose', 'Side', 'Type', 'Qty', 'Filled', 'Avg fill', 'Status', 'Note']}
            rows={(orders.data ?? []).map((o) => [fmtTime(o.created_at), o.instrument_symbol, o.purpose, o.side, o.order_type, o.quantity, o.filled_quantity, o.avg_fill_price != null ? fmtNum(o.avg_fill_price) : '—',
              <Badge tone={orderTone(o.status)}>{o.status}</Badge>, <span className="small muted">{o.reject_reason ?? ''}</span>])}
            empty="No orders." />
        </Card>
      )}
      {tab === 'fills' && (
        <Card title="Simulated executions: observed market price vs simulated fill">
          <Table head={['Time (IST)', 'Instrument', 'Side', 'Qty', 'Market price (observed)', 'Fill price (simulated)', 'Slippage ₹', 'Costs ₹']}
            rows={(fills.data ?? []).map((e) => [fmtTime(e.executed_at), e.instrument_symbol, e.side, e.quantity, fmtNum(e.market_price), fmtNum(e.fill_price), fmtNum(e.slippage_amount), fmtNum(e.total_costs, 2)])}
            empty="No executions." />
        </Card>
      )}
    </div>
  );
}

function Table({ head, rows, empty }: { head: string[]; rows: React.ReactNode[][]; empty: string }) {
  return (
    <div className="table-wrap">
      <table>
        <thead><tr>{head.map((h) => <th key={h}>{h}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j}>{c}</td>)}</tr>)}
          {rows.length === 0 && <tr><td colSpan={head.length} className="muted">{empty}</td></tr>}
        </tbody>
      </table>
    </div>
  );
}
