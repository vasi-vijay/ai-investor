import { useState } from 'react';
import { Rec } from '../api';
import { fmtTime } from '../format';
import { Badge, Card, DataTable, ErrorNote, Skeleton, useApi } from '../ui';

export default function Audit({ admin }: { admin: boolean }) {
  const [tab, setTab] = useState<'decisions' | 'system'>('decisions');
  const sig = useApi<Rec[]>(tab === 'decisions' ? '/api/signals?limit=500&includeNoTrade=true' : null, 20000);
  const sys = useApi<Rec[]>(tab === 'system' && admin ? '/api/audit?limit=500' : null, 20000);
  const risk = useApi<Rec[]>(tab === 'system' ? '/api/risk/events?limit=300' : null, 20000);
  return (
    <div className="stack">
      <div className="note info">The audit trail is append-only: every strategy evaluation, AI verdict, risk decision, order and operator action is recorded and survives restarts. Nothing here is edited or deleted by the application.</div>
      <div className="seg" role="tablist">
        <button role="tab" aria-selected={tab === 'decisions'} className={tab === 'decisions' ? 'tab active' : 'tab'} onClick={() => setTab('decisions')}>Decisions</button>
        <button role="tab" aria-selected={tab === 'system'} className={tab === 'system' ? 'tab active' : 'tab'} onClick={() => setTab('system')}>System & risk events</button>
      </div>
      <ErrorNote error={sig.error || sys.error || risk.error} />
      {tab === 'decisions' && <Card title="Every evaluation (incl. NO_TRADE)">{!sig.data ? <Skeleton /> : <DataTable rows={sig.data} empty="No evaluations yet." cols={[
        { key: 'bar_time', head: 'Bar (IST)', render: (r) => fmtTime(r.bar_time), sort: (r) => r.bar_time }, { key: 'instrument_symbol', head: 'Instrument' }, { key: 'strategy_name', head: 'Strategy' },
        { key: 'rule_decision', head: 'Rules' }, { key: 'ai_decision', head: 'AI', render: (r) => r.ai_decision ?? '—' }, { key: 'risk_decision', head: 'Risk', render: (r) => r.risk_decision ?? '—' },
        { key: 'final_status', head: 'Outcome', render: (r) => <Badge tone={r.final_status === 'APPROVED' ? 'ok' : r.final_status === 'REJECTED' ? 'warn' : 'muted'}>{r.final_status}</Badge> },
        { key: 'rejection_reason', head: 'Reason', render: (r) => <span className="small">{r.rejection_reason ?? ''}</span> }]} />}</Card>}
      {tab === 'system' && (
        <>
          <Card title="Operator & system log">{!admin ? <div className="muted small">Administrator role required.</div> : !sys.data ? <Skeleton /> : <DataTable rows={sys.data} empty="No entries." cols={[
            { key: 'created_at', head: 'Time (IST)', render: (r) => fmtTime(r.created_at), sort: (r) => r.created_at }, { key: 'actor', head: 'Actor' }, { key: 'action', head: 'Action' },
            { key: 'entity_type', head: 'Entity' }, { key: 'detail', head: 'Detail', render: (r) => <span className="small">{r.detail}</span> }]} />}</Card>
          <Card title="Risk events">{!risk.data ? <Skeleton /> : <DataTable rows={risk.data} empty="No risk events." cols={[
            { key: 'created_at', head: 'Time (IST)', render: (r) => fmtTime(r.created_at), sort: (r) => r.created_at }, { key: 'event_type', head: 'Type' }, { key: 'check_name', head: 'Check' },
            { key: 'severity', head: 'Severity' }, { key: 'detail', head: 'Detail', render: (r) => <span className="small">{r.detail}</span> }]} />}</Card>
        </>)}
    </div>
  );
}
