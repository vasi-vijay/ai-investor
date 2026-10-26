import { useEffect, useRef, useState } from 'react';
import { createChart, CandlestickData, IChartApi, ISeriesApi, LineData, UTCTimestamp, HistogramData } from 'lightweight-charts';
import { Rec } from '../api';
import { fmtNum, fmtTime } from '../format';
import { Badge, Card, DataTable, Empty, ErrorNote, Skeleton, SourceTag, useApi, useDebounced } from '../ui';

const TF = [1, 5, 15, 30, 60];
const css = (n: string) => getComputedStyle(document.documentElement).getPropertyValue(n).trim() || '#888';

export default function Markets() {
  const [q, setQ] = useState('');
  const dq = useDebounced(q);
  const list = useApi<Rec>(`/api/market/instruments?q=${encodeURIComponent(dq)}`, 0, 0);
  const [sym, setSym] = useState<string | null>(null);
  const [tf, setTf] = useState(5);
  const [days, setDays] = useState(3);
  const insts: Rec[] = list.data?.instruments ?? [];
  useEffect(() => { if (!sym && insts.length) setSym(insts[0].symbol); }, [insts, sym]);
  const quote = useApi<Rec>(sym ? `/api/market/quote?symbol=${encodeURIComponent(sym)}` : null, 10000);
  const candles = useApi<Rec>(sym ? `/api/market/candles?symbol=${encodeURIComponent(sym)}&minutes=${tf}&days=${days}` : null, 30000);
  const meta = candles.data ?? quote.data ?? list.data;
  const unavailable = list.data?.unavailable ?? quote.data?.unavailable ?? candles.data?.unavailable;
  return (
    <div className="stack">
      {meta && (
        <div className={'banner ' + (meta.simulated ? 'warn' : 'info')}>
          <b>Source:</b> {meta.source}{meta.simulated && ' (SIMULATED — not real prices)'} · <b>Update mechanism:</b> {meta.mechanism} · <b>Session:</b> {meta.session}
          {' '}· <b>Last refresh:</b> {fmtTime(meta.fetched_at)} · <b>Last candle:</b> {fmtTime(candles.data?.last_candle_time)}
        </div>)}
      <ErrorNote error={list.error || quote.error || candles.error} />
      {unavailable && <div className="note warn" role="alert"><b>Market data unavailable:</b> {unavailable}. No quote is shown and no executable signal is generated. See <a href="#/broker">Broker Connection</a>.</div>}
      <div className="grid markets">
        <Card title="Watchlist">
          <input type="search" placeholder="Search instruments…" aria-label="Search instruments" value={q} onChange={(e) => setQ(e.target.value)} />
          {!list.data ? <Skeleton lines={4} /> : insts.length === 0 ? <Empty title="No instruments" hint={unavailable ? 'Connect Groww to load the instrument universe.' : 'No match.'} /> : (
            <ul className="watch">{insts.map((i) => (
              <li key={i.symbol}><button className={'watch-i' + (i.symbol === sym ? ' active' : '')} onClick={() => setSym(i.symbol)}>
                <span>{i.symbol}</span><span className="muted small">{i.type === 'INDEX' ? 'Index' : 'NSE EQ'}</span></button></li>))}</ul>)}
        </Card>
        <div className="stack">
          <Card title={sym ?? 'Select an instrument'} right={<SourceTag kind={meta?.simulated ? 'SIMULATION' : 'LIVE'} />}>
            <QuoteBar q={quote.data} />
            <div className="row">
              <span className="seg" role="group" aria-label="Timeframe">{TF.map((m) => <button key={m} className={m === tf ? 'tab active' : 'tab'} aria-pressed={m === tf} onClick={() => setTf(m)}>{m < 60 ? `${m}m` : '1h'}</button>)}</span>
              <label className="inline">Range<select value={days} onChange={(e) => setDays(Number(e.target.value))}>{[1, 3, 5, 10].map((d) => <option key={d} value={d}>{d} day{d > 1 ? 's' : ''}</option>)}</select></label>
            </div>
            {candles.data?.candles?.length ? <PriceChart candles={candles.data.candles} /> : candles.loading || !candles.data ? <Skeleton lines={6} /> : <Empty title="No candles" hint="The provider returned no completed candles for this range." />}
            <div className="muted small">Overlays: EMA 9 / EMA 21 (computed server-side from completed candles). RSI(14) shown below.</div>
            {candles.data?.candles?.length ? <RsiStrip candles={candles.data.candles} /> : null}
          </Card>
        </div>
      </div>
      {candles.data?.candles?.length ? (
        <Card title="Recent candles"><DataTable pageSize={10} empty="" rows={[...candles.data.candles].reverse()} cols={[
          { key: 't', head: 'Start (IST)', render: (r) => fmtTime(new Date(r.t * 1000).toISOString()), sort: (r) => r.t },
          { key: 'o', head: 'Open', num: true, render: (r) => fmtNum(r.o) }, { key: 'h', head: 'High', num: true, render: (r) => fmtNum(r.h) }, { key: 'l', head: 'Low', num: true, render: (r) => fmtNum(r.l) },
          { key: 'c', head: 'Close', num: true, render: (r) => fmtNum(r.c) }, { key: 'v', head: 'Volume', num: true }, { key: 'rsi14', head: 'RSI', num: true, render: (r) => (r.rsi14 != null ? fmtNum(r.rsi14, 1) : '—') }]} /></Card>) : null}
    </div>
  );
}

function QuoteBar({ q }: { q: Rec | null }) {
  if (!q || q.unavailable) return <div className="muted small">No quote available.</div>;
  return (
    <div className="quote">
      <span className="quote-last">{fmtNum(q.last)}</span>
      <span className="small">bid {q.bid != null ? fmtNum(q.bid) : '—'} · ask {q.ask != null ? fmtNum(q.ask) : '—'} · spread {q.spread_pct != null ? fmtNum(q.spread_pct, 3) + '%' : '—'} · vol {q.volume ?? '—'}</span>
      <span className="small muted">quote time {fmtTime(q.quote_time)} ({q.age_seconds}s ago)</span>
      {q.stale && <Badge tone="bad" title="Older than the configured maximum quote age during market hours">⚠ STALE — signals suppressed</Badge>}
    </div>
  );
}

function PriceChart({ candles }: { candles: Rec[] }) {
  const el = useRef<HTMLDivElement>(null);
  const chart = useRef<IChartApi | null>(null);
  const series = useRef<{ c: ISeriesApi<'Candlestick'>; e9: ISeriesApi<'Line'>; e21: ISeriesApi<'Line'>; v: ISeriesApi<'Histogram'> } | null>(null);
  useEffect(() => {
    if (!el.current) return;
    const ch = createChart(el.current, {
      height: 340, autoSize: true,
      layout: { background: { color: 'transparent' }, textColor: css('--muted') }, grid: { vertLines: { color: css('--line') }, horzLines: { color: css('--line') } },
      timeScale: { timeVisible: true, secondsVisible: false }, rightPriceScale: { borderColor: css('--line') },
    });
    const c = ch.addCandlestickSeries({ upColor: css('--ok'), downColor: css('--bad'), wickUpColor: css('--ok'), wickDownColor: css('--bad'), borderVisible: false });
    const e9 = ch.addLineSeries({ color: css('--accent'), lineWidth: 1, priceLineVisible: false });
    const e21 = ch.addLineSeries({ color: css('--warn'), lineWidth: 1, priceLineVisible: false });
    const v = ch.addHistogramSeries({ priceFormat: { type: 'volume' }, priceScaleId: 'vol', color: css('--line') });
    ch.priceScale('vol').applyOptions({ scaleMargins: { top: 0.8, bottom: 0 } });
    chart.current = ch; series.current = { c, e9, e21, v };
    return () => { ch.remove(); chart.current = null; series.current = null; };
  }, []);
  useEffect(() => {
    const s = series.current; if (!s) return;
    const t = (r: Rec) => r.t as UTCTimestamp;
    s.c.setData(candles.map((r): CandlestickData => ({ time: t(r), open: r.o, high: r.h, low: r.l, close: r.c })));
    s.e9.setData(candles.filter((r) => r.ema9 != null).map((r): LineData => ({ time: t(r), value: r.ema9 })));
    s.e21.setData(candles.filter((r) => r.ema21 != null).map((r): LineData => ({ time: t(r), value: r.ema21 })));
    s.v.setData(candles.map((r): HistogramData => ({ time: t(r), value: r.v })));
    chart.current?.timeScale().fitContent();
  }, [candles]);
  return <div ref={el} className="chart-box" role="img" aria-label="Candlestick chart with EMA 9 and EMA 21" />;
}

function RsiStrip({ candles }: { candles: Rec[] }) {
  const el = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!el.current) return;
    const ch = createChart(el.current, { height: 110, autoSize: true, layout: { background: { color: 'transparent' }, textColor: css('--muted') }, grid: { vertLines: { visible: false }, horzLines: { color: css('--line') } }, timeScale: { visible: false }, rightPriceScale: { borderColor: css('--line') } });
    const s = ch.addLineSeries({ color: css('--info'), lineWidth: 1, priceLineVisible: false });
    s.setData(candles.filter((r) => r.rsi14 != null).map((r): LineData => ({ time: r.t as UTCTimestamp, value: r.rsi14 })));
    s.createPriceLine({ price: 70, color: css('--bad'), lineWidth: 1, lineStyle: 2, axisLabelVisible: false, title: '' });
    s.createPriceLine({ price: 30, color: css('--ok'), lineWidth: 1, lineStyle: 2, axisLabelVisible: false, title: '' });
    ch.timeScale().fitContent();
    return () => ch.remove();
  }, [candles]);
  return <div ref={el} className="chart-box" role="img" aria-label="RSI 14" />;
}
