import { useEffect, useState } from 'react';
import { api, Rec } from '../api';
import { fmtInr, fmtNum } from '../format';
import { Badge, Card, DataTable, Empty, KV, Skeleton, SourceTag } from '../ui';
import { useStatus } from '../shell/StatusContext';

export default function Portfolio({ admin, bump }: { admin: boolean; bump: number }) {
  const { overview: o } = useStatus();
  const [live, setLive] = useState<{ funds?: Rec; positions?: Rec[]; orders?: Rec[]; error?: string } | null>(null);
  const connected = o?.data && !o.data.simulated && o.data.state === 'OK';
  useEffect(() => {
    if (!admin || !connected) { setLive(null); return; }
    let alive = true;
    Promise.all([api.get<Rec>('/api/broker/funds'), api.get<Rec[]>('/api/broker/positions'), api.get<Rec[]>('/api/broker/orders')])
      .then(([funds, positions, orders]) => alive && setLive({ funds, positions, orders }))
      .catch((e) => alive && setLive({ error: (e as Error).message }));
    return () => { alive = false; };
  }, [admin, connected, bump]);
  const cap = o?.capital;
  return (
    <div className="stack">
      <Card title="Groww account" right={<SourceTag kind="LIVE" />}>
        {!admin ? <div className="muted small">Administrator role required to read broker account data.</div>
          : !connected ? <Empty title="Groww is not connected" hint={<>No broker balance, holdings or orders are shown. Connect on <a href="#/broker">Broker Connection</a>.</>} />
          : !live ? <Skeleton lines={3} /> : live.error ? <div className="note bad" role="alert">Could not read account: {live.error}</div> : (
            <>
              <KV rows={[['Available cash', fmtInr(live.funds?.available_cash, 2)], ['Margin used', fmtInr(live.funds?.used_margin, 2)], ['Total balance', fmtInr(live.funds?.total_balance, 2)]]} />
              <h4>Broker positions</h4>
              <DataTable pageSize={10} empty="No open broker positions." rows={live.positions ?? []} cols={[
                { key: 'tradingSymbol', head: 'Symbol' }, { key: 'segment', head: 'Segment' }, { key: 'product', head: 'Product' }, { key: 'netQuantity', head: 'Net qty', num: true },
                { key: 'avgPrice', head: 'Avg price', num: true, render: (p) => fmtNum(p.avgPrice) }, { key: 'realizedPnl', head: 'Realised', num: true, render: (p) => fmtInr(p.realizedPnl, 2) }]} />
              <h4>Broker orders</h4>
              <DataTable pageSize={10} empty="No broker orders." rows={live.orders ?? []} cols={[
                { key: 'brokerOrderId', head: 'Broker order ID' }, { key: 'reference', head: 'Reference' }, { key: 'tradingSymbol', head: 'Symbol' }, { key: 'side', head: 'Side' },
                { key: 'quantity', head: 'Qty', num: true }, { key: 'filledQuantity', head: 'Filled', num: true }, { key: 'status', head: 'Status', render: (p) => <Badge>{p.status}</Badge> }]} />
            </>)}
      </Card>
      <Card title="Paper-trading account" right={<SourceTag kind="SIMULATION" />}>
        {cap ? <KV rows={[['Starting capital', fmtInr(cap.initial_simulated_capital)], ['Realised P&L (net)', fmtInr(cap.realized_pnl_total_net, 2)], ['Unrealised', fmtInr(cap.unrealized_pnl, 2)], ['Equity', fmtInr(cap.simulated_equity, 2)]]} /> : <Skeleton lines={3} />}
        <div className="muted small">Simulated capital — not related to your Groww balance.</div>
      </Card>
    </div>
  );
}
