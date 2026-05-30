# Storage Console

Homelab storage operations dashboard. Three cards, each toggling and triggering a Kubernetes CronJob that moves large files to the HDD or backs up Immich. Rewritten in v2 as Spring Boot 3 + Next.js 15 (was Go in v1).

## Access

| | |
|---|---|
| **Tailnet URL** | https://tier.stoat-perch.ts.net |
| **In-cluster URL** | `http://storage-console-frontend.homelab.svc.cluster.local:3000` (UI) / `http://storage-console-backend.homelab.svc.cluster.local:8080` (API) |
| **Debug port-forward (UI)** | `kubectl -n homelab port-forward svc/storage-console-frontend 3000:3000` → http://localhost:3000 |
| **Debug port-forward (API)** | `kubectl -n homelab port-forward svc/storage-console-backend 8080:8080` → http://localhost:8080 |
| **Admin login** | `admin` / value of `STORAGE_CONSOLE_ADMIN_PASSWORD` in `.env` |

## Stack

| Layer | Tech |
|---|---|
| Backend | Java 21, Spring Boot 3.4, Spring Security (JWT), Spring Data JPA, Flyway |
| K8s client | fabric8 `io.fabric8:kubernetes-client` 6.13 (in-cluster ServiceAccount) |
| Frontend | Next.js 15, React 19, TypeScript, Tailwind 3 |
| Database | shared-postgres @ `shared-postgres.homelab.svc.cluster.local:5432` |
| Ingress | Tailscale ingress class — `host: tier` → `tier.stoat-perch.ts.net` |
| Containers | OrbStack-local images (`imagePullPolicy: Never`) |

## Database

| | |
|---|---|
| Server | `shared-postgres.homelab.svc.cluster.local:5432` |
| Database | `storage_console` |
| User | `storage_console` |
| Password | `STORAGE_CONSOLE_PASSWORD` in `.env` (also in `shared-postgres-secret`) |
| Migrations | Flyway, `backend/src/main/resources/db/migration/V*.sql` |

```bash
# psql shell
kubectl -n homelab exec -it shared-postgres-0 -- \
  psql -U storage_console -d storage_console
```

## Source

- Repo: https://github.com/seenimurugan/storage-console
- v1 (Go) is preserved at tag `v1-final`

## What each card controls

| Card | CronJob | Trigger model |
|---|---|---|
| Immich Tier (>2 GB) | `tier-mover-immich` | suspend=false → daily 3 AM; suspend=true → manual only |
| Jellyfin Tier (>3 GB) | `tier-mover-jellyfin` | same |
| Immich Backup | `immich-backup` | same |

CronJob ownership: storage-console v2 owns these three CronJobs (moved out of cluster-setup in v2). The previous `cluster-setup/50-tiered-storage-mover.yaml` is now deprecated — apply only via storage-console's `./deploy.sh`.
