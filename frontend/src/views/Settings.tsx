import { useState } from 'react';
import { api } from '../api';
import { applyTheme, getTheme } from '../shell/theme';
import { Badge, Card, ConfirmButton, ErrorNote, KV } from '../ui';
import { OP_STATES, OpState } from '../shell/status';
import { useStatus } from '../shell/StatusContext';

export default function Settings({ admin, user }: { admin: boolean; user: { username: string; roles: string[] } }) {
  const { overview: o, reload } = useStatus();
  const [theme, setTheme] = useState(getTheme());
  const [err, setErr] = useState<string | null>(null);
  const [aiMode, setAiMode] = useState('AI_SHADOW');
  const [name, setName] = useState('');
  const op = OP_STATES[(o?.operating_state as OpState) ?? 'DISCONNECTED'];
  const paused = o?.operating_state === 'TRADING_PAUSED';
  const run = async (fn: () => Promise<unknown>) => { setErr(null); try { await fn(); reload(); } catch (e) { setErr((e as Error).message); } };
  return (
    <div className="stack">
      <ErrorNote error={err} />
      <div className="grid two">
        <Card title="Appearance">
          <label>Theme<select value={theme} onChange={(e) => { const t = e.target.value as 'light' | 'dark' | 'system'; setTheme(t); applyTheme(t); }}>
            <option value="system">Follow system</option><option value="light">Light</option><option value="dark">Dark</option></select></label>
          <div className="small muted">Stored only in this browser.</div>
        </Card>
        <Card title="Account"><KV rows={[['User', user.username], ['Roles', user.roles.join(', ')]]} /></Card>
      </div>
      <Card title="Operating mode" right={<Badge tone={op.tone}>{op.glyph} {op.label}</Badge>}>
        <p className="small">{op.help}</p>
        <KV rows={[['Data source', o?.data?.source ?? '—'], ['AI analyst', `${o?.claude?.analyst ?? '—'} · ${o?.claude?.model ?? ''}`], ['AI mode (paper run)', o?.paper_run?.ai_mode ?? '—'], ['Paper run', o?.paper_run?.name ?? '—']]} />
        <div className="row">
          <ConfirmButton label={paused ? 'Resume trading' : 'Pause trading'} confirmText={paused ? 'Resume signal generation?' : 'Stop new signals and orders? Open positions stay managed.'} disabled={!admin}
            onConfirm={() => run(() => api.post('/api/broker/pause', { paused: !paused }))} />
        </div>
        <div className="small muted">Data source, AI provider, model, limits and strategy parameters are server configuration (environment / <code>application.yml</code>) and are deliberately not editable from the browser. Live trading is enabled only through Broker Connection → Live trading activation.</div>
      </Card>
      <Card title="Start a new paper run">
        <p className="small">Changing the AI mode starts a new run so statistics are never mixed. Only possible while flat (no open positions/orders).</p>
        <div className="form">
          <label>AI mode<select value={aiMode} onChange={(e) => setAiMode(e.target.value)}><option value="RULES_ONLY">RULES_ONLY</option><option value="AI_SHADOW">AI_SHADOW</option><option value="AI_GATED">AI_GATED</option></select></label>
          <label>Run name<input value={name} maxLength={100} onChange={(e) => setName(e.target.value)} /></label>
          <ConfirmButton label="Start new run" confirmText="End the current paper run and start a new one?" disabled={!admin || !name.trim()} onConfirm={() => run(() => api.post('/api/paper/runs', { aiMode, name: name.trim() }))} />
        </div>
      </Card>
    </div>
  );
}
