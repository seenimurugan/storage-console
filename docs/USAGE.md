# Usage

**On this page:** [Logging in](#logging-in) · [The dashboard](#the-dashboard) · [Setting Auto mode](#setting-auto-mode) · [Triggering a manual run](#triggering-a-manual-run) · [Two concurrent runs](#two-concurrent-runs) · [Checking from the CLI](#checking-from-the-cli) · [Fallback: the `tier-now.sh` script](#fallback-the-`tier-nowsh`-script)

## Logging in

1. Open https://tier.stoat-perch.ts.net (Tailnet required).
2. Username `admin`, password from `.env` (`STORAGE_CONSOLE_ADMIN_PASSWORD`).
3. The JWT is stored in `localStorage.storage-console.token` and lasts 30 days.

## The dashboard

Three cards, one per storage operation:

| Card | Threshold | Source → destination |
|---|---|---|
| Immich Tier | configurable (default 1 GiB) | Immich SSD `library/` + `encoded-video/` → HDD `immich-library/` |
| Jellyfin Tier | configurable (default 1 GiB) | Jellyfin SSD `movies/`, `tvshows/`, `music/` → HDD `jellyfin-media/` |
| Immich Backup | — | pg_dump + library tar → HDD `backups/postgres/` and `backups/library/` |

Each card shows:

- **Size threshold (tier cards only)** — a numeric input (GiB, decimals allowed) "Move files larger than (GiB)". Only files **larger** than this are tiered to the HDD. Type a value, click **Save**; it persists immediately and applies to both the manual *Trigger Now* run and the scheduled Auto run. The Immich Backup card has no threshold. See [Setting the size threshold](#setting-the-size-threshold).
- **Mode toggle** — Auto or Manual. State is stored as `CronJob.spec.suspend` (Auto = false, Manual = true). Kubernetes is the source of truth — there is no DB column for mode.
- **Trigger Now** — only enabled in Manual mode. Spawns a Job with name `<cronjob>-manual-<utc-timestamp>` and opens a live log viewer that polls every 2 s.
- **Next run** — for Auto mode, the next time the CronJob will fire (assumes `0 H * * *` daily schedule).
- **Last run** — most recent Job's outcome (succeeded / failed / running / pending), with a relative timestamp. Click "view logs" to see the last 500 log lines.

The top bar shows a global **HDD connected / not mounted** indicator, refreshed when the page reloads tasks (every 15 s).

## Setting the size threshold

Each tier card (Immich, Jellyfin) has a **Move files larger than (GiB)** input. Only files strictly larger than the threshold are moved to the HDD; smaller files stay on the SSD.

1. Type a value in GiB — decimals are allowed (e.g. `0.5`, `1`, `1.5`, `2.5`). Must be greater than 0.
2. Click **Save**. A green "Saved" confirmation appears.
3. The value is stored in the `tiering-thresholds` ConfigMap (keys `immich-threshold-bytes` / `jellyfin-threshold-bytes`, in bytes) and is read by both the manual *Trigger Now* run and the scheduled Auto run on their next invocation. No redeploy needed.

Immich and Jellyfin thresholds are independent. Defaults are seeded at 1 GiB each on first deploy.

```bash
# Inspect / set from the CLI
kubectl -n homelab get configmap tiering-thresholds -o jsonpath='{.data}'
kubectl -n homelab patch configmap tiering-thresholds --type merge \
  -p '{"data":{"immich-threshold-bytes":"1610612736"}}'   # 1.5 GiB
```

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
