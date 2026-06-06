'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { api, Me, Task, setToken } from '@/lib/api';
import { TaskCard } from '@/components/TaskCard';
import { SecretsBackupCard } from '@/components/SecretsBackupCard';
import { HddBadge } from '@/components/StatusBadge';

export default function DashboardPage() {
  const router = useRouter();
  const [me, setMe] = useState<Me | null>(null);
  const [tasks, setTasks] = useState<Task[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    api.me()
      .then(setMe)
      .catch(() => router.replace('/login'));
  }, [router]);

  async function reload() {
    try {
      const ts = await api.listTasks();
      setTasks(ts);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  useEffect(() => {
    if (!me) return;
    reload();
    const id = setInterval(reload, 15_000);
    return () => clearInterval(id);
  }, [me]);

  if (!me) return <div className="p-6 text-slate-500">Loading…</div>;

  const hdd = tasks?.[0]?.hddConnected ?? false;

  return (
    <div className="min-h-screen flex flex-col">
      <header className="bg-white dark:bg-slate-900 border-b border-slate-200 dark:border-slate-800">
        <div className="max-w-6xl mx-auto px-4 py-3 flex items-center justify-between">
          <div className="flex items-center gap-3">
            <div className="w-9 h-9 rounded-xl bg-brand-500 text-white flex items-center justify-center font-bold">⛁</div>
            <div>
              <div className="font-semibold leading-tight">Storage Console</div>
              <div className="text-xs text-slate-500">Homelab storage operations</div>
            </div>
          </div>
          <div className="flex items-center gap-3 text-sm">
            <span className="text-slate-600 dark:text-slate-300">{me.displayName}</span>
            <button
              onClick={() => { setToken(null); router.replace('/login'); }}
              className="text-slate-500 hover:text-slate-900 dark:hover:text-white"
            >
              Log out
            </button>
          </div>
        </div>
      </header>

      <main className="flex-1 max-w-6xl mx-auto w-full px-4 py-6 space-y-6">
        <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
          <div>
            <h1 className="text-2xl font-semibold">Tasks</h1>
            <p className="text-sm text-slate-500">Auto runs at the scheduled time. Manual runs are triggered from this page.</p>
          </div>
          <HddBadge connected={hdd} />
        </div>

        {error && <div className="rounded-lg bg-red-100 text-red-800 px-3 py-2 text-sm">{error}</div>}

        {tasks === null ? (
          <div className="text-slate-500">Loading tasks…</div>
        ) : (
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
            {tasks.map((t) => (
              <TaskCard key={t.id} task={t} onChanged={reload} />
            ))}
            <SecretsBackupCard />
          </div>
        )}
      </main>
    </div>
  );
}
