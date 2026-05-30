'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, setToken } from '@/lib/api';

export default function LoginPage() {
  const router = useRouter();
  const [username, setU] = useState('');
  const [password, setP] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.me().then(() => router.replace('/')).catch(() => {});
  }, [router]);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api.login(username.trim(), password);
      setToken(r.token);
      router.replace('/');
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : 'Login failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center px-4">
      <form
        onSubmit={submit}
        className="w-full max-w-sm bg-white dark:bg-slate-900 rounded-2xl shadow-xl p-6 space-y-4 border border-slate-200 dark:border-slate-800"
      >
        <div className="text-center">
          <div className="mx-auto w-12 h-12 rounded-2xl bg-brand-500 text-white flex items-center justify-center text-2xl">
            ⛁
          </div>
          <h1 className="mt-3 text-xl font-semibold">Storage Console</h1>
          <p className="text-sm text-slate-500">Sign in</p>
        </div>
        <label className="block">
          <span className="text-sm font-medium">Username</span>
          <input
            value={username}
            onChange={(e) => setU(e.target.value)}
            autoComplete="username"
            required
            className="mt-1 w-full rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-950 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand-500"
          />
        </label>
        <label className="block">
          <span className="text-sm font-medium">Password</span>
          <input
            type="password"
            value={password}
            onChange={(e) => setP(e.target.value)}
            autoComplete="current-password"
            required
            className="mt-1 w-full rounded-lg border border-slate-300 dark:border-slate-700 bg-white dark:bg-slate-950 px-3 py-2 focus:outline-none focus:ring-2 focus:ring-brand-500"
          />
        </label>
        {error && <div className="text-sm text-red-600">{error}</div>}
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-lg bg-brand-500 hover:bg-brand-600 disabled:opacity-60 text-white font-semibold py-2.5"
        >
          {busy ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
