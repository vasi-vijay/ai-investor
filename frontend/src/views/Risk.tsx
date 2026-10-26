import { FormEvent, useState } from 'react';
import { api, Rec } from '../api';
import { fmtTime } from '../format';
import { Badge, Card, ErrorNote, KV, useApi } from '../ui';

export default function Risk({ admin, bump }: { admin: boolean; bump: number }) {
  const ov = useApi<Rec>('/api/overview', 10000, bump);
  const limits = useApi<Rec>('/api/risk/limits');
  const events = useApi<Rec[]>('/api/risk/events?limit=80', 15000, bump);
  const [reason, setReason] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const engaged = !!ov.data?.kill_switch;
  const toggle = async (e: FormEvent) => {
    e.preventDefault(); setErr(null);
    try { await api.post('/api/risk/kill-switch', { engaged: !engaged, reason: reason || (engaged ? 'released from dashboard' : 'engaged from dashboard') }); setReason(''); ov.reload(); }
    catch (ex) { setErr((ex as Error).message); }
  };
  const today = ov.data?.today;
  return (
    <div className="stack">
      <ErrorNote error={err || limits.error} />
      <div className="grid two">
        <Card title="Emergency kill switch">
          <p className="small">When engaged, <b>no new order</b> is approved (paper or live). Open simulated positions keep being managed (stops/targets). The state is persistent and survives restarts.</p>
          <div>Status: <Badge tone={engaged ? 'bad' : 'ok'}>{engaged ? 'ENGAGED' : 'off'}</Badge></div>
          <form className="form" onSubmit={toggle}>
            <label>Reason<input value={reason} onChange={(e) => setReason(e.target.value)} maxLength={300} /></label>
            <button className={'btn ' + (engaged ? '' : 'danger')} disabled={!admin}>{engaged ? 'Release kill switch' : 'ENGAGE kill switch'}</button>
          </form>
          {!admin && <div className="note warn">Admin role required.</div>}
        </Card>
        <Card title="Daily risk lock">
          <KV rows={[['Locked today', today?.locked ? <Badge tone="bad">LOCKED</Badge> : <Badge tone="ok">no</Badge>], ['Reason', today?.lock_reason ?? '—'], ['Consecutive losses', today?.consecutive_losses ?? 0]]} />
          <p className="small muted">The daily-loss lock is stored in the database. It is never cleared by the application, so a restart cannot reset the loss limit. Neither AI output nor any API can change a limit.</p>
        </Card>
      </div>
      <div className="grid three">
        {['global', 'equity', 'options'].map((k) => (
          <Card key={k} title={`Limits · ${k}`}>
            <KV rows={Object.entries(limits.data?.[k] ?? {}).map(([a, b]) => [a, String(b)] as [string, string])} />
          </Card>
        ))}
      </div>
      <Card title="Recent risk events">
        <div className="table-wrap"><table><thead><tr><th>Time (IST)</th><th>Type</th><th>Check</th><th>Severity</th><th>Detail</th></tr></thead>
          <tbody>{(events.data ?? []).map((e) => <tr key={e.id}><td>{fmtTime(e.created_at)}</td><td className="small">{e.event_type}</td><td className="small">{e.check_name}</td><td><Badge tone={e.severity === 'CRITICAL' ? 'bad' : e.severity === 'WARN' ? 'warn' : 'muted'}>{e.severity}</Badge></td><td className="small reason">{e.detail}</td></tr>)}
            {events.data?.length === 0 && <tr><td colSpan={5} className="muted">No risk events.</td></tr>}</tbody></table></div>
      </Card>
    </div>
  );
}
