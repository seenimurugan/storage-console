# Maintenance

## Rebuilding after a code change

```bash
cd /path/to/storage-console
cp .env.example .env   # if not already done
$EDITOR .env           # bump STORAGE_CONSOLE_TAG (e.g. 0.1 → 0.2)
./deploy.sh
```

`deploy.sh` builds the image (`imagePullPolicy: Never`; OrbStack k3s reuses the Mac Docker daemon), then applies and waits for rollout.

### Manual rebuild without deploy.sh

```bash
docker build --platform linux/arm64 -t storage-console:0.2 .
kubectl set image deployment/storage-console storage-console=storage-console:0.2 -n homelab
kubectl -n homelab rollout status deployment/storage-console
```

## Compiling locally (no Docker)

```bash
go mod tidy
go build ./cmd/storage-console
# Binary: ./storage-console (uses ~/.kube/config for kubectl)
./storage-console
# open http://localhost:8080
```

## Tearing down

```bash
./undeploy.sh
# Removes: Deployment, Service, Ingress, Role, RoleBinding, ServiceAccount
# Keeps:   CronJobs (tier-mover-immich, tier-mover-jellyfin, immich-backup)
```

## RBAC — what the ServiceAccount can do

The `storage-console` ServiceAccount is bound to a Role scoped to the `homelab` namespace with the minimum permissions required:

| Resource | Verbs |
|---|---|
| `batch/jobs` | create, get, list |
| `core/pods` | get, list |
| `core/pods/log` | get |
| `batch/cronjobs` | get |

It cannot delete jobs, access secrets, or operate outside the `homelab` namespace.

## Adding a new action

1. In `internal/handlers/handlers.go`, add an entry to `actionToCronJob`:
   ```go
   "my-action": "my-cronjob-name",
   ```
2. In `web/index.html`, add a button calling `runAction('my-action')`.
3. Ensure the CronJob `my-cronjob-name` exists with `suspend: true` in the cluster.
4. Rebuild and deploy.

## Debug commands

```bash
# Check rollout history
kubectl -n homelab rollout history deployment/storage-console

# Roll back one version
kubectl -n homelab rollout undo deployment/storage-console

# Describe pod (events, resource usage)
kubectl -n homelab describe pod -l app=storage-console

# Live log stream
kubectl -n homelab logs -l app=storage-console -f

# Exec into pod
kubectl -n homelab exec -it deploy/storage-console -- sh
```
