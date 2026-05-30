# Architecture

## Overview

storage-console is a single Go binary that embeds a vanilla JS UI and exposes a small HTTP API. There is no database and no external dependencies — only Go stdlib and `kubectl` (bundled in the container image).

## Request flow

```
Browser (Tailnet)
    │
    │  HTTPS
    ▼
Tailscale Ingress (tier.stoat-perch.ts.net)
    │
    │  HTTP :8080
    ▼
storage-console Pod (storage-console ServiceAccount)
    │
    ├── GET  /            → embedded index.html (embed.FS)
    ├── GET  /static/*    → embedded CSS/JS (embed.FS)
    ├── GET  /api/healthz → "ok\n"
    │
    ├── POST /api/run/{action}
    │       └── validates CronJob exists → kubectl create job --from=cronjob/<name>
    │           returns { jobName, namespace }
    │
    ├── GET  /api/jobs/{name}/status
    │       └── kubectl get job <name> -o json → phase/startedAt/completedAt
    │
    └── GET  /api/jobs/{name}/logs
            └── kubectl get pods -l job-name=<name>
                kubectl logs <pod> --tail=200 → stripped of ANSI codes
```

## Static assets — embed.FS

```
web/
  web.go       // package web; //go:embed index.html style.css app.js → var FS embed.FS
  index.html   // single page: 3 action buttons + log output area
  style.css    // mobile-first dark theme
  app.js       // fetch wrapper, 2 s polling loop, sessionStorage job persistence
```

The `embed.FS` is compiled into the binary at build time. No runtime file reads needed.

## HTTP mux (cmd/storage-console/main.go)

| Route | Handler |
|---|---|
| `GET /` | Rewrites path to `/index.html`, serves from embed.FS |
| `GET /static/*` | Strip prefix, serve from embed.FS |
| `GET /api/healthz` | `handlers.Healthz` |
| `POST /api/run/{action}` | `handlers.RunAction` |
| `GET /api/jobs/{name}/status` | `handlers.JobStatusHandler` |
| `GET /api/jobs/{name}/logs` | `handlers.JobLogsHandler` |

## kubectl exec model

The backend runs `kubectl` as a subprocess (`os/exec`). Inside the cluster the `kubectl` binary (bundled in the image) uses the ServiceAccount token at `/var/run/secrets/kubernetes.io/serviceaccount/` automatically.

For local development (`go build ./cmd/storage-console`), kubectl falls back to `~/.kube/config`.

## Namespace coupling

`internal/handlers/handlers.go` has a package-level constant:

```go
const namespace = "homelab"
```

The namespace is not configurable at runtime. To change it, update this constant and rebuild. The k8s manifest now uses `${HOMELAB_NAMESPACE}` (substituted by `deploy.sh` via `envsubst`).

## RBAC design

The `storage-console` ServiceAccount has a namespace-scoped Role (not ClusterRole). This is intentional — the app only needs to operate within `homelab`. Granting cluster-wide access would be over-privileged for an admin console with no authentication layer beyond Tailscale.

## Container image

Multi-stage Dockerfile:

| Stage | Base | Purpose |
|---|---|---|
| `builder` | `golang:1.23-alpine` | Compile Go binary with `CGO_ENABLED=0` |
| runtime | `alpine:3.20` | Run binary; install `kubectl` from dl.k8s.io |

The runtime image pulls the latest stable `kubectl` at build time (`curl -sSL https://dl.k8s.io/release/stable.txt`). Pin the kubectl version in the Dockerfile if reproducible builds are needed.

## Security posture

- No credentials stored in the app — cluster access is via RBAC on the ServiceAccount.
- No user authentication — access control is Tailscale ACLs.
- All kubectl output is read-only to the UI; the only mutation is `create job`.
- ANSI escape codes are stripped from logs before sending to the browser to prevent terminal injection via crafted job output.
