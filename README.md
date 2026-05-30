# storage-console

Homelab storage operations console — Spring Boot 3 backend + Next.js 15 frontend on Kubernetes. Lives at https://tier.stoat-perch.ts.net on the Tailnet.

## What it does

Three cards on a dashboard, each backed by a Kubernetes CronJob. For every card you can:

- toggle **Auto / Manual** (persisted as `CronJob.spec.suspend`)
- click **Trigger Now** in manual mode (spawns a one-shot Job from the CronJob template)
- see the **last run** outcome, **next run** time, and an **HDD-connected** indicator

| Card | CronJob | What it does |
|---|---|---|
| Immich Tier (> 2 GB) | `tier-mover-immich` | Move Immich files larger than 2 GiB from SSD to HDD |
| Jellyfin Tier (> 3 GB) | `tier-mover-jellyfin` | Move Jellyfin media larger than 3 GiB from SSD to HDD |
| Immich Backup | `immich-backup` | pg_dump + library tar → HDD `backups/` |

Each CronJob's pod starts with an HDD probe — if the HDD is not mounted on the host, the job exits 0 silently rather than failing noisily.

## Stack

| Layer | Tech |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Spring Security (JWT), Spring Data JPA, Flyway |
| Frontend | Next.js 15, React 19, TypeScript, Tailwind |
| Database | shared-postgres (homelab namespace) — db `storage_console` |
| K8s integration | fabric8 Kubernetes Java client (in-cluster ServiceAccount + Role) |
| Ingress | Tailscale ingress controller (`tier.stoat-perch.ts.net`) |

## Depends on

- **cluster-setup** — `homelab` namespace, Tailscale ingress controller, shared-postgres
- **HDD** — `/Volumes/homelab-hdd` mounted on the Mac host with subdirs `immich-library/`, `jellyfin-media/`, `backups/postgres/`, `backups/library/`

## Quick start

```bash
git clone https://github.com/seenimurugan/storage-console
cd storage-console

# 1. Set up your env
cp .env.example .env
$EDITOR .env

# 2. Deploy (builds both images, applies k8s, waits for rollouts)
./deploy.sh
```

`deploy.sh` is idempotent — safe to re-run. It builds images locally (OrbStack reuses the Mac Docker daemon), creates the storage-console DB+user in shared-postgres on first run, and waits for both rollouts.

## Access

| | |
|---|---|
| **Tailnet URL** | https://tier.stoat-perch.ts.net |
| **Admin login** | `admin` / see `.env` (`STORAGE_CONSOLE_ADMIN_PASSWORD`) |
| **Debug port-forward** | `kubectl -n homelab port-forward svc/storage-console-frontend 3000:3000` |

Rotate the admin password on first deploy — see [docs/MAINTENANCE.md](docs/MAINTENANCE.md#rotate-admin-password).

## Tear down

```bash
./undeploy.sh
# Removes Deployments/Services/Ingress/RBAC/Secrets.
# Preserves: CronJobs (and their .spec.suspend mode), DB data.
```

## Docs

- [docs/README.md](docs/README.md) — access URLs, in-cluster URLs, DB info
- [docs/USAGE.md](docs/USAGE.md) — how to set mode, trigger manually, read status
- [docs/MAINTENANCE.md](docs/MAINTENANCE.md) — Postgres access, rebuilds, RBAC, troubleshooting
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — request flow, data model, REST API, k8s integration

Also rendered live at https://docs.stoat-perch.ts.net (sidebar → Storage Console).

## History

The v1 Go implementation is preserved at the `v1-final` git tag. To inspect:

```bash
git show v1-final:cmd/storage-console/main.go
git checkout v1-final   # full v1 source
```
