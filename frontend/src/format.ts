/** Server timestamps are UTC; java.sql.Timestamp values arrive without a zone suffix. */
export function parseUtc(s: string | null | undefined): Date | null {
  if (!s) return null;
  const hasZone = /(Z|[+-]\d{2}:?\d{2})$/.test(s);
  const d = new Date(hasZone ? s : s + 'Z');
  return isNaN(d.getTime()) ? null : d;
}

export function fmtTime(s: string | null | undefined): string {
  const d = parseUtc(s);
  return d ? d.toLocaleString('en-IN', { timeZone: 'Asia/Kolkata', hour12: false, day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit' }) : '—';
}

export function fmtInr(n: number | null | undefined, digits = 0): string {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—';
  return '₹' + Number(n).toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function fmtNum(n: number | null | undefined, digits = 2): string {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—';
  return Number(n).toLocaleString('en-IN', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

export function fmtPct(n: number | null | undefined, digits = 1): string {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '—';
  return Number(n).toFixed(digits) + '%';
}

export function signClass(n: number | null | undefined): string {
  if (!n) return '';
  return n > 0 ? 'pos' : 'neg';
}
