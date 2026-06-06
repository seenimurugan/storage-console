'use client';

import { useState, useEffect } from 'react';
import { api, SecretsBackupStatus } from '@/lib/api';
import { StatusBadge } from './StatusBadge';
import { relativeTime } from '@/lib/format';

/**
 * SecretsBackupCard — one-click, no CronJob.
 *
 * Renders a single "Back Up Secrets Now" button plus a last-run status line.
 * There is no Auto/Manual toggle: this is always on-demand only.
 */
export function SecretsBackupCard() {
  const [status, setStatus] = useState<SecretsBackupStatus | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeJob, setActiveJob] = useState<string | null>(null);
  const [showLogs, setShowLogs] = useState(false);
  const [logs, setLogs] = useState<string>('');

  async function reload() {
    try {
      const s = await api.secretsBackupStatus();
      setStatus(s);
      setError(null);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load status');
    }
  }

  useEffect(() => {
    reload();
    const id = setInterval(reload, 15_000);
    return () => clearInterval(id);
  }, []);

  async function trigger() {
    if (busy) return;
    setBusy(true);
    setError(null);
    try {
      const res = await api.triggerSecretsBackup();
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
        const txt = await api.secretsBackupLogs(jobName, 500);
        setLogs(txt);
        const s = await api.secretsBackupStatus();
        setStatus(s);
        const terminal =
          s.lastJobName === jobName &&
          (s.outcome === 'succeeded' || s.outcome === 'failed');
        if (!terminal) setTimeout(tick, 2000);
      } catch {
        setTimeout(tick, 4000);
      }
    };
    tick();
    return () => { stopped = true; };
  }

  async function viewLastLogs() {
    if (!status?.lastJobName) return;
    setActiveJob(status.lastJobName);
    setShowLogs(true);
    try {
      const txt = await api.secretsBackupLogs(status.lastJobName, 500);
      setLogs(txt);
    } catch (e: unknown) {
      setLogs(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="bg-white dark:bg-slate-900 border border-slate-200 dark:border-slate-800 rounded-2xl shadow-sm p-5 flex flex-col gap-4">
      <div>
        <h2 className="font-semibold text-lg">Secrets Backup</h2>
        <p className="text-sm text-slate-500 dark:text-slate-400 mt-0.5">
          Dumps all cluster secrets (age-encrypted) to the backup HDD. One-click, no schedule.
        </p>
      </div>

      <div className="flex items-center justify-between gap-3">
        <div className="text-xs text-slate-500 uppercase tracking-wide">On-demand only</div>
        <button
          type="button"
          onClick={trigger}
          disabled={busy}
          className="px-4 py-2 rounded-lg font-medium text-sm transition-colors bg-brand-500 hover:bg-brand-600 text-white disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {busy ? 'Working…' : 'Back Up Secrets Now'}
        </button>
      </div>

      <div className="grid grid-cols-2 gap-3 text-sm border-t border-slate-100 dark:border-slate-800 pt-3">
        <div>
          <div className="text-xs uppercase tracking-wide text-slate-500">Last run</div>
          <div className="flex items-center gap-2">
            <StatusBadge outcome={status?.outcome ?? undefined} />
            <span className="text-slate-500">
              {status?.startedAt ? relativeTime(status.startedAt) : '—'}
            </span>
          </div>
        </div>
        <div>
          <div className="text-xs uppercase tracking-wide text-slate-500">Finished</div>
          <div className="font-medium text-slate-700 dark:text-slate-300">
            {status?.finishedAt ? relativeTime(status.finishedAt) : '—'}
          </div>
        </div>
      </div>

      {error && <div className="text-xs text-red-600">{error}</div>}

      <div className="flex justify-between items-center pt-1">
        <div className="text-xs text-slate-400 font-mono truncate">
          {status?.lastJobName ?? 'no runs yet'}
        </div>
        {status?.lastJobName && (
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
