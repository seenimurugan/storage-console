// Same-origin API client. Backend is reachable via /api on the Tailscale ingress.
const BASE = process.env.NEXT_PUBLIC_API_BASE ?? '';

export type Me = {
  id: number;
  username: string;
  displayName: string;
  role: 'ADMIN';
};

export type RunView = {
  jobName: string;
  startedAt: string | null;
  finishedAt: string | null;
  outcome: 'pending' | 'running' | 'succeeded' | 'failed';
};

export type Task = {
  id: 'immich-tier' | 'jellyfin-tier' | 'immich-backup' | 'hdd-healer';
  displayName: string;
  description: string;
  cronJobName: string;
  mode: 'auto' | 'manual';
  schedule: string | null;
  nextRun: string | null;
  lastRun: RunView | null;
  hddConnected: boolean;
  warning: string | null;
};

export type HddStatus = {
  hostPath: string;
  connected: boolean;
  lastChecked: string;
};

export type Thresholds = {
  immichGib: number;
  jellyfinGib: number;
};

export type ThresholdView = {
  task: string;
  gib: number;
  bytes: number;
};

function token(): string | null {
  if (typeof window === 'undefined') return null;
  return window.localStorage.getItem('storage-console.token');
}

export function setToken(t: string | null) {
  if (typeof window === 'undefined') return;
  if (t) window.localStorage.setItem('storage-console.token', t);
  else window.localStorage.removeItem('storage-console.token');
}

async function req<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  const t = token();
  if (t) headers.set('Authorization', `Bearer ${t}`);
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const res = await fetch(`${BASE}${path}`, { ...init, headers });
  if (res.status === 401) {
    setToken(null);
    if (typeof window !== 'undefined' && !window.location.pathname.endsWith('/login')) {
      window.location.href = '/login';
    }
    throw new Error('Unauthorized');
  }
  if (!res.ok) {
    let msg = res.statusText;
    try { const j = await res.json(); if (j?.message) msg = j.message; } catch {}
    throw new Error(msg || `HTTP ${res.status}`);
  }
  if (res.status === 204) return undefined as T;
  const ct = res.headers.get('content-type') ?? '';
  if (ct.includes('application/json')) return res.json() as Promise<T>;
  return (await res.text()) as unknown as T;
}

export const api = {
  login: (username: string, password: string) =>
    req<{ token: string; expiresInSeconds: number; user: Me }>('/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ username, password }),
    }),
  me: () => req<Me>('/api/auth/me'),
  listTasks: () => req<Task[]>('/api/tasks'),
  setMode: (id: string, mode: 'auto' | 'manual') =>
    req<Task>(`/api/tasks/${id}/mode`, { method: 'PUT', body: JSON.stringify({ mode }) }),
  trigger: (id: string) =>
    req<{ jobName: string; namespace: string }>(`/api/tasks/${id}/trigger`, { method: 'POST' }),
  listRuns: (id: string, limit = 10) =>
    req<RunView[]>(`/api/tasks/${id}/runs?limit=${limit}`),
  logs: (id: string, jobName: string, lines = 500) =>
    req<string>(`/api/tasks/${id}/runs/${jobName}/logs?lines=${lines}`),
  hddStatus: () => req<HddStatus>('/api/system/hdd-status'),
  getThresholds: () => req<Thresholds>('/api/thresholds'),
  setThreshold: (task: string, gib: number) =>
    req<ThresholdView>(`/api/thresholds/${task}`, {
      method: 'PUT',
      body: JSON.stringify({ gib }),
    }),
};
