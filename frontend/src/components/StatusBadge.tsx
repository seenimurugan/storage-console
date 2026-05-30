import clsx from 'clsx';

export function StatusBadge({ outcome }: { outcome: string | undefined | null }) {
  const label = outcome ?? 'no runs yet';
  const cls = clsx(
    'inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium',
    outcome === 'succeeded' && 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200',
    outcome === 'failed' && 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200',
    outcome === 'running' && 'bg-blue-100 text-blue-800 dark:bg-blue-900/40 dark:text-blue-200',
    outcome === 'pending' && 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-200',
    !outcome && 'bg-slate-100 text-slate-600 dark:bg-slate-800 dark:text-slate-300'
  );
  return <span className={cls}>{label}</span>;
}

export function HddBadge({ connected }: { connected: boolean }) {
  const cls = clsx(
    'inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-sm font-medium',
    connected
      ? 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-200'
      : 'bg-red-100 text-red-800 dark:bg-red-900/40 dark:text-red-200'
  );
  return (
    <span className={cls}>
      <span
        className={clsx(
          'w-2 h-2 rounded-full',
          connected ? 'bg-emerald-500' : 'bg-red-500'
        )}
      />
      HDD {connected ? 'connected' : 'not mounted'}
    </span>
  );
}
