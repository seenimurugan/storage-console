# storage-console

Homelab storage operations dashboard — trigger tiering and backup jobs from a browser, then watch the live logs. Lives at https://tier.stoat-perch.ts.net on the Tailnet.

## What it does

Three buttons that fire `kubectl create job --from=cronjob/<name>` inside the cluster, then live-tail the pod logs with 2 s polling:

| Button | CronJob triggered |
|---|---|
| Move bigger images | `tier-mover-immich` — Immich files > 2 GiB, SSD → HDD |
| Move bigger movies | `tier-mover-jellyfin` — Jellyfin files > 3 GiB, SSD → HDD |
| Backup Immich | `immich-backup` — pg_dump + tarball → HDD |

The CronJobs must already exist in namespace `homelab` with `suspend: true`. The backend validates they exist before creating a Job.

## Depends on

- **cluster-setup** — `homelab` namespace, Tailscale ingress controller: [`github.com/seenimurugan/homelab-cluster-setup`](https://github.com/seenimurugan/homelab-cluster-setup)
- **tiering CronJobs** — `tier-mover-immich`, `tier-mover-jellyfin`, `immich-backup` (created by the storage-tiering setup)

## Quick start

```bash
git clone https://github.com/seenimurugan/storage-console
cd storage-console

# 1. Set up your env
cp .env.example .env
$EDITOR .env   # verify HOMELAB_HDD_PATH, HOMELAB_TIER_HDD_PATH, STORAGE_CONSOLE_TAG

# 2. Build and deploy (builds arm64 image, applies manifests via envsubst)
./deploy.sh
```

`deploy.sh` is idempotent — safe to re-run. It verifies both HDD mounts are present, builds the image, and waits for the rollout.

## Access

| | |
|---|---|
| **Tailnet URL** | https://tier.stoat-perch.ts.net |
| **Auth** | None — Tailscale ACLs are the access control |
| **Debug port-forward** | `kubectl port-forward -n homelab svc/storage-console 8080:8080` → http://localhost:8080 |

## Tear down

```bash
./undeploy.sh   # removes deployment/service/ingress/RBAC; preserves CronJobs
```

## Docs

- [docs/README.md](docs/README.md) — app overview, access URLs, stack summary
- [docs/USAGE.md](docs/USAGE.md) — running jobs, reading logs, troubleshooting
- [docs/MAINTENANCE.md](docs/MAINTENANCE.md) — rebuilding, updating image tag, RBAC, debug commands
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — request flow, embed.FS, kubectl exec model, RBAC design

Also rendered live at https://docs.stoat-perch.ts.net (sidebar → Storage Console).
