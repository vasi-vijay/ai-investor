import { useState } from 'react';
import { Rec } from '../api';
import { fmtInr, fmtNum, fmtTime, signClass } from '../format';
import { Badge, Card, Col, DataTable, ErrorNote, Skeleton, SimTag, Tone, useApi } from '../ui';
import { useStatus } from '../shell/StatusContext';

const orderTone = (s: string): Tone => (s === 'FILLED' ? 'ok' : s === 'PENDING' || s === 'PARTIAL' ? 'info' : s === 'REJECTED' ? 'bad' : 'muted');

export default function Trading({ section, bump }: { section: 'orders' | 'positions'; bump: number }) {
  return section === 'orders' ? <Orders bump={bump} /> : <Positions bump={bump} />;
}

const SourceNote = () => {
  const { overview } = useStatus();
  return <div className="banner info"><SimTag /> These records are <b>paper-trading simulations</b>: fill prices are the observed market price ± configured slippage. {overview?.data?.simulated ? 'Market prices are also simulated. ' : ''}Live broker orders appear only on Portfolio (broker) and the live approval queue after live trading is separately enabled.</div>;
};

function Orders({ bump }: { bump: number }) {
  const [tab, setTab] = useState<'open' | 'completed' | 'rejected' | 'cancelled' | 'fills'>('open');
  const orders = useApi<Rec[]>('/api/orders?limit=500', 15000, bump);
  const fills = useApi<Rec[]>(tab === 'fills' ? '/api/executions?limit=500' : null, 0, bump);
  const sel = (st: string[]) => (orders.data ?? []).filter((o) => st.includes(o.status));
  const groups = { open: sel(['PENDING', 'PARTIAL', 'NEW', 'OPEN']), completed: sel(['FILLED']), rejected: sel(['REJECTED']), cancelled: sel(['CANCELLED', 'EXPIRED']) };
  const cols: Col<Rec>[] = [
    { key: 'created_at', head: 'Created (IST)', render: (o) => fmtTime(o.created_at), sort: (o) => o.created_at },
    { key: 'id', head: 'Correlation ID', render: (o) => <code className="small">{String(o.client_order_id ?? o.id).slice(0, 14)}</code>, text: (o) => String(o.client_order_id ?? o.id) },
    { key: 'broker_order_id', head: 'Broker order ID', render: (o) => o.broker_order_id ?? <span className="muted" title="Paper orders have no broker ID">— (paper)</span>, text: (o) => o.broker_order_id ?? '' },
    { key: 'instrument_symbol', head: 'Instrument' }, { key: 'purpose', head: 'Purpose' }, { key: 'side', head: 'Side' }, { key: 'order_type', head: 'Type' },
    { key: 'quantity', head: 'Qty', num: true }, { key: 'filled_quantity', head: 'Filled', num: true },
    { key: 'price', head: 'Price', num: true, render: (o) => (o.price != null ? fmtNum(o.price) : o.avg_fill_price != null ? fmtNum(o.avg_fill_price) : '—'), sort: (o) => o.price ?? o.avg_fill_price },
    { key: 'status', head: 'Status', render: (o) => <Badge tone={orderTone(o.status)}>{o.status}</Badge> },
    { key: 'source', head: 'Source', render: () => <SimTag />, text: () => 'simulated' },
    { key: 'reject_reason', head: 'Note', render: (o) => <span className="small muted">{o.reject_reason ?? ''}</span> },
  ];
  const fcols: Col<Rec>[] = [
    { key: 'executed_at', head: 'Time (IST)', render: (e) => fmtTime(e.executed_at), sort: (e) => e.executed_at }, { key: 'instrument_symbol', head: 'Instrument' }, { key: 'side', head: 'Side' },
    { key: 'quantity', head: 'Qty', num: true }, { key: 'market_price', head: 'Observed price', num: true, render: (e) => fmtNum(e.market_price) },
    { key: 'fill_price', head: 'Fill (simulated)', num: true, render: (e) => fmtNum(e.fill_price) }, { key: 'slippage_amount', head: 'Slippage ₹', num: true, render: (e) => fmtNum(e.slippage_amount) },
    { key: 'total_costs', head: 'Costs ₹', num: true, render: (e) => fmtNum(e.total_costs, 2) },
  ];
  const labels = { open: 'Open orders', completed: 'Completed', rejected: 'Rejected', cancelled: 'Cancelled', fills: 'Execution details' };
  return (
    <div className="stack">
      <SourceNote />
      <div className="seg" role="tablist">{(Object.keys(labels) as (keyof typeof labels)[]).map((t) => (
        <button key={t} role="tab" aria-selected={t === tab} className={t === tab ? 'tab active' : 'tab'} onClick={() => setTab(t)}>{labels[t]}{t !== 'fills' && orders.data ? ` (${groups[t].length})` : ''}</button>))}</div>
      <ErrorNote error={orders.error || fills.error} />
      <Card title={labels[tab]}>
        {tab === 'fills' ? (fills.data ? <DataTable cols={fcols} rows={fills.data} empty="No executions." /> : <Skeleton />)
          : orders.data ? <DataTable cols={cols} rows={groups[tab]} empty={`No ${labels[tab].toLowerCase()} orders.`} /> : <Skeleton />}
      </Card>
    </div>
  );
}

function Positions({ bump }: { bump: number }) {
  const [tab, setTab] = useState<'open' | 'history'>('open');
  const open = useApi<Rec[]>('/api/positions?status=OPEN', 10000, bump);
  const closed = useApi<Rec[]>(tab === 'history' ? '/api/positions?status=CLOSED&limit=500' : null, 0, bump);
  const ocols: Col<Rec>[] = [
    { key: 'instrument_symbol', head: 'Instrument', render: (p) => <>{p.instrument_symbol}{p.instrument_type === 'OPTION' && <Badge tone="info">option</Badge>}</>, text: (p) => p.instrument_symbol },
    { key: 'strategy_name', head: 'Strategy' }, { key: 'side', head: 'Side' }, { key: 'open_quantity', head: 'Qty', num: true },
    { key: 'avg_entry_price', head: 'Entry', num: true, render: (p) => fmtNum(p.avg_entry_price) }, { key: 'mark_price', head: 'Mark', num: true, render: (p) => (p.mark_price != null ? fmtNum(p.mark_price) : '—') },
    { key: 'stop_loss', head: 'Stop', num: true, render: (p) => fmtNum(p.stop_loss) }, { key: 'target_price', head: 'Target', num: true, render: (p) => fmtNum(p.target_price) },
    { key: 'unrealized_pnl', head: 'Unrealised', num: true, render: (p) => <span className={signClass(p.unrealized_pnl)}>{p.unrealized_pnl != null ? fmtInr(p.unrealized_pnl) : '—'}</span> },
    { key: 'opened_at', head: 'Opened (IST)', render: (p) => fmtTime(p.opened_at), sort: (p) => p.opened_at }, { key: 'src', head: 'Source', render: () => <SimTag />, text: () => 'simulated' },
  ];
  const ccols: Col<Rec>[] = [
    { key: 'instrument_symbol', head: 'Instrument' }, { key: 'strategy_name', head: 'Strategy' }, { key: 'side', head: 'Side' }, { key: 'quantity', head: 'Qty', num: true },
    { key: 'avg_entry_price', head: 'Entry', num: true, render: (p) => fmtNum(p.avg_entry_price) }, { key: 'avg_exit_price', head: 'Exit', num: true, render: (p) => fmtNum(p.avg_exit_price) },
    { key: 'exit_reason', head: 'Exit reason' }, { key: 'realized_pnl_gross', head: 'Gross', num: true, render: (p) => fmtInr(p.realized_pnl_gross, 2) },
    { key: 'total_costs', head: 'Costs', num: true, render: (p) => fmtInr(p.total_costs, 2) },
    { key: 'realized_pnl_net', head: 'Net', num: true, render: (p) => <b className={signClass(p.realized_pnl_net)}>{fmtInr(p.realized_pnl_net, 2)}</b> },
    { key: 'closed_at', head: 'Closed (IST)', render: (p) => fmtTime(p.closed_at), sort: (p) => p.closed_at },
  ];
  return (
    <div className="stack">
      <SourceNote />
      <div className="seg" role="tablist">
        <button role="tab" aria-selected={tab === 'open'} className={tab === 'open' ? 'tab active' : 'tab'} onClick={() => setTab('open')}>Open positions</button>
        <button role="tab" aria-selected={tab === 'history'} className={tab === 'history' ? 'tab active' : 'tab'} onClick={() => setTab('history')}>Trade history</button>
      </div>
      <ErrorNote error={open.error || closed.error} />
      <Card title={tab === 'open' ? 'Open paper positions' : 'Closed paper trades (net of simulated costs)'}>
        {tab === 'open' ? (open.data ? <DataTable cols={ocols} rows={open.data} empty="No open positions." /> : <Skeleton />)
          : closed.data ? <DataTable cols={ccols} rows={closed.data} empty="No closed trades yet." /> : <Skeleton />}
      </Card>
    </div>
  );
}
