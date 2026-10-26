// Thin API client. Authentication is an HttpOnly session cookie managed by the server; the only client-side
// secret-ish value is the CSRF double-submit cookie. Nothing is ever written to localStorage/sessionStorage.
export type Rec = Record<string, any>; // eslint-disable-line @typescript-eslint/no-explicit-any

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

let unauthorizedHandler: () => void = () => {};
export function onUnauthorized(fn: () => void) {
  unauthorizedHandler = fn;
}

function cookie(name: string): string | undefined {
  const m = document.cookie.split('; ').find((c) => c.startsWith(name + '='));
  return m ? decodeURIComponent(m.split('=')[1]) : undefined;
}

async function ensureCsrf(): Promise<void> {
  if (!cookie('XSRF-TOKEN')) await fetch('/api/auth/csrf', { credentials: 'include' });
}

async function request<T = Rec>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (method !== 'GET') {
    await ensureCsrf();
    const t = cookie('XSRF-TOKEN');
    if (t) headers['X-XSRF-TOKEN'] = t;
  }
  const res = await fetch(path, { method, headers, credentials: 'include', body: body === undefined ? undefined : JSON.stringify(body) });
  if (res.status === 401 && !path.startsWith('/api/auth/login')) unauthorizedHandler();
  const text = await res.text();
  const json = text ? safeJson(text) : null;
  if (!res.ok) throw new ApiError(res.status, json?.error ?? String(res.status), json?.message ?? json?.error ?? res.statusText);
  return json as T;
}

function safeJson(t: string): Rec | null {
  try {
    return JSON.parse(t);
  } catch {
    return null;
  }
}

export const api = {
  get: <T = Rec>(p: string) => request<T>('GET', p),
  post: <T = Rec>(p: string, b?: unknown) => request<T>('POST', p, b ?? {}),
};
