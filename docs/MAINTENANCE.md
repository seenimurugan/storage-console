# Maintenance

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
# Preserves: CronJobs (and their Auto/Manual state), DB data, shared-postgres-secret
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

## RBAC — what the ServiceAccount can do

The `storage-console` ServiceAccount is bound to a Role scoped to the `homelab` namespace.

| Resource | Verbs |
|---|---|
| `batch/cronjobs` | get, list, watch, patch, update |
| `batch/jobs` | get, list, watch, create, delete |
| `core/pods` | get, list |
| `core/pods/log` | get |

It cannot delete CronJobs, access secrets, or operate outside `homelab`.

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

### HDD shows "not mounted" but the disk is plugged in
The backend pod mounts `/Volumes/homelab-hdd` as a hostPath. OrbStack VM may not see the mount immediately after macOS finishes auto-mounting. Restart the backend:

```bash
kubectl -n homelab rollout restart deployment/storage-console-backend
```

Also see memory note `feedback_orbstack_hdd_mount_limit.md` — first-mount can ENFILE. If the deployment is in a `CreateContainerConfigError`, unplug + replug the HDD and `kubectl rollout restart`.

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
