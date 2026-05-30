# Usage

## Logging in

1. Open https://tier.stoat-perch.ts.net (Tailnet required).
2. Username `admin`, password from `.env` (`STORAGE_CONSOLE_ADMIN_PASSWORD`).
3. The JWT is stored in `localStorage.storage-console.token` and lasts 30 days.

## The dashboard

Three cards, one per storage operation:

| Card | Threshold | Source → destination |
|---|---|---|
| Immich Tier | >2 GiB | Immich SSD `library/` + `encoded-video/` → HDD `immich-library/` |
| Jellyfin Tier | >3 GiB | Jellyfin SSD `movies/`, `tvshows/`, `music/` → HDD `jellyfin-media/` |
| Immich Backup | — | pg_dump + library tar → HDD `backups/postgres/` and `backups/library/` |

Each card shows:

- **Mode toggle** — Auto or Manual. State is stored as `CronJob.spec.suspend` (Auto = false, Manual = true). Kubernetes is the source of truth — there is no DB column for mode.
- **Trigger Now** — only enabled in Manual mode. Spawns a Job with name `<cronjob>-manual-<utc-timestamp>` and opens a live log viewer that polls every 2 s.
- **Next run** — for Auto mode, the next time the CronJob will fire (assumes `0 H * * *` daily schedule).
- **Last run** — most recent Job's outcome (succeeded / failed / running / pending), with a relative timestamp. Click "view logs" to see the last 500 log lines.

The top bar shows a global **HDD connected / not mounted** indicator, refreshed when the page reloads tasks (every 15 s).

## Setting Auto mode

1. Make sure the HDD is mounted at `/Volumes/homelab-hdd` and contains the expected subdirs (`immich-library/`, `jellyfin-media/`, `backups/`).
2. Switch the card to **Auto**.
3. The CronJob will fire at the configured schedule (default `0 3 * * *` in Europe/London for the tier movers, `0 4 * * *` for the backup).

If the HDD is not mounted when the CronJob fires, the job exits 0 silently (its first command is `test -d /hdd-check || exit 0`). It does NOT count as a failure and won't fill up the failed-jobs history.

## Triggering a manual run

1. Switch the card to **Manual** (if not already).
2. Click **Trigger Now**.
3. The log viewer opens at the bottom of the card and polls every 2 s until the job reaches a terminal state.
4. After completion, the **Last run** badge updates to `succeeded` or `failed`.

If the HDD is not mounted, the job will exit 0 with `SKIP HDD not mounted` in the logs.

## Two concurrent runs

`concurrencyPolicy: Forbid` is set on all three CronJobs — a new Job will NOT start if a previous one is still running. The UI will accept the trigger and Kubernetes will report the conflict; the new Job will show as failed-to-start in the runs list.

## Checking from the CLI

```bash
# Mode (true = manual, false = auto)
kubectl -n homelab get cronjobs -o jsonpath='{range .items[*]}{.metadata.name}{"\t"}{.spec.suspend}{"\n"}{end}'

# Recent runs
kubectl -n homelab get jobs --sort-by=.metadata.creationTimestamp | tail -10

# Logs for a job
kubectl -n homelab logs -l job-name=<job-name> --tail=200
```

## Fallback: the `tier-now.sh` script

`~/homelab/tier-now.sh` still works — it shells out to `kubectl create job --from=cronjob/<name>` and tails logs, same as the UI. Useful when the cluster is up but the storage-console UI is down.

```bash
~/homelab/tier-now.sh immich     # tier-mover-immich
~/homelab/tier-now.sh jellyfin   # tier-mover-jellyfin
~/homelab/tier-now.sh backup     # immich-backup
```
