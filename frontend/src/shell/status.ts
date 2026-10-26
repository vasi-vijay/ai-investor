import { Tone } from '../ui';

export type OpState =
  | 'DISCONNECTED' | 'AUTHENTICATING' | 'CONNECTED_READ_ONLY' | 'PAPER_TRADING' | 'LIVE_TRADING_PENDING_AUTHORIZATION'
  | 'LIVE_TRADING_ENABLED' | 'AUTHENTICATION_EXPIRED' | 'TRADING_PAUSED' | 'EMERGENCY_LOCKED' | 'CONNECTION_ERROR';

// Mode is always conveyed by a glyph AND text, never by colour alone.
export const OP_STATES: Record<OpState, { label: string; tone: Tone; glyph: string; help: string }> = {
  DISCONNECTED: { label: 'Disconnected', tone: 'muted', glyph: '○', help: 'No broker session. No signals are generated.' },
  AUTHENTICATING: { label: 'Authenticating', tone: 'info', glyph: '◌', help: 'Authenticating with Groww.' },
  CONNECTED_READ_ONLY: { label: 'Read-only', tone: 'info', glyph: '◐', help: 'Connected for data and account reads; no order logic runs.' },
  PAPER_TRADING: { label: 'Paper trading', tone: 'info', glyph: '◇', help: 'Orders are simulated; nothing is sent to a broker.' },
  LIVE_TRADING_PENDING_AUTHORIZATION: { label: 'Live: pending authorisation', tone: 'warn', glyph: '▲', help: 'Live was requested but not every gate check passes. No live orders.' },
  LIVE_TRADING_ENABLED: { label: 'LIVE trading', tone: 'bad', glyph: '●', help: 'Real-money orders can be submitted, each after manual approval.' },
  AUTHENTICATION_EXPIRED: { label: 'Auth expired', tone: 'warn', glyph: '⚠', help: 'Groww session expired; broker-dependent actions are paused.' },
  TRADING_PAUSED: { label: 'Paused', tone: 'warn', glyph: '⏸', help: 'Operator paused trading; no new signals or orders.' },
  EMERGENCY_LOCKED: { label: 'Emergency lock', tone: 'bad', glyph: '■', help: 'Kill switch engaged; no new orders.' },
  CONNECTION_ERROR: { label: 'Connection error', tone: 'bad', glyph: '✕', help: 'Broker authentication failed.' },
};

export function riskStatus(o: Record<string, any> | null): { label: string; tone: Tone; glyph: string } { // eslint-disable-line @typescript-eslint/no-explicit-any
  if (!o) return { label: 'Risk: unknown', tone: 'muted', glyph: '?' };
  if (o.kill_switch) return { label: 'Risk: kill switch', tone: 'bad', glyph: '■' };
  if (o.today?.locked) return { label: 'Risk: daily lock', tone: 'bad', glyph: '■' };
  const pct = o.risk_utilization?.daily_loss_used_pct ?? 0;
  if (pct >= 80) return { label: `Risk: high ${pct.toFixed(0)}%`, tone: 'bad', glyph: '▲' };
  if (pct >= 50) return { label: `Risk: elevated ${pct.toFixed(0)}%`, tone: 'warn', glyph: '▲' };
  return { label: `Risk: normal ${pct.toFixed(0)}%`, tone: 'ok', glyph: '✓' };
}

export type Warning = { level: 'bad' | 'warn' | 'info'; text: string };

export function warnings(o: Record<string, any> | null): Warning[] { // eslint-disable-line @typescript-eslint/no-explicit-any
  if (!o) return [];
  const w: Warning[] = [];
  if (o.kill_switch) w.push({ level: 'bad', text: 'Kill switch is engaged — no new orders.' });
  if (o.today?.locked) w.push({ level: 'bad', text: `Daily loss lock: ${o.today.lock_reason}` });
  if (o.engine_blocked_reason) w.push({ level: 'warn', text: `Engine idle: ${o.engine_blocked_reason}` });
  if (o.claude?.status === 'NOT_CONFIGURED') w.push({ level: 'warn', text: 'Claude is not configured (ANTHROPIC_API_KEY) — AI-dependent decisions are blocked.' });
  if (o.claude?.status === 'DEGRADED') w.push({ level: 'warn', text: `Claude degraded: ${o.claude.last_failure_message ?? ''}` });
  if (o.data?.simulated) w.push({ level: 'info', text: 'Market data is SIMULATED (DATA_SOURCE=MOCK). Prices are not real.' });
  if (o.claude?.simulated) w.push({ level: 'info', text: 'AI analyst is a SIMULATED heuristic, not Claude.' });
  if (o.data_freshness?.last_error) w.push({ level: 'warn', text: `Last cycle error: ${o.data_freshness.last_error}` });
  return w;
}
