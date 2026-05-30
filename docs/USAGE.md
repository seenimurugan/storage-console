# Usage

## Running a job

1. Open https://tier.stoat-perch.ts.net (must be on Tailnet).
2. Click one of the three action buttons:
   - **Move bigger images** — triggers `tier-mover-immich`
   - **Move bigger movies** — triggers `tier-mover-jellyfin`
   - **Backup Immich** — triggers `immich-backup`
3. The UI shows a spinner while the Job is pending, then streams the pod logs in real time (2 s polling).
4. Status transitions: `pending → running → succeeded / failed`.

The job name is persisted in `sessionStorage` so a page refresh keeps tracking the same job within the same browser session.

## Prerequisites

The following CronJobs must exist in the `homelab` namespace with `suspend: true` before the console can trigger them:

```bash
kubectl -n homelab get cronjobs
# Expected:
#   NAME                   SCHEDULE    SUSPEND   ACTIVE
#   tier-mover-immich      0 2 * * *   True      0
#   tier-mover-jellyfin    0 3 * * *   True      0
#   immich-backup          0 4 * * *   True      0
```

The backend validates CronJob existence before creating a Job and returns HTTP 503 if one is missing.

## Checking job status manually

```bash
# List all jobs in homelab namespace
kubectl -n homelab get jobs

# Watch a specific job
kubectl -n homelab get job move-images-<timestamp> -w

# Get pod logs for a job
kubectl -n homelab logs -l job-name=move-images-<timestamp> --tail=100
```

## Healthcheck

```bash
# Via port-forward
kubectl -n homelab port-forward svc/storage-console 8080:8080 &
curl -s http://localhost:8080/api/healthz
# Expected: ok
```

## Troubleshooting

```bash
# Pod not starting?
kubectl -n homelab describe pod -l app=storage-console

# Job not created — CronJob missing?
kubectl -n homelab get cronjobs

# RBAC error when triggering a job?
kubectl -n homelab describe rolebinding storage-console

# App logs (last 50 lines, follow)
kubectl -n homelab logs -l app=storage-console --tail=50 -f
```
