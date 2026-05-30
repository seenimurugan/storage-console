'use client';

import { useState } from 'react';
import { api, Task, RunView } from '@/lib/api';
import { ModeToggle } from './ModeToggle';
import { StatusBadge } from './StatusBadge';
import { relativeTime, timeOfDay } from '@/lib/format';
import clsx from 'clsx';

export function TaskCard({ task, onChanged }: { task: Task; onChanged: () => void }) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showLogs, setShowLogs] = useState(false);
  const [logs, setLogs] = useState<string>('');
  const [activeJob, setActiveJob] = useState<string | null>(null);

  async function changeMode(next: 'auto' | 'manual') {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      await api.setMode(task.id, next);
      onChanged();
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function trigger() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await api.trigger(task.id);
      setActiveJob(res.jobName);
      setShowLogs(true);
      pollLogs(res.jobName);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function pollLogs(jobName: string) {
    let stopped = false;
    const tick = async () => {
      if (stopped) return;
      try {
        const txt = await api.logs(task.id, jobName, 500);
        setLogs(txt);
        const runs = await api.listRuns(task.id, 5);
        const r = runs.find((x) => x.jobName === jobName);
        const terminal = r && (r.outcome === 'succeeded' || r.outcome === 'failed');
        onChanged();
        if (!terminal) setTimeout(tick, 2000);
      } catch {
        setTimeout(tick, 4000);
      }
    };
    tick();
    return () => { stopped = true; };
  }

  async function viewLastLogs() {
    if (!task.lastRun) return;
    setActiveJob(task.lastRun.jobName);
    setShowLogs(true);
    try {
      const txt = await api.logs(task.id, task.lastRun.jobName, 500);
      setLogs(txt);
    } catch (e: unknown) {
      setLogs(e instanceof Error ? e.message : String(e));
    }
  }

  const auto = task.mode === 'auto';
  const lr: RunView | null = task.lastRun;

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl shadow-sm p-5 flex flex-col gap-4">
      <div>
        <h2 className="font-semibold text-lg">{task.displayName}</h2>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">{task.description}</p>
      </div>

      {task.warning && (
        <div className="text-xs rounded-lg bg-amber-100 dark:bg-amber-900/30 text-amber-900 dark:text-amber-200 px-3 py-2">
          {task.warning}
        </div>
      )}

      <div className="flex items-center justify-between gap-3">
        <div className="flex flex-col gap-1">
          <span className="text-xs text-slate-500 uppercase tracking-wide">Mode</span>
          <ModeToggle mode={task.mode} onChange={changeMode} disabled={busy} />
        </div>
        <button
          type="button"
          onClick={trigger}
          disabled={busy || auto}
          className={clsx(
            'px-4 py-2 rounded-lg font-medium text-sm transition-colors',
            auto
              ? 'bg-slate-100 dark:bg-slate-800 text-slate-400 cursor-not-allowed'
              : 'bg-brand-500 hover:bg-brand-600 text-white'
          )}
          title={auto ? 'Switch to manual to trigger' : 'Run this task now'}
        >
          {busy ? 'Working…' : 'Trigger Now'}
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 text-sm border-t border-slate-100 dark:border-slate-800 pt-3">
        <div>
          <div className="text-xs uppercase tracking-wide text-slate-500">Next run</div>
          <div className="font-medium">
            {auto
              ? task.nextRun
                ? timeOfDay(task.nextRun)
                : task.schedule ?? '—'
              : <span className="text-slate-400">paused</span>}
          </div>
        </div>
        <div>
          <div className="text-xs uppercase tracking-wide text-slate-500">Last run</div>
          <div className="flex items-center gap-2">
            <StatusBadge outcome={lr?.outcome} />
            <span className="text-slate-500">{lr ? relativeTime(lr.startedAt) : ''}</span>
          </div>
        </div>
      </div>

      {error && <div className="text-xs text-red-600">{error}</div>}

      <div className="flex justify-between items-center pt-1">
        <div className="text-xs text-slate-400 font-mono truncate">{task.cronJobName}</div>
        {lr && (
          <button
            type="button"
            className="text-xs text-brand-500 hover:underline"
            onClick={viewLastLogs}
          >
            view logs
          </button>
        )}
      </div>

      {showLogs && (
        <div className="mt-2 rounded-lg bg-slate-950 text-slate-100 text-xs font-mono p-3 max-h-80 overflow-auto">
          <div className="flex justify-between text-slate-400 mb-2">
            <span>{activeJob}</span>
            <button onClick={() => setShowLogs(false)} className="hover:text-white">close</button>
          </div>
          <pre className="whitespace-pre-wrap">{logs || '(no logs yet)'}</pre>
        </div>
      )}
    </div>
  );
}
