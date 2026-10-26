import { describe, expect, it } from 'vitest';
import { OP_STATES, riskStatus, warnings } from './status';

describe('operating states', () => {
  it('defines all ten states, each with a glyph and text (mode is never colour-only)', () => {
    const keys = Object.keys(OP_STATES);
    expect(keys).toHaveLength(10);
    for (const k of keys) { expect(OP_STATES[k as keyof typeof OP_STATES].glyph.length).toBeGreaterThan(0); expect(OP_STATES[k as keyof typeof OP_STATES].label.length).toBeGreaterThan(0); }
  });
  it('flags live trading distinctly', () => { expect(OP_STATES.LIVE_TRADING_ENABLED.tone).toBe('bad'); expect(OP_STATES.PAPER_TRADING.label).toMatch(/paper/i); });
});

describe('risk status', () => {
  it('kill switch and daily lock dominate', () => {
    expect(riskStatus({ kill_switch: true }).label).toMatch(/kill/);
    expect(riskStatus({ today: { locked: true } }).tone).toBe('bad');
  });
  it('grades utilisation', () => {
    expect(riskStatus({ risk_utilization: { daily_loss_used_pct: 10 } }).tone).toBe('ok');
    expect(riskStatus({ risk_utilization: { daily_loss_used_pct: 60 } }).tone).toBe('warn');
    expect(riskStatus({ risk_utilization: { daily_loss_used_pct: 90 } }).tone).toBe('bad');
  });
});

describe('warnings', () => {
  it('reports missing Claude credentials and blocked engine, and discloses simulated data', () => {
    const w = warnings({ claude: { status: 'NOT_CONFIGURED' }, engine_blocked_reason: 'Groww is DISCONNECTED', data: { simulated: true } }).map((x) => x.text).join('|');
    expect(w).toMatch(/Claude is not configured/); expect(w).toMatch(/Engine idle/); expect(w).toMatch(/SIMULATED/);
  });
  it('is empty when nothing is wrong', () => { expect(warnings({ claude: { status: 'OK' }, data: { simulated: false } })).toEqual([]); });
});
