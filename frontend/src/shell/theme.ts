export type Theme = 'light' | 'dark' | 'system';

export function getTheme(): Theme {
  try { const t = localStorage.getItem('theme'); return t === 'light' || t === 'dark' ? t : 'system'; } catch { return 'system'; }
}

export function applyTheme(t: Theme) {
  if (t === 'system') document.documentElement.removeAttribute('data-theme'); else document.documentElement.setAttribute('data-theme', t);
  try { if (t === 'system') localStorage.removeItem('theme'); else localStorage.setItem('theme', t); } catch { /* storage unavailable */ }
}
