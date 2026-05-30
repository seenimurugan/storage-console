// Lightweight relative-time formatter — avoids pulling in a date library.
export function relativeTime(iso: string | null | undefined): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  const diff = Date.now() - t;
  const past = diff >= 0;
  const a = Math.abs(diff);
  const sec = Math.floor(a / 1000);
  const min = Math.floor(sec / 60);
  const hr = Math.floor(min / 60);
  const day = Math.floor(hr / 24);
  let s: string;
  if (sec < 45) s = 'just now';
  else if (min < 60) s = `${min}m`;
  else if (hr < 24) s = `${hr}h`;
  else if (day < 7) s = `${day}d`;
  else s = new Date(t).toLocaleDateString();
  if (s === 'just now') return s;
  return past ? `${s} ago` : `in ${s}`;
}

export function timeOfDay(iso: string | null | undefined): string {
  if (!iso) return '—';
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return iso;
  return new Date(t).toLocaleString(undefined, {
    weekday: 'short',
    hour: '2-digit',
    minute: '2-digit',
  });
}
