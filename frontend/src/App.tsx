import { FormEvent, useEffect, useState } from 'react';
import { api, onUnauthorized, Rec } from './api';
import { Badge, ErrorBoundary, useApi } from './ui';
import { OP_STATES, OpState, riskStatus, warnings } from './shell/status';
import { StatusContext } from './shell/StatusContext';
import { applyTheme, getTheme } from './shell/theme';
import Overview from './views/Overview';
import Markets from './views/Markets';
import Decisions from './views/Decisions';
import Strategies from './views/Strategies';
import Trading from './views/Trading';
import Portfolio from './views/Portfolio';
import Risk from './views/Risk';
import Backtests from './views/Backtests';
import Analytics from './views/Analytics';
import Audit from './views/Audit';
import Broker from './views/Broker';
import Settings from './views/Settings';

type User = { username: string; roles: string[]; admin: boolean };

const NAV = [
  { id: 'overview', label: 'Overview', icon: '▦' },
  { id: 'markets', label: 'Live Markets', icon: '∿' },
  { id: 'ai', label: 'AI Analysis', icon: '✦' },
  { id: 'strategies', label: 'Strategies', icon: '≡' },
  { id: 'orders', label: 'Orders', icon: '⇄' },
  { id: 'positions', label: 'Positions', icon: '◧' },
  { id: 'portfolio', label: 'Portfolio', icon: '◔' },
  { id: 'risk', label: 'Risk Management', icon: '⛨' },
  { id: 'backtests', label: 'Backtesting', icon: '↺' },
  { id: 'analytics', label: 'Performance Analytics', icon: '↗' },
  { id: 'audit', label: 'Decision Audit Trail', icon: '☰' },
  { id: 'broker', label: 'Broker Connection', icon: '⚯' },
  { id: 'settings', label: 'Application Settings', icon: '⚙' },
] as const;
type PageId = (typeof NAV)[number]['id'];

const pageFromHash = (): PageId => {
  const h = location.hash.replace(/^#\/?/, '');
  return (NAV.find((n) => n.id === h)?.id ?? 'overview') as PageId;
};

export default function App() {
  const [user, setUser] = useState<User | null>(null);
  const [checked, setChecked] = useState(false);
  useEffect(() => {
    applyTheme(getTheme());
    onUnauthorized(() => setUser(null));
    api.get<User>('/api/auth/me').then(setUser).catch(() => setUser(null)).finally(() => setChecked(true));
  }, []);
  if (!checked) return <div className="center muted">Loading…</div>;
  if (!user) return <Login onLogin={setUser} />;
  return <Shell user={user} onLogout={async () => { await api.post('/api/auth/logout'); setUser(null); }} />;
}

function Shell({ user, onLogout }: { user: User; onLogout: () => void }) {
  const [page, setPage] = useState<PageId>(pageFromHash);
  const [collapsed, setCollapsed] = useState(false);
  const [drawer, setDrawer] = useState(false);
  const [menu, setMenu] = useState<'bell' | 'acct' | null>(null);
  const [tick, setTick] = useState(0);
  const [wsUp, setWsUp] = useState(false);
  const ov = useApi<Rec>('/api/overview', 10000, tick);

  useEffect(() => { const f = () => setPage(pageFromHash()); window.addEventListener('hashchange', f); return () => window.removeEventListener('hashchange', f); }, []);
  const go = (id: PageId) => { location.hash = '/' + id; setDrawer(false); setMenu(null); };

  // server push: any "step" event triggers refetches in visible views; reconnects with bounded backoff.
  useEffect(() => {
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
  }, []);

  const o = ov.data;
  const op = OP_STATES[(o?.operating_state as OpState) ?? 'DISCONNECTED'];
  const risk = riskStatus(o);
  const warn = warnings(o);
  const g = o?.data;
  const growwTone = !o ? 'muted' : g?.simulated ? 'info' : g?.state === 'OK' ? 'ok' : g?.state === 'AUTH_EXPIRED' ? 'warn' : g?.state === 'AUTH_FAILED' ? 'bad' : 'muted';
  const growwText = !o ? 'Groww …' : g?.simulated ? 'Data: simulated' : g?.state === 'OK' ? 'Groww connected' : g?.state === 'AUTH_EXPIRED' ? 'Groww expired' : g?.state === 'AUTH_FAILED' ? 'Groww error' : 'Groww not connected';
  const current = NAV.find((n) => n.id === page)!;

  return (
    <StatusContext.Provider value={{ overview: o, error: ov.error, reload: ov.reload }}>
      <div className={'shell' + (collapsed ? ' collapsed' : '') + (drawer ? ' drawer-open' : '')}>
        <a className="skip" href="#main">Skip to content</a>
        <aside className="side" aria-label="Primary">
          <div className="brand"><span className="logo" aria-hidden>◈</span><span className="brand-t">AI Investor</span></div>
          <nav>
            {NAV.map((n) => (
              <button key={n.id} className={'nav-i' + (n.id === page ? ' active' : '')} aria-current={n.id === page ? 'page' : undefined} onClick={() => go(n.id)} title={n.label}>
                <span className="nav-ic" aria-hidden>{n.icon}</span><span className="nav-l">{n.label}</span>
              </button>))}
          </nav>
          <button className="nav-i collapse" onClick={() => setCollapsed(!collapsed)} aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}><span className="nav-ic" aria-hidden>{collapsed ? '»' : '«'}</span><span className="nav-l">Collapse</span></button>
        </aside>
        {drawer && <div className="scrim" onClick={() => setDrawer(false)} aria-hidden />}
        <div className="col">
          <header className="topbar">
            <button className="btn icon hamburger" aria-label="Open navigation" onClick={() => setDrawer(true)}>☰</button>
            <h1 className="page-t">{current.label}</h1>
            <div className="chips">
              <Badge tone={op.tone} title={op.help}>{op.glyph} {op.label}</Badge>
              <Badge tone={growwTone} title={g?.detail}>{growwText}</Badge>
              <Badge tone="muted" title="Market session">{o?.market_session ?? '…'}</Badge>
              <Badge tone={risk.tone}>{risk.glyph} {risk.label}</Badge>
            </div>
            <div className="tools">
              <Badge tone={wsUp ? 'ok' : 'muted'} title="dashboard event stream">{wsUp ? 'push' : 'poll'}</Badge>
              <div className="menu-wrap">
                <button className="btn icon" aria-label={`Notifications (${warn.length})`} aria-expanded={menu === 'bell'} onClick={() => setMenu(menu === 'bell' ? null : 'bell')}>🔔{warn.length > 0 && <span className="dot">{warn.length}</span>}</button>
                {menu === 'bell' && <div className="menu wide" role="menu">{warn.length === 0 ? <div className="muted small pad">No active warnings.</div> : warn.map((w, i) => <div key={i} className={'note ' + (w.level === 'info' ? 'info' : w.level === 'warn' ? 'warn' : 'bad')}>{w.text}</div>)}</div>}
              </div>
              <div className="menu-wrap">
                <button className="btn" aria-expanded={menu === 'acct'} onClick={() => setMenu(menu === 'acct' ? null : 'acct')}>{user.username} ▾</button>
                {menu === 'acct' && <div className="menu" role="menu"><div className="pad small muted">{user.admin ? 'Administrator' : 'Viewer (read-only)'}</div>
                  <button className="menu-i" onClick={() => go('settings')}>Settings</button><button className="menu-i" onClick={onLogout}>Sign out</button></div>}
              </div>
            </div>
          </header>
          <main id="main" tabIndex={-1}>
            <ErrorBoundary name={current.label} key={page}>
              {page === 'overview' && <Overview bump={tick} />}
              {page === 'markets' && <Markets />}
              {page === 'ai' && <Decisions bump={tick} />}
              {page === 'strategies' && <Strategies />}
              {page === 'orders' && <Trading section="orders" bump={tick} />}
              {page === 'positions' && <Trading section="positions" bump={tick} />}
              {page === 'portfolio' && <Portfolio admin={user.admin} bump={tick} />}
              {page === 'risk' && <Risk admin={user.admin} bump={tick} />}
              {page === 'backtests' && <Backtests admin={user.admin} />}
              {page === 'analytics' && <Analytics bump={tick} />}
              {page === 'audit' && <Audit admin={user.admin} />}
              {page === 'broker' && <Broker admin={user.admin} bump={tick} />}
              {page === 'settings' && <Settings admin={user.admin} user={user} />}
            </ErrorBoundary>
          </main>
          <footer className="foot small muted">Paper and backtest figures are simulations, not profit projections. Live broker figures are labelled LIVE.</footer>
        </div>
      </div>
    </StatusContext.Provider>
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
        <h2>◈ AI Investor</h2>
        <p className="muted small">NSE intraday trading workstation</p>
        <label>Username<input value={username} onChange={(e) => setU(e.target.value)} autoComplete="username" required /></label>
        <label>Password<input type="password" value={password} onChange={(e) => setP(e.target.value)} autoComplete="current-password" required /></label>
        {err && <div className="note bad" role="alert">{err}</div>}
        <button className="btn primary" disabled={busy}>{busy ? 'Signing in…' : 'Sign in'}</button>
      </form>
    </div>
  );
}
