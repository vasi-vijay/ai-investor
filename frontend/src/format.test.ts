import { describe, expect, it } from 'vitest';
import { fmtInr, fmtPct, parseUtc, signClass } from './format';

describe('format helpers', () => {
  it('treats zoneless server timestamps as UTC', () => {
    expect(parseUtc('2026-10-26T08:05:00')?.toISOString()).toBe('2026-10-26T08:05:00.000Z');
    expect(parseUtc('2026-10-26T08:05:00Z')?.toISOString()).toBe('2026-10-26T08:05:00.000Z');
    expect(parseUtc(null)).toBeNull();
  });
  it('formats rupees and percentages defensively', () => {
    expect(fmtInr(1234567)).toBe('₹12,34,567');
    expect(fmtInr(undefined)).toBe('—');
    expect(fmtPct(12.345)).toBe('12.3%');
  });
  it('classifies sign', () => {
    expect(signClass(5)).toBe('pos');
    expect(signClass(-1)).toBe('neg');
    expect(signClass(0)).toBe('');
  });
});
