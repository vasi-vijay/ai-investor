import { useState } from 'react';
import { api, Rec } from '../api';
import { fmtInr, fmtTime } from '../format';
import { Badge, Card, ConfirmButton, ErrorNote, KV, Skeleton, SourceTag, Tone, useApi } from '../ui';
import { OP_STATES, OpState } from '../shell/status';
import { useStatus } from '../shell/StatusContext';
import Live from './Live';

const capTone = (s: string): Tone => (s === 'OK' ? 'ok' : s === 'NO_DATA' ? 'warn' : s === 'UNAVAILABLE' ? 'muted' : 'bad');
const CAPS: Record<string, string> = { instrument_master: 'Instrument master', quotes: 'Live quotes', historical_candles: 'Historical candles', funds: 'Funds & margin', positions: 'Positions', orders: 'Order book' };

export default function Broker({ admin, bump }: { admin: boolean; bump: number }) {
  const st = useApi<Rec>('/api/broker/status', 15000, bump);
  const { reload: reloadShell } = useStatus();
  const [tab, setTab] = useState<'connection' | 'live'>('connection');
  const [busy, setBusy] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [funds, setFunds] = useState<Rec | null>(null);
  const [fundsErr, setFundsErr] = useState<string | null>(null);

  const act = async (name: string, path: string) => {
    setBusy(name); setErr(null);
    try {
      await api.post(path);
      if (name === 'verify' || name === 'refresh') {
        try { setFunds(await api.get<Rec>('/api/broker/funds')); setFundsErr(null); } catch (e) { setFunds(null); setFundsErr((e as Error).message); }
      } else { setFunds(null); }
      st.reload(); reloadShell();
    } catch (e) { setErr((e as Error).message); } finally { setBusy(null); }
  };

  const s = st.data, d = s?.data ?? {}, probe = s?.probe;
  const op = OP_STATES[(s?.operating_state as OpState) ?? 'DISCONNECTED'];
  const connected = d.state === 'OK' && !d.simulated;
  return (
    <div className="stack">
      <div className="seg" role="tablist">
        <button role="tab" aria-selected={tab === 'connection'} className={tab === 'connection' ? 'tab active' : 'tab'} onClick={() => setTab('connection')}>Groww connection</button>
        <button role="tab" aria-selected={tab === 'live'} className={tab === 'live' ? 'tab active' : 'tab'} onClick={() => setTab('live')}>Live trading activation</button>
      </div>
      {tab === 'live' ? <Live admin={admin} bump={bump} /> : !s ? (st.error ? <ErrorNote error={st.error} /> : <Skeleton lines={5} />) : (
        <>
          <div className="note info"><b>Connecting Groww never enables live trading.</b> Live trading is a separate, gated activation (see the “Live trading activation” tab). Credentials are read by the server from its environment and are never shown or entered here.</div>
          <ErrorNote error={err} />
          {d.simulated && <div className="note warn" role="alert"><b>DATA_SOURCE is MOCK:</b> this instance runs on synthetic prices. No Groww connection exists and no broker figures can be shown.</div>}
          <div className="grid two">
            <Card title="Groww" right={<Badge tone={op.tone}>{op.glyph} {op.label}</Badge>}>
              <KV rows={[
                ['Broker', 'Groww (Trade API)'],
                ['Connection', <Badge tone={connected ? 'ok' : d.state === 'AUTH_EXPIRED' ? 'warn' : d.state === 'AUTH_FAILED' ? 'bad' : 'muted'}>{d.simulated ? 'SIMULATED' : d.state}</Badge>],
                ['Authentication detail', d.detail ?? '—'],
                ['Credentials on server', d.credentials_present ? 'present' : <span className="neg">not configured</span>],
                ['Last successful authentication', d.last_auth_success ? fmtTime(d.last_auth_success) : '—'],
                ['Market-data mechanism', d.mechanism],
                ['Poll interval', d.poll_seconds ? `${d.poll_seconds}s` : '—'],
                ['Max quote age', `${d.max_quote_age_seconds}s`],
                ['Last capability check', probe?.probed_at ? fmtTime(probe.probed_at) : 'never'],
              ]} />
              {d.blocked_reason && <div className="note warn">Broker-dependent actions are paused: {d.blocked_reason}</div>}
              {!admin && <div className="note warn">Administrator role required for connection actions.</div>}
              <div className="row">
                <button className="btn primary" disabled={!admin || !!busy || d.simulated} onClick={() => act('connect', '/api/broker/connect')}>{busy === 'connect' ? 'Connecting…' : d.state === 'OK' ? 'Reauthorise' : 'Connect Groww'}</button>
                <button className="btn" disabled={!admin || !!busy || d.simulated} onClick={() => act('verify', '/api/broker/verify')}>{busy === 'verify' ? 'Verifying…' : 'Verify connection'}</button>
                <button className="btn" disabled={!admin || !!busy || d.simulated || !connected} onClick={() => act('refresh', '/api/broker/verify')}>Refresh account data</button>
                <ConfirmButton label="Disconnect" tone="danger" disabled={!admin || !!busy || d.simulated || d.state === 'DISCONNECTED'} confirmText="Drop the session? Open positions are NOT liquidated." onConfirm={() => act('disconnect', '/api/broker/disconnect')} />
              </div>
              <div className="small muted">Groww access tokens expire daily at 06:00 IST; reauthorise (or refresh the token on the server) each trading day. Token requests are limited to 150 per 24 h.</div>
            </Card>
            <Card title="Claude API" right={<Badge tone={s.claude.status === 'OK' ? 'ok' : s.claude.status === 'NOT_CONFIGURED' ? 'warn' : s.claude.status === 'DEGRADED' ? 'bad' : 'muted'}>{s.claude.status}</Badge>}>
              <KV rows={[['Analyst', s.claude.analyst], ['Model', s.claude.model], ['Credential on server', s.claude.configured ? 'present' : <span className="neg">ANTHROPIC_API_KEY not set</span>],
                ['Last success', s.claude.last_success ? fmtTime(s.claude.last_success) : '—'], ['Last failure', s.claude.last_failure ? `${fmtTime(s.claude.last_failure)} — ${s.claude.last_failure_message}` : '—']]} />
              {s.claude.detail && <div className="note warn">{s.claude.detail}</div>}
              {s.claude.simulated && <div className="note warn">The analyst is a simulated heuristic (AI_PROVIDER=MOCK_HEURISTIC), not Claude.</div>}
              <div className="small muted">Claude receives only market snapshots. It never sees broker credentials and cannot place orders or change risk limits.</div>
            </Card>
          </div>
          <Card title="API capabilities" right={probe ? <span className="small muted">checked {fmtTime(probe.probed_at)}</span> : null}>
            {!probe ? <div className="muted small">Not checked yet. Use “Verify connection” — it performs read-only requests only (never an order endpoint).</div> : (
              <div className="table-wrap"><table><thead><tr><th>Capability</th><th>Status</th><th>Detail</th></tr></thead><tbody>
                {Object.entries(probe.capabilities as Rec).map(([k, v]: [string, any]) => ( // eslint-disable-line @typescript-eslint/no-explicit-any
                  <tr key={k}><td>{CAPS[k] ?? k}</td><td><Badge tone={capTone(v.status)}>{v.status}</Badge></td><td className="small reason">{v.detail}</td></tr>))}
              </tbody></table></div>)}
          </Card>
          <Card title="Account (Groww)" right={<SourceTag kind="LIVE" />}>
            {funds ? <KV rows={[['Available cash', fmtInr(funds.available_cash, 2)], ['Margin used', fmtInr(funds.used_margin, 2)], ['Total balance', fmtInr(funds.total_balance, 2)]]} />
              : <div className="muted small">{fundsErr ? `Unavailable: ${fundsErr}` : connected ? 'Run “Verify connection” to read funds.' : 'Connect Groww to read funds. No balance is ever shown from synthetic data.'}</div>}
            <div className="small muted">Positions and orders are on the <a href="#/portfolio">Portfolio</a> page.</div>
          </Card>
        </>)}
    </div>
  );
}
