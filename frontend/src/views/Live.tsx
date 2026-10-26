import { useState } from 'react';
import { api, Rec } from '../api';
import { Badge, Card, ErrorNote, useApi } from '../ui';

export default function Live({ admin, bump }: { admin: boolean; bump: number }) {
  const st = useApi<Rec>('/api/live/status', 15000, bump);
  const orders = useApi<Rec[]>(admin ? '/api/live/orders' : null, 15000, bump);
  const [err, setErr] = useState<string | null>(null);
  const [note, setNote] = useState('');
  const [msg, setMsg] = useState<string | null>(null);
  const act = async (path: string, body?: unknown) => {
    setErr(null); setMsg(null);
    try { const r = await api.post<Rec>(path, body); setMsg(JSON.stringify(r).slice(0, 300)); st.reload(); orders.reload(); }
    catch (e) { setErr((e as Error).message); }
  };
  const enabled = !!st.data?.live_order_placement_enabled;
  return (
    <div className="stack">
      <div className={'banner ' + (enabled ? 'warn' : 'info')}>
        {enabled ? <><b>LIVE ACCOUNT scope.</b> Every order requires manual approval. Real money.</> : <><b>Live trading is DISABLED.</b> Paper trading remains available as a simulation. Live order placement requires every condition below to pass.</>}
      </div>
      <ErrorNote error={err || st.error} />
      {msg && <div className="note info small">{msg}</div>}
      <Card title="Live-trading gate (all must pass)" right={<Badge tone="muted">account scope: LIVE (separate from paper)</Badge>}>
        <ul className="checks">
          {(st.data?.gate ?? []).map((c: Rec) => <li key={c.name} className={c.ok ? 'pass' : 'fail'}><b>{c.ok ? '✓' : '✗'} {c.name}</b><span className="small"> {c.detail}</span></li>)}
        </ul>
        <div className="small muted">Order provider: {st.data?.order_provider} · manual approval of every order: {String(st.data?.manual_approval_required)} · pending approvals: {st.data?.pending_approvals}</div>
        {st.data?.live_funds && <div className="small">Live account funds (read-only): {JSON.stringify(st.data.live_funds)}</div>}
      </Card>
      <Card title="Operator actions (admin)">
        <p className="small muted">These are deliberate, audited steps. They do nothing unless <code>app.live.enabled=true</code> is set in configuration and Groww credentials are present. See docs/LIVE_TRADING.md and docs/COMPLIANCE.md before attempting.</p>
        <div className="row">
          <button className="btn" disabled={!admin} onClick={() => act('/api/live/probe-capabilities')}>Probe broker capabilities (read-only)</button>
          <button className="btn" disabled={!admin} onClick={() => { if (confirm('Authorise live trading? Orders will still require your approval one by one.')) act('/api/live/authorize', { confirmation: window.prompt('Type the exact phrase: I AUTHORIZE LIVE TRADING WITH REAL MONEY') ?? '' }); }}>Authorise live…</button>
          <button className="btn danger" disabled={!admin} onClick={() => act('/api/live/revoke')}>Revoke authorisation</button>
        </div>
        <div className="form"><label>Compliance attestation (who verified which SEBI/NSE/Groww requirements, when)
          <input value={note} onChange={(e) => setNote(e.target.value)} maxLength={500} /></label>
          <button className="btn" disabled={!admin || note.length < 20} onClick={() => act('/api/live/compliance-attestation', { note })}>Record attestation</button></div>
      </Card>
      {admin && (
        <Card title="Live order approvals">
          <div className="table-wrap"><table><thead><tr><th>Proposed</th><th>Instrument</th><th>Side</th><th>Qty</th><th>Value</th><th>Status</th><th>Note</th><th /></tr></thead>
            <tbody>{(orders.data ?? []).map((o) => (
              <tr key={o.id}><td className="small">{String(o.proposed_at).slice(0, 19)}</td><td>{o.instrument_symbol}</td><td>{o.side}</td><td>{o.quantity}</td><td>{o.est_order_value}</td><td><Badge tone={o.status === 'SUBMITTED' ? 'ok' : o.status === 'PENDING_APPROVAL' ? 'info' : 'warn'}>{o.status}</Badge></td><td className="small reason">{o.note}</td>
                <td>{o.status === 'PENDING_APPROVAL' && <><button className="btn small" onClick={() => act(`/api/live/orders/${o.id}/approve`)}>Approve</button> <button className="btn small" onClick={() => act(`/api/live/orders/${o.id}/reject`, { note: 'rejected from dashboard' })}>Reject</button></>}
                  {o.status === 'UNKNOWN_RECONCILE' && <button className="btn small" onClick={() => act(`/api/live/orders/${o.id}/reconcile`)}>Reconcile with broker</button>}</td></tr>))}
              {orders.data?.length === 0 && <tr><td colSpan={8} className="muted">No live order proposals.</td></tr>}</tbody></table></div>
        </Card>
      )}
    </div>
  );
}
