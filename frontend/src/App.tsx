import { FormEvent, useEffect, useState } from 'react';
import { api, onUnauthorized, Rec } from './api';
import { Badge } from './ui';
import Overview from './views/Overview';
import Decisions from './views/Decisions';
import Trading from './views/Trading';
import Analytics from './views/Analytics';
import Backtests from './views/Backtests';
import Risk from './views/Risk';
import Live from './views/Live';

type User = { username: string; roles: string[]; admin: boolean };
const TABS = ['Overview', 'AI decisions', 'Orders & positions', 'Analytics', 'Backtests', 'Risk & controls', 'Live'] as const;
type Tab = (typeof TABS)[number];

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [checked, setChecked] = useState(false);
  const [tab, setTab] = useState<Tab>('Overview');
  const [tick, setTick] = useState(0);
  const [wsUp, setWsUp] = useState(false);

  useEffect(() => {
    onUnauthorized(() => setUser(null));
    api.get<User>('/api/auth/me').then(setUser).catch(() => setUser(null)).finally(() => setChecked(true));
  }, []);

  // server push: any "step" event triggers refetches in the visible views. Reconnects with backoff.
  useEffect(() => {
    if (!user) return;
    let ws: WebSocket | null = null, closed = false, delay = 1000, timer: number | undefined;
    const connect = () => {
      const proto = location.protocol === 'https:' ? 'wss' : 'ws';
      ws = new WebSocket(`${proto}://${location.host}/ws/events`);
      ws.onopen = () => { setWsUp(true); delay = 1000; };
      ws.onmessage = () => setTick((t) => t + 1);
      ws.onclose = () => { setWsUp(false); if (!closed) { timer = window.setTimeout(connect, delay); delay = Math.min(delay * 2, 30000); } };
    };
    connect();
    return () => { closed = true; window.clearTimeout(timer); ws?.close(); };
  }, [user]);

  if (!checked) return <div className="center muted">Loading…</div>;
  if (!user) return <Login onLogin={setUser} />;

  const logout = async () => { await api.post('/api/auth/logout'); setUser(null); };
  return (
    <div className="app">
      <header className="top">
        <div className="brand">AI Investor <span className="muted small">pilot</span></div>
        <nav className="tabs" aria-label="Sections">
          {TABS.map((t) => (
            <button key={t} className={t === tab ? 'tab active' : 'tab'} onClick={() => setTab(t)}>{t}</button>
          ))}
        </nav>
        <div className="who">
          <Badge tone={wsUp ? 'ok' : 'muted'} title="live event stream">{wsUp ? 'live' : 'polling'}</Badge>
          <span className="small">{user.username}{user.admin ? ' (admin)' : ' (viewer)'}</span>
          <button className="btn small" onClick={logout}>Sign out</button>
        </div>
      </header>
      <main>
        {tab === 'Overview' && <Overview bump={tick} />}
        {tab === 'AI decisions' && <Decisions bump={tick} />}
        {tab === 'Orders & positions' && <Trading bump={tick} />}
        {tab === 'Analytics' && <Analytics bump={tick} />}
        {tab === 'Backtests' && <Backtests admin={user.admin} />}
        {tab === 'Risk & controls' && <Risk admin={user.admin} bump={tick} />}
        {tab === 'Live' && <Live admin={user.admin} bump={tick} />}
      </main>
      <footer className="foot small muted">
        All performance figures are <b>simulated</b>. They are not profit projections and say nothing about future results.
      </footer>
    </div>
  );
}

function Login({ onLogin }: { onLogin: (u: User) => void }) {
  const [username, setU] = useState('admin');
  const [password, setP] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    setBusy(true); setErr(null);
    try {
      await fetch('/api/auth/csrf', { credentials: 'include' });
      const u = await api.post<Rec>('/api/auth/login', { username, password });
      onLogin(u as User);
    } catch (ex) {
      setErr((ex as Error).message === 'invalid_credentials' ? 'Invalid username or password' : 'Sign-in failed');
    } finally { setBusy(false); setP(''); }
  };
  return (
    <div className="center">
      <form className="card login" onSubmit={submit}>
        <h2>AI Investor</h2>
        <p className="muted small">Paper-trading pilot · NSE · live trading disabled</p>
        <label>Username<input value={username} onChange={(e) => setU(e.target.value)} autoComplete="username" required /></label>
        <label>Password<input type="password" value={password} onChange={(e) => setP(e.target.value)} autoComplete="current-password" required /></label>
        {err && <div className="note bad">{err}</div>}
        <button className="btn primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  );
}
