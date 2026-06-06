# Storage Console

Homelab storage operations dashboard. Three cards, each toggling and triggering a Kubernetes CronJob that moves large files to the HDD or backs up Immich. Custom app built on Spring Boot 3 + Next.js 15 (rewritten from Go v1).

Source: `/Users/nila/Developer/apps/storage-console/`

**On this page:** [Access](#access) · [Initial credentials](#initial-credentials) · [What it does](#what-it-does) · [Stack & framework](#stack--framework) · [Storage](#storage) · [See also](#see-also) · [File reference](#file-reference)

---

## Access

| Where | URL |
|---|---|
| **Phone / family on Tailscale** | https://tier.stoat-perch.ts.net |
| **Cluster DNS — frontend** (other pods / Mac shell) | http://storage-console-frontend.homelab.svc.cluster.local |
| **Cluster DNS — backend API** (curl / Postman) | http://storage-console-backend.homelab.svc.cluster.local:8080 |
| **Ad-hoc debug port-forward (UI)** | `kubectl -n homelab port-forward svc/storage-console-frontend 3000:3000` → http://localhost:3000 |
| **Ad-hoc debug port-forward (API)** | `kubectl -n homelab port-forward svc/storage-console-backend 8080:8080` → http://localhost:8080 |

---

## Initial credentials

| | |
|---|---|
| User | `admin` |
| Password | `admin` |

**Change immediately** — see [Maintenance → Rotate admin password](MAINTENANCE.md#rotate-admin-password).

---

## What it does

| Card | CronJob controlled | Trigger model |
|---|---|---|
| Immich Tier | `tier-mover-immich` | `suspend=false` → runs daily at 3 AM; `suspend=true` → manual-only |
| Jellyfin Tier | `tier-mover-jellyfin` | same |
| Immich Backup | `immich-backup` | same |

Each tier card has a **configurable size threshold** (GiB, decimals; default 1 GiB) — only files larger than it are moved to the HDD. Each card also shows the CronJob's current state (Auto/Manual), the last run time, and a "Trigger Now" button that fires the job immediately. The backend reads and patches CronJob resources (and the `tiering-thresholds` ConfigMap) via the fabric8 Kubernetes client.

---

## Stack & framework

| Layer | Tech |
|---|---|
| Backend | Java 21 + **Spring Boot 3.4** (web, data-jpa, security, actuator) |
| K8s client | fabric8 `io.fabric8:kubernetes-client` 6.13 (in-cluster ServiceAccount) |
| Auth | **JWT** (HS256) + bcrypt |
| Migrations | **Flyway** |
| Database | **Shared cluster Postgres** — `shared-postgres.homelab.svc.cluster.local`, database `storage_console`. See [Shared Postgres](../shared-postgres/README.md). |
| Frontend | **Next.js 15** (App Router, standalone) + React 19 + TypeScript |
| Styling | **Tailwind CSS** |
| Deploy | Kubernetes (`homelab` namespace), Tailscale Ingress, path-routed |

---

## Storage

This app does not own its database — it uses the cluster's [Shared Postgres](../shared-postgres/README.md) at `shared-postgres.homelab.svc.cluster.local:5432`, database `storage_console`.

| | |
|---|---|
| Server | `shared-postgres.homelab.svc.cluster.local:5432` |
| Database | `storage_console` |
| User | `storage_console` |
| Password | `STORAGE_CONSOLE_PASSWORD` in `.env` (also in `storage-console-postgres-secret`) |

Backend and frontend pods are stateless — no PVCs in this app's manifests.

CronJobs owned by this app: `tier-mover-immich`, `tier-mover-jellyfin`, `immich-backup` (defined in `k8s/40-cronjobs.yaml`). The legacy `cluster-setup/50-tiered-storage-mover.yaml` is deprecated — apply only via storage-console's `./deploy.sh`.

The `hdd-healer` CronJob was removed in v2.7. Immich and Jellyfin self-heal HDD unplug/replug automatically via a propagation-safe hostPath mount (`/Volumes` → `/hdd-root`, `mountPropagation: HostToContainer`) — no pod restart or healer is needed.

---

## See also

- [Maintenance](MAINTENANCE.md) — restart, rotate password, CronJob debugging
- [Architecture](ARCHITECTURE.md) — request flow, Kubernetes RBAC, CronJob ownership

## File reference

| File | Purpose |
|---|---|
| `/Users/nila/Developer/apps/storage-console/k8s/10-backend.yaml` | Backend Deployment + Service + Secret |
| `/Users/nila/Developer/apps/storage-console/k8s/20-frontend.yaml` | Frontend Deployment + Service |
| `/Users/nila/Developer/apps/storage-console/k8s/30-ingress.yaml` | Tailscale Ingress (path-routed) |
| `/Users/nila/Developer/apps/storage-console/k8s/40-cronjobs.yaml` | The three CronJobs this app controls |
