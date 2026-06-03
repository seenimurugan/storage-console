# Immich restic Backup

Incremental, deduplicated backup of **Immich only**, driven by the
`immich-backup` CronJob (namespace `homelab`). The storage-console
**Immich Backup** card controls it (Auto/Manual toggle = `CronJob.spec.suspend`,
*Trigger Now* = `createJobFromCronJob`).

**On this page:** [What it backs up](#what-it-backs-up) · [Repo location](#repo-location) · [Password (3 places)](#password-3-places) · [Retention](#retention) · [Mount guard](#mount-guard) · [Restore runbook](#restore-runbook) · [Manual run](#manual-run)

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
