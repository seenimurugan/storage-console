# Maintenance

**On this page:** [Rebuilding after a code change](#rebuilding-after-a-code-change) · [Rotate admin password](#rotate-admin-password) · [Tearing down](#tearing-down) · [RBAC — what the ServiceAccount can do](#rbac--what-the-serviceaccount-can-do) · [DB access](#db-access) · [Troubleshooting](#troubleshooting)

## Rebuilding after a code change

```bash
cd /path/to/storage-console
$EDITOR .env   # bump BACKEND_TAG / FRONTEND_TAG for immutable tag history
./deploy.sh
```

`deploy.sh` is idempotent and rebuilds + applies + waits for rollouts. With `imagePullPolicy: Never`, OrbStack k3s picks up the new image as soon as the Deployment is patched.

### Manual rebuild without deploy.sh

```bash
# backend only
docker build --platform linux/arm64 -t storage-console-backend:2.0 backend/
kubectl -n homelab rollout restart deployment/storage-console-backend
kubectl -n homelab rollout status  deployment/storage-console-backend

# frontend only
docker build --platform linux/arm64 -t storage-console-frontend:2.0 frontend/
kubectl -n homelab rollout restart deployment/storage-console-frontend
```

## Rotate admin password

The bootstrap admin is only created on first start when no admin exists in the DB. Changing `STORAGE_CONSOLE_ADMIN_PASSWORD` in `.env` and redeploying does NOT change the existing admin row.

To rotate:

```bash
# 1. Generate a bcrypt hash
HASH=$(htpasswd -bnBC 10 "" "newpassword" | tr -d ':\n')

# 2. Update the row
kubectl -n homelab exec shared-postgres-0 -- \
  psql -U storage_console -d storage_console \
  -c "UPDATE app_user SET password_hash='$HASH' WHERE username='admin';"
```

## Tearing down

```bash
./undeploy.sh
# Removes:   Deployments, Services, Ingress, Secret, RBAC
# Preserves: CronJobs (and their Auto/Manual state), DB data, storage-console-postgres-secret
```

To also wipe the database:

```bash
kubectl -n homelab exec shared-postgres-0 -- \
  psql -U postgres -c 'DROP DATABASE storage_console;'
```

To also delete the CronJobs:

```bash
kubectl -n homelab delete cronjob tier-mover-immich tier-mover-jellyfin immich-backup
kubectl -n homelab delete configmap tiered-storage-mover-script
kubectl -n homelab delete sa tiered-storage-mover immich-backup
```

## Immich backup (restic)

The **Immich Backup** card drives the `immich-backup` CronJob, which now does a
restic-based incremental backup (DB dump + SSD library + HDD-tiered originals)
into a repo on the dedicated backup disk. Repo location, the 3-place password
store, retention, the mount guard, and the **restore runbook** are documented
separately:

→ [docs/RESTIC-BACKUP.md](RESTIC-BACKUP.md)

The launchd fallback `~/homelab/backup-immich.sh` and the Jellyfin backup are
independent and untouched.

## RBAC — what the ServiceAccount can do

The `storage-console` ServiceAccount is bound to a Role scoped to the `homelab` namespace.

| Resource | Verbs |
|---|---|
| `batch/cronjobs` | get, list, watch, patch, update |
| `batch/jobs` | get, list, watch, create, delete |
| `core/pods` | get, list |
| `core/pods/log` | get |
| `core/configmaps` | get, patch, update |

It cannot delete CronJobs, access secrets, or operate outside `homelab`. The `configmaps` verbs are used to read/persist the per-app tiering size thresholds in the `tiering-thresholds` ConfigMap.

## DB access

```bash
# psql shell
kubectl -n homelab exec -it shared-postgres-0 -- \
  psql -U storage_console -d storage_console

# tables
\d
# audit log
SELECT * FROM audit_event ORDER BY at DESC LIMIT 20;
# manual run history
SELECT task_id, job_name, trigger_kind, outcome, started_at FROM task_run ORDER BY started_at DESC LIMIT 20;
```

The `task_run` table is currently audit-trail only — the live "last run" badge in the UI is computed from the Kubernetes Job API, not from this table. Future enhancement: a background poller can write completed Jobs into `task_run`.

## Troubleshooting

### CronJob not found in the UI
The card shows a warning like "CronJob not found in cluster". Check that `k8s/40-cronjobs.yaml` was applied:

```bash
kubectl -n homelab get cronjobs
# expect: tier-mover-immich, tier-mover-jellyfin, immich-backup
```

If missing, re-run `./deploy.sh`.

### Manual trigger returns 409 Conflict
The CronJob is currently in Auto mode (`suspend=false`). Switch the card to Manual first.

### Backend is resilient to a missing HDD (does NOT crash)
As of backend `2.5`, the Deployment mounts the **parent** `/Volumes` (hostPath `type: Directory`, always present) at `/hdd-root` with `mountPropagation: HostToContainer`, instead of bind-mounting `/Volumes/homelab-hdd` directly. `HddProbeService` then probes the `/hdd-root/homelab-hdd` subpath.

Why: a direct bind-mount of `/Volumes/homelab-hdd` made the container runtime `mkdir` a missing path → `permission denied` → **CrashLoopBackOff** whenever the HDD was unplugged. Mounting the always-present parent means the backend **always starts**; a missing HDD is reported as `connected:false` (degraded), not a crash.

### HDD shows "not mounted" but the disk is plugged in
With `HostToContainer` propagation, a freshly re-plugged disk appears inside the running pod automatically; the 30 s probe cache (`HddProbeService`) flips the status to connected on the next cycle — **no restart needed**. If it's still wrong after ~1 min:

```bash
# confirm the host sees it
ls /Volumes/homelab-hdd
# confirm the pod sees it
kubectl -n homelab exec deploy/storage-console-backend -- ls /hdd-root/homelab-hdd
# last resort
kubectl -n homelab rollout restart deployment/storage-console-backend
```

Note: the **mover CronJob** pods bind-mount the HDD directly (they legitimately need it) and exit 0 via their `test -d /hdd-check` guard when it's absent — unchanged.

### "FAIL sha256-mismatch" in mover logs
The mover did a sha256 integrity check after copying SSD → HDD and the hashes diverged. Symlink was NOT created; HDD copy was removed. Investigate disk health (`smartctl`) before retrying.

### Backend logs

```bash
kubectl -n homelab logs -f deployment/storage-console-backend
```

### Frontend logs

```bash
kubectl -n homelab logs -f deployment/storage-console-frontend
```

### Rollback to v1 (Go)

```bash
cd /path/to/storage-console
./undeploy.sh
git checkout v1-final
./deploy.sh   # uses v1's deploy.sh
```
