# Immich restic Backup

Incremental, deduplicated backup of **Immich only**, driven by the
`immich-backup` CronJob (namespace `homelab`). The storage-console
**Immich Backup** card controls it (Auto/Manual toggle = `CronJob.spec.suspend`,
*Trigger Now* = `createJobFromCronJob`).

**On this page:** [What it backs up](#what-it-backs-up) · [Repo location](#repo-location) · [Password (3 places)](#password-3-places) · [Retention](#retention) · [Mount guard](#mount-guard) · [Concurrency model](#concurrency-model) · [Stale-lock recovery](#stale-lock-recovery) · [Restore runbook](#restore-runbook) · [Manual run](#manual-run)

## What it backs up

One restic snapshot per run (`--host immich --tag weekly`), containing:

| Path in snapshot | Source | Mount |
| --- | --- | --- |
| `/ssd-immich` | SSD Immich library (`immich-upload-localpath-pvc`) | RO |
| `/hdd-immich`  | HDD-tiered Immich originals (`${HOMELAB_TIER_HDD_PATH}/immich-library`) | RO |
| `/work/immich-db.sql` | `pg_dump` of the `immich` Postgres DB (init container) | shared emptyDir |

restic dedups at the block level, so each run after the first only stores
**new/changed** blocks (true incremental).

## Repo location

```
${HOMELAB_HDD_PATH}/restic-immich   →  /Volumes/homelab-backup-hdd/restic-immich
```

This is the **dedicated 3 TB backup disk**, NOT the tier disk. The repo is a
plain restic local repo (`RESTIC_REPOSITORY=/repo` inside the pod).

## Password (3 places)

**CRITICAL: if the password is lost the repo is UNRECOVERABLE.** It lives in:

1. **Cluster Secret** `restic-immich-secret` (key `RESTIC_PASSWORD`), namespace `homelab`.
2. **Host file** `~/.config/immich/restic-password` (chmod 600). `deploy.sh` reads
   this when the Secret does not yet exist.
3. **`.env.example`** carries an empty `RESTIC_PASSWORD=` placeholder only — the
   real value is **never** committed.

Generate with `openssl rand -base64 32`. `deploy.sh` precedence:
existing Secret (untouched) > `$RESTIC_PASSWORD` env > host file.

## Retention

```
restic forget --keep-weekly 8 --keep-monthly 24 --prune
```

8 weekly + 24 monthly snapshots, pruned each run.

## Mount guard

Before any restic write the job requires the sentinel
`${HOMELAB_HDD_PATH}/.homelab-backup-hdd` (mounted RO at `/backup-root`). If it
is absent — disk unplugged, or the hostPath silently fell back to the pod's
ephemeral overlay fs — the job logs
`event=immich.backup.guard ... outcome=fail` and **exits non-zero without
running restic**. `deploy.sh` drops the marker on the real disk.

## Concurrency model

Three overlapping concerns — Trigger Now guard, scheduled self-overlap, and
manual+scheduled overlap — are handled at different levels:

### Trigger Now guard (HTTP 409)

The storage-console backend enforces a concurrency check in the **Trigger Now**
path (`TaskController.trigger` → `KubernetesService.activeJobsForCronJob`):

> Before creating a Job it counts Jobs belonging to the target CronJob that are
> still in-flight (`status.active > 0`, or not yet succeeded/failed). If any are
> live, **Trigger Now is rejected with HTTP 409** and the JSON body contains
> `"message":"Backup already running — wait for it to finish."` No new Job is
> created.

This is generic across all storage-console tasks (tier movers too), not just
`immich-backup`. The decision is logged:
`event=task.trigger.activecheck ... activeJobs=<n>` then either
`event=task.trigger.created ... outcome=201` or
`event=task.trigger.rejected ... reason=already-running`.

### Scheduled self-overlap (CronJob `concurrencyPolicy: Forbid`)

The `immich-backup` CronJob is configured with `concurrencyPolicy: Forbid`.
If a scheduled run is still active when the next schedule tick fires, the
Kubernetes CronJob controller skips that tick entirely — no second Job is
created, no error is surfaced. This prevents the scheduler from piling up runs
when a backup takes longer than its interval.

### Manual + scheduled overlap (safe but slower)

If a **manual Trigger Now Job** is already running when the next **scheduled
CronJob tick** fires, `concurrencyPolicy: Forbid` applies to the CronJob's own
Jobs only — it does **not** suppress a manually created Job. So a manual+scheduled
overlap is theoretically possible. However:

- restic uses **non-exclusive locks** for `restic backup` — two concurrent backups
  to the same repo will not corrupt it; they will each take a lock, deduplicate
  independently against the existing pack files, and both complete successfully.
- The only cost is speed: both runs compete for I/O and may be slower than a
  single sequential run.
- `restic unlock` only clears **stale locks** (from dead processes); it has no
  effect on locks held by a live process, so a concurrent run is never
  prematurely unlocked.

## Stale-lock recovery

If a backup pod is killed mid-run (OOM, node reboot, eviction) restic leaves a
**stale lock** in the repo, and the next run would refuse to start. Because the
concurrency guard above already prevents a *real* second run from overlapping a
live one, any lock seen at the start of a run must belong to a dead process — so
the CronJob script runs `restic unlock` before `restic backup`:

```sh
log "event=immich.backup.unlock outcome=begin reason=clear-stale-locks"
if restic unlock; then
  log "event=immich.backup.unlock outcome=success reason=stale-locks-cleared"
else
  log "event=immich.backup.unlock outcome=warn reason=unlock-nonzero-continuing"
fi
```

`restic unlock` only removes **stale** locks (not locks held by a live process),
so this is safe. The script keeps `set -eu`; a non-zero `unlock` is downgraded to
a warning so a transient hiccup doesn't abort the whole run.

## Restore runbook

Run a throwaway pod that mounts the repo RO and sets `RESTIC_PASSWORD`.

```bash
RPW=$(kubectl -n homelab get secret restic-immich-secret \
        -o jsonpath='{.data.RESTIC_PASSWORD}' | base64 -d)

kubectl -n homelab run restic-restore --rm -it --restart=Never \
  --image=alpine:3.21 --overrides='
{"spec":{"securityContext":{"runAsUser":0},
 "containers":[{"name":"r","image":"alpine:3.21","stdin":true,"tty":true,
   "command":["/bin/sh"],
   "env":[{"name":"RESTIC_REPOSITORY","value":"/repo"},
          {"name":"RESTIC_PASSWORD","value":"'"$RPW"'"}],
   "volumeMounts":[{"name":"repo","mountPath":"/repo","readOnly":true}]}],
 "volumes":[{"name":"repo","hostPath":{"path":"/Volumes/homelab-backup-hdd/restic-immich","type":""}}]}}'

# inside the pod:
apk add --no-cache restic
restic snapshots --compact
restic ls latest | head                       # browse
restic restore latest --include /work/immich-db.sql --target /tmp/r   # DB dump
restic restore latest --include /ssd-immich/<path> --target /tmp/r    # one file
restic restore latest --target /restore-here                          # full
```

Restore the Postgres dump into a fresh DB with
`psql -U <user> -d immich -f /tmp/r/work/immich-db.sql` (the dump was taken with
`--clean --if-exists`).

## Manual run

```bash
# via UI: storage-console → Immich Backup card → Trigger Now
# via CLI:
kubectl -n homelab create job --from=cronjob/immich-backup immich-backup-manual
kubectl -n homelab logs -f -l job-name=immich-backup-manual -c restic
```

The launchd fallback `~/homelab/backup-immich.sh` (tar+zstd) is **separate** and
untouched by this rework.
