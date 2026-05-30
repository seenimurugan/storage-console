'use client';

import clsx from 'clsx';

export function ModeToggle({
  mode,
  onChange,
  disabled,
}: {
  mode: 'auto' | 'manual';
  onChange: (next: 'auto' | 'manual') => void;
  disabled?: boolean;
}) {
  return (
    <div className="inline-flex rounded-lg border border-slate-300 dark:border-slate-700 overflow-hidden text-sm">
      {(['auto', 'manual'] as const).map((m) => {
        const active = m === mode;
        return (
          <button
            key={m}
            type="button"
            disabled={disabled}
            onClick={() => !active && onChange(m)}
            className={clsx(
              'px-3 py-1.5 font-medium capitalize transition-colors',
              active
                ? 'bg-brand-500 text-white'
                : 'bg-white dark:bg-slate-900 text-slate-700 dark:text-slate-200 hover:bg-slate-100 dark:hover:bg-slate-800',
              disabled && 'opacity-60 cursor-not-allowed'
            )}
          >
            {m}
          </button>
        );
      })}
    </div>
  );
}
