# Storage Console

Homelab storage operations dashboard. Triggers tiering and backup jobs from a browser and streams live pod logs.

## Access

| Method | URL |
|---|---|
| Tailnet URL | https://tier.stoat-perch.ts.net |
| In-cluster | `http://storage-console.homelab.svc.cluster.local:8080` |
| Debug port-forward | `kubectl port-forward -n homelab svc/storage-console 8080:8080` → http://localhost:8080 |
| Auth | None — Tailscale ACLs are the access control |

## Stack

| Layer | Tech |
|---|---|
| Language | Go 1.23, stdlib `net/http` only |
| UI | Vanilla HTML/CSS/JS, embedded via `embed.FS` |
| Job dispatch | `kubectl create job --from=cronjob/<name>` (subprocess) |
| Log streaming | 2 s polling, `kubectl logs`, ANSI stripped |
| Kubernetes auth | ServiceAccount + Role + RoleBinding (least-privilege) |
| Container | Multi-stage Docker: `golang:1.23-alpine` → `alpine:3.20` + kubectl |
| Ingress | Tailscale ingress controller (`ingressClassName: tailscale`) |

## Source repository

https://github.com/seenimurugan/storage-console

## Actions supported

| Action ID | CronJob | What it does |
|---|---|---|
| `move-images` | `tier-mover-immich` | Moves Immich files > 2 GiB from SSD to HDD |
| `move-movies` | `tier-mover-jellyfin` | Moves Jellyfin files > 3 GiB from SSD to HDD |
| `backup-immich` | `immich-backup` | Runs pg_dump + tarball of Immich to HDD |
