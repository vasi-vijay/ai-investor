import { useState } from 'react';
import { Rec } from '../api';
import { fmtNum, fmtTime } from '../format';
import { Badge, Card, ErrorNote, KV, List, SimTag, Tone, useApi } from '../ui';

const finalTone = (s: string): Tone => (s === 'APPROVED' ? 'ok' : s === 'REJECTED' ? 'warn' : s === 'NO_TRADE' ? 'muted' : 'bad');
const dirOf = (d: string) => (d === 'BUY_CANDIDATE' ? 'LONG' : d === 'SELL_CANDIDATE' ? 'SHORT' : '—');

export default function Decisions({ bump }: { bump: number }) {
  const [includeNoTrade, setInc] = useState(false);
  const [selected, setSel] = useState<string | null>(null);
  const { data, error } = useApi<Rec[]>(`/api/signals?limit=150&includeNoTrade=${includeNoTrade}`, 15000, bump);
  return (
    <div className="stack">
      <ErrorNote error={error} />
      {selected && <Detail id={selected} onClose={() => setSel(null)} />}
      <Card title="AI & rules decisions" right={<label className="inline"><input type="checkbox" checked={includeNoTrade} onChange={(e) => setInc(e.target.checked)} /> include NO_TRADE evaluations</label>}>
        <p className="muted small">
          Every strategy evaluation is recorded. “AI confidence” is an <b>uncalibrated model score</b>, not a probability of profit. Entry/stop/target are the
          strategy’s levels on the analysed instrument (underlying level for index signals).
        </p>
        <div className="table-wrap">
          <table>
            <thead><tr><th>Bar (IST)</th><th>Instrument</th><th>Strategy</th><th>Rules</th><th>AI</th><th>Entry / Stop / Target</th><th>R:R</th><th>Outcome</th><th>Reason</th></tr></thead>
            <tbody>
              {(data ?? []).map((r) => (
                <tr key={r.id} className="click" onClick={() => { setSel(r.id); window.scrollTo({ top: 0, behavior: 'smooth' }); }}>
                  <td>{fmtTime(r.bar_time)}</td>
                  <td>{r.instrument_symbol}{r.traded_instrument_id ? <span className="muted small"> → option</span> : null}</td>
                  <td className="small">{r.strategy_name}</td>
                  <td>{r.rule_decision === 'NO_TRADE' || r.rule_decision === 'INSUFFICIENT_DATA' ? <Badge>{r.rule_decision}</Badge> : <Badge tone="info">{dirOf(r.rule_decision)}</Badge>}</td>
                  <td>
                    {r.ai_decision ? (
                      <>
                        <Badge tone={r.ai_agrees === true ? 'ok' : r.ai_agrees === false ? 'bad' : 'muted'}>{r.ai_decision}</Badge>
                        {r.ai_confidence != null && <span className="small muted" title="uncalibrated model output, not a probability"> {r.ai_confidence}*</span>}
                        {r.ai_agrees === false && <Badge tone="warn" title={r.disagreement_note}>disagree</Badge>}
                      </>
                    ) : <span className="muted">—</span>}
                  </td>
                  <td className="num small">{r.proposed_entry != null ? `${fmtNum(r.proposed_entry)} / ${fmtNum(r.proposed_stop)} / ${fmtNum(r.proposed_target)}` : '—'}</td>
                  <td className="num">{r.risk_reward != null ? fmtNum(r.risk_reward, 2) : '—'}</td>
                  <td><Badge tone={finalTone(r.final_status)}>{r.final_status}</Badge> {r.final_status === 'APPROVED' && <SimTag />}{r.outcome_status && <div className="small muted">{r.outcome_status} {r.outcome_pnl != null ? `₹${fmtNum(r.outcome_pnl, 0)}` : ''}</div>}</td>
                  <td className="small reason">{r.rejection_reason ?? ''}</td>
                </tr>
              ))}
              {data && data.length === 0 && <tr><td colSpan={9} className="muted">No evaluations yet.</td></tr>}
            </tbody>
          </table>
        </div>
        <div className="small muted">* AI confidence: uncalibrated, subjective model output.</div>
      </Card>
    </div>
  );
}

function Detail({ id, onClose }: { id: string; onClose: () => void }) {
  const { data: d, error } = useApi<Rec>(`/api/signals/${id}`);
  const [raw, setRaw] = useState(false);
  if (!d) return <Card title="Decision detail"><ErrorNote error={error} /><div className="muted">Loading…</div></Card>;
  const ai = d.ai_record, va = ai?.validated_assessment, rr = d.rule_reasons ?? {}, snap = d.snapshot ?? {}, op = snap.option_plan;
  const move = d.subsequent_move?.hypothetical;
  return (
    <Card title={`Decision ${d.id.slice(0, 8)} · ${d.instrument_symbol} · ${d.strategy_name} v${d.strategy_version}`} right={<button className="btn small" onClick={onClose}>Close</button>}>
      <div className="grid three">
        <div>
          <h4>1 · Strategy (deterministic)</h4>
          <KV rows={[['Decision', d.rule_decision], ['Regime', d.market_regime ?? '—'], ['Timeframe', d.timeframe], ['Snapshot hash', <code>{String(d.snapshot_hash).slice(0, 16)}…</code>]]} />
          <b className="small">Supporting</b><List items={rr.supporting} />
          <b className="small">Contradicting</b><List items={rr.contradicting} />
          <b className="small">Invalidation</b><List items={rr.invalidation} />
        </div>
        <div>
          <h4>2 · AI assessment</h4>
          {!ai ? <div className="muted small">No AI analysis for this evaluation (rules-only mode, NO_TRADE, or plan rejected before AI).</div> : (
            <>
              <KV rows={[
                ['Analyst', `${ai.analyst} · ${ai.model}`],
                ['Validation', <Badge tone={ai.validation_status === 'VALID' ? 'ok' : 'bad'}>{ai.validation_status}</Badge>],
                ['Decision', va ? <Badge tone={d.ai_agrees === true ? 'ok' : 'warn'}>{va.decision}</Badge> : '—'],
                ['Confidence', va ? <span title="uncalibrated">{va.confidence} / 100 <span className="muted small">(uncalibrated)</span></span> : '—'],
                ['Latency / tokens', `${ai.latency_ms ?? '—'} ms · in ${ai.input_tokens ?? '—'} / out ${ai.output_tokens ?? '—'}`],
                ['Reused identical prompt', ai.reused_from_id ? 'yes (no API call)' : 'no'],
              ]} />
              {ai.validation_error_list && <div className="note bad">Rejected: <List items={ai.validation_error_list} /></div>}
              {va && (<>
                <b className="small">Supports</b><List items={va.supporting} />
                <b className="small">Contradicts</b><List items={va.contradicting} />
                <b className="small">Would invalidate</b><List items={va.invalidation} />
                <b className="small">Missing information</b><List items={va.missing_information} />
                <b className="small">Reject trade?</b> <Badge tone={va.reject_trade ? 'warn' : 'ok'}>{va.reject_trade ? 'yes' : 'no'}</Badge>
                {d.disagreement_note && <div className="note warn">AI vs rules: {d.disagreement_note}</div>}
              </>)}
            </>
          )}
        </div>
        <div>
          <h4>3 · Independent risk engine</h4>
          <KV rows={[['Result', <Badge tone={d.risk_decision === 'APPROVED' ? 'ok' : d.risk_decision === 'REJECTED' ? 'bad' : 'muted'}>{d.risk_decision}</Badge>], ['Final', <Badge tone={finalTone(d.final_status)}>{d.final_status}</Badge>]]} />
          {d.rejection_reason && <div className="note warn">{d.rejection_reason}</div>}
          <ul className="checks">
            {(d.risk_checks ?? []).map((c: Rec) => (
              <li key={c.name} className={c.passed ? 'pass' : 'fail'}><b>{c.passed ? '✓' : '✗'} {c.name}</b><span className="small"> {c.detail}</span></li>
            ))}
          </ul>
        </div>
      </div>
      {op && (<><h4>Option plan (long option only)</h4>
        <KV rows={[['Contract', op.contract], ['Strike / expiry', `${op.strike} ${op.option_type} · ${op.expiry} (${op.days_to_expiry}d)`], ['Lot size', op.lot_size],
          ['Premium entry / stop / target', `${op.premium_entry} / ${op.premium_stop} / ${op.premium_target}`], ['Bid / ask (spread)', `${op.bid} / ${op.ask} (${(op.spread_pct * 100).toFixed(2)}%)`],
          ['Day volume · OI', `${op.day_volume} · ${op.open_interest ?? '—'}`], ['Recent premium move', `${(op.recent_premium_move_fraction * 100).toFixed(1)}%`]]} /></>)}
      <h4>4 · Outcome</h4>
      <KV rows={[['Simulated order', d.order_id ? d.order_id.slice(0, 8) : '—'], ['Actual (simulated) result', d.outcome_status ? `${d.outcome_status} · ₹${fmtNum(d.outcome_pnl, 2)}` : 'not traded / still open'],
        ['Subsequent move (strategy levels, cost-free)', move ? `${move.result} · ${fmtNum(move.r_multiple, 2)}R (MFE ${fmtNum(move.mfe_r, 2)}R, MAE ${fmtNum(move.mae_r, 2)}R)` : 'computed at end of day']]} />
      <button className="btn small" onClick={() => setRaw(!raw)}>{raw ? 'Hide' : 'Show'} raw AI output & snapshot</button>
      {raw && <pre className="raw">{JSON.stringify({ raw_ai_response: ai?.raw_response, snapshot: { ...snap, candles_completed_only: `[${snap.candles_completed_only?.length ?? 0} candles — see database]` } }, null, 2)}</pre>}
    </Card>
  );
}
