#!/usr/bin/env bash
# deploy.sh — idempotent deploy for storage-console v2
#   builds backend + frontend images, ensures storage-console DB in shared-postgres,
#   applies k8s manifests via envsubst, and waits for rollouts.
# Safe to re-run.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── 1. Load .env ─────────────────────────────────────────────────────────────
ENV_FILE="$SCRIPT_DIR/.env"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: .env not found."
  echo "       Copy .env.example to .env and fill in real values, then re-run."
  echo "         cp .env.example .env && \$EDITOR .env"
  exit 1
fi
# shellcheck disable=SC1090
set -a; source "$ENV_FILE"; set +a

: "${HOMELAB_NAMESPACE:=homelab}"
: "${BACKEND_IMAGE:=storage-console-backend}"
: "${BACKEND_TAG:=2.0}"
: "${FRONTEND_IMAGE:=storage-console-frontend}"
: "${FRONTEND_TAG:=2.0}"
: "${HOMELAB_TIER_HDD_PATH:=/Volumes/homelab-hdd}"
: "${HOMELAB_HDD_PATH:=/Volumes/homelab-backup-hdd}"
: "${IMMICH_TIER_SCHEDULE:=0 3 * * *}"
: "${JELLYFIN_TIER_SCHEDULE:=0 3 * * *}"
: "${IMMICH_BACKUP_SCHEDULE:=0 4 * * *}"
: "${DB_BACKUP_SCHEDULE:=0 5 * * *}"
export HOMELAB_NAMESPACE BACKEND_IMAGE BACKEND_TAG FRONTEND_IMAGE FRONTEND_TAG \
       HOMELAB_TIER_HDD_PATH HOMELAB_HDD_PATH \
       IMMICH_TIER_SCHEDULE JELLYFIN_TIER_SCHEDULE IMMICH_BACKUP_SCHEDULE DB_BACKUP_SCHEDULE \
       STORAGE_CONSOLE_JWT_SECRET STORAGE_CONSOLE_ADMIN_USERNAME STORAGE_CONSOLE_ADMIN_PASSWORD \
       STORAGE_CONSOLE_ADMIN_DISPLAY_NAME

# ── 2. Prereq checks ─────────────────────────────────────────────────────────
for tool in kubectl docker envsubst; do
  if ! command -v "$tool" &>/dev/null; then
    case "$tool" in
      envsubst) echo "ERROR: envsubst not found. Install via: brew install gettext" ;;
      *)        echo "ERROR: $tool not found in PATH." ;;
    esac
    exit 1
  fi
done
if ! kubectl cluster-info &>/dev/null; then
  echo "ERROR: Cannot reach the Kubernetes cluster. Is OrbStack running?"
  exit 1
fi
if ! docker info &>/dev/null; then
  echo "ERROR: Cannot reach the Docker daemon. Is OrbStack running?"
  exit 1
fi

# ── 3. Namespace ─────────────────────────────────────────────────────────────
if ! kubectl get namespace "$HOMELAB_NAMESPACE" &>/dev/null; then
  echo "[deploy] Namespace '$HOMELAB_NAMESPACE' not found — creating."
  kubectl create namespace "$HOMELAB_NAMESPACE"
fi

# ── 4. Build images ──────────────────────────────────────────────────────────
echo ""
echo "[deploy] (1/8) Building images (linux/arm64 — OrbStack k3s shares Mac Docker)"
echo "  → $BACKEND_IMAGE:$BACKEND_TAG"
docker build --platform linux/arm64 -t "$BACKEND_IMAGE:$BACKEND_TAG" -t "$BACKEND_IMAGE:latest" "$SCRIPT_DIR/backend"
echo "  → $FRONTEND_IMAGE:$FRONTEND_TAG"
docker build --platform linux/arm64 -t "$FRONTEND_IMAGE:$FRONTEND_TAG" -t "$FRONTEND_IMAGE:latest" "$SCRIPT_DIR/frontend"

# ── 5. Ensure storage-console-postgres-secret has STORAGE_CONSOLE_* keys ─────
# Dedicated per-app secret (split from shared-postgres-secret on 2026-06-01).
# JSON Patch (RFC 6902) on existing secrets so re-runs never stomp other keys.
echo ""
echo "[deploy] (2/8) Ensuring storage-console-postgres-secret has STORAGE_CONSOLE_* keys (JSON Patch, no stomp)"
_SC_DB_B64=$(printf '%s' "${STORAGE_CONSOLE_DB}" | base64 | tr -d '\n')
_SC_USER_B64=$(printf '%s' "${STORAGE_CONSOLE_USER}" | base64 | tr -d '\n')
_SC_PASS_B64=$(printf '%s' "${STORAGE_CONSOLE_PASSWORD}" | base64 | tr -d '\n')

if kubectl -n "$HOMELAB_NAMESPACE" get secret storage-console-postgres-secret &>/dev/null; then
  kubectl patch secret -n "$HOMELAB_NAMESPACE" storage-console-postgres-secret --type=json -p="[
    {\"op\":\"add\",\"path\":\"/data/STORAGE_CONSOLE_DB\",\"value\":\"${_SC_DB_B64}\"},
    {\"op\":\"add\",\"path\":\"/data/STORAGE_CONSOLE_USER\",\"value\":\"${_SC_USER_B64}\"},
    {\"op\":\"add\",\"path\":\"/data/STORAGE_CONSOLE_PASSWORD\",\"value\":\"${_SC_PASS_B64}\"}
  ]"
else
  kubectl -n "$HOMELAB_NAMESPACE" create secret generic storage-console-postgres-secret \
    --from-literal=STORAGE_CONSOLE_DB="${STORAGE_CONSOLE_DB}" \
    --from-literal=STORAGE_CONSOLE_USER="${STORAGE_CONSOLE_USER}" \
    --from-literal=STORAGE_CONSOLE_PASSWORD="${STORAGE_CONSOLE_PASSWORD}"
fi

# ── 5b. Ensure restic-immich-secret exists (RESTIC_PASSWORD) ─────────────────
# The restic repo password lives in 3 places (see docs/RESTIC-BACKUP.md):
#   (i)  this cluster Secret, (ii) host file ~/.config/immich/restic-password,
#   (iii) .env.example placeholder.  If the repo password is LOST the restic
#   repo is UNRECOVERABLE — we therefore NEVER auto-generate-and-forget here.
# Precedence: existing cluster secret (leave as-is) > $RESTIC_PASSWORD env >
#   host file ~/.config/immich/restic-password.  We never print the value.
echo ""
echo "[deploy] (2b/8) Ensuring restic-immich-secret (RESTIC_PASSWORD)"
RESTIC_PW_FILE="${HOME}/.config/immich/restic-password"
if kubectl -n "$HOMELAB_NAMESPACE" get secret restic-immich-secret &>/dev/null; then
  echo "  ✓ restic-immich-secret already exists — leaving untouched (password is immutable for repo access)."
else
  _RESTIC_PW=""
  if [[ -n "${RESTIC_PASSWORD:-}" ]]; then
    _RESTIC_PW="${RESTIC_PASSWORD}"
    echo "  → using RESTIC_PASSWORD from environment"
  elif [[ -f "$RESTIC_PW_FILE" ]]; then
    _RESTIC_PW="$(cat "$RESTIC_PW_FILE")"
    echo "  → using RESTIC_PASSWORD from $RESTIC_PW_FILE"
  else
    echo "  ERROR: no restic password found. Set RESTIC_PASSWORD in .env OR create"
    echo "         $RESTIC_PW_FILE (chmod 600) before deploying. See docs/RESTIC-BACKUP.md."
    exit 1
  fi
  kubectl -n "$HOMELAB_NAMESPACE" create secret generic restic-immich-secret \
    --from-literal=RESTIC_PASSWORD="${_RESTIC_PW}"
  unset _RESTIC_PW
  echo "  ✓ restic-immich-secret created (value masked)"
fi

# ── 6. Ensure storage_console DB + user exist in shared-postgres ─────────────
# The shared-postgres init.sh ConfigMap only runs on first postgres startup,
# so for an existing cluster we have to create the DB/user ourselves.
# We pipe a small bootstrap script (with vars substituted on the *host* side)
# into the pod's /bin/sh — avoids the quoting hell of doing it on one line.
echo ""
echo "[deploy] (3/8) Ensuring storage_console DB+user in shared-postgres"
PG_POD=$(kubectl -n "$HOMELAB_NAMESPACE" get pod -l app=shared-postgres -o jsonpath='{.items[0].metadata.name}' 2>/dev/null || true)
if [[ -z "$PG_POD" ]]; then
  echo "  WARNING: shared-postgres pod not found — skipping DB bootstrap."
  echo "           If this is a fresh cluster, deploy shared-postgres first."
else
  PG_BOOTSTRAP=$(cat <<EOF
set -e
psql -v ON_ERROR_STOP=1 -U "\$POSTGRES_USER" -d postgres <<'SQL'
DO \$\$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${STORAGE_CONSOLE_USER}') THEN
    CREATE USER "${STORAGE_CONSOLE_USER}" WITH PASSWORD '${STORAGE_CONSOLE_PASSWORD}';
  ELSE
    ALTER USER "${STORAGE_CONSOLE_USER}" WITH PASSWORD '${STORAGE_CONSOLE_PASSWORD}';
  END IF;
END
\$\$;
SQL
EXISTS=\$(psql -tAU "\$POSTGRES_USER" -d postgres -c "SELECT 1 FROM pg_database WHERE datname='${STORAGE_CONSOLE_DB}'")
if [ "\$EXISTS" != "1" ]; then
  psql -v ON_ERROR_STOP=1 -U "\$POSTGRES_USER" -d postgres \\
    -c "CREATE DATABASE \"${STORAGE_CONSOLE_DB}\" OWNER \"${STORAGE_CONSOLE_USER}\";"
fi
psql -v ON_ERROR_STOP=1 -U "\$POSTGRES_USER" -d postgres \\
  -c "GRANT ALL PRIVILEGES ON DATABASE \"${STORAGE_CONSOLE_DB}\" TO \"${STORAGE_CONSOLE_USER}\";"
EOF
)
  if ! echo "$PG_BOOTSTRAP" | kubectl -n "$HOMELAB_NAMESPACE" exec -i "$PG_POD" -- sh; then
    echo "ERROR: failed to bootstrap storage_console DB. See output above."
    exit 1
  fi
fi

# ── 7. Pre-flight: warn (don't fail) on missing HDD ──────────────────────────
echo ""
echo "[deploy] (4/8) HDD mount check (informational)"
if [[ -d "$HOMELAB_TIER_HDD_PATH" && -n "$(ls -A "$HOMELAB_TIER_HDD_PATH" 2>/dev/null)" ]]; then
  echo "  ✓ $HOMELAB_TIER_HDD_PATH (tier disk) is mounted (contents: $(ls "$HOMELAB_TIER_HDD_PATH" 2>/dev/null | head -5 | tr '\n' ' '))"
else
  echo "  ⚠ $HOMELAB_TIER_HDD_PATH (tier disk) is not mounted (or empty). Tier CronJobs will skip until plugged in."
fi

# Backup HDD: restic repo target + pre-flight mount-guard sentinel.
# The immich-backup CronJob ABORTS unless ${HOMELAB_HDD_PATH}/.homelab-backup-hdd
# exists, so we (a) create the repo dir and (b) drop the sentinel here.  Both are
# done on the host where deploy.sh runs (the Mac with the disk physically mounted);
# the sandboxed agent shell cannot, so a one-off pod is used during testing.
if [[ -d "$HOMELAB_HDD_PATH" && -w "$HOMELAB_HDD_PATH" ]]; then
  mkdir -p "$HOMELAB_HDD_PATH/restic-immich"
  [[ -f "$HOMELAB_HDD_PATH/.homelab-backup-hdd" ]] || \
    printf 'homelab backup disk marker — DO NOT DELETE (immich-backup mount guard)\n' > "$HOMELAB_HDD_PATH/.homelab-backup-hdd"
  echo "  ✓ $HOMELAB_HDD_PATH (backup disk) ready: restic-immich/ + .homelab-backup-hdd marker present"
else
  echo "  ⚠ $HOMELAB_HDD_PATH (backup disk) not mounted/writable. immich-backup will ABORT (guard) until present."
fi

# ── 8. Remove old v1 deployment if present ──────────────────────────────────
echo ""
echo "[deploy] (5/8) Removing v1 storage-console resources (if present)"
kubectl -n "$HOMELAB_NAMESPACE" delete deployment storage-console --ignore-not-found
kubectl -n "$HOMELAB_NAMESPACE" delete service storage-console    --ignore-not-found
kubectl -n "$HOMELAB_NAMESPACE" delete ingress storage-console    --ignore-not-found
# Keep the v1 ServiceAccount/Role/RoleBinding name "storage-console" — v2 re-uses it.

# ── 9. Apply manifests ──────────────────────────────────────────────────────
echo ""
# ── Apply SealedSecrets (GitOps secrets, encrypted-in-git) ────────────────────
# SealedSecrets in k8s/sealed/ are committed encrypted; the in-cluster
# sealed-secrets controller (kube-system) decrypts them into real Secrets with
# identical values. This is ADDITIVE and the SAFE DR path on a rebuilt cluster.
#
# NOTE: the .env → `kubectl create secret` step above is intentionally KEPT as a
# documented FALLBACK (no big-bang cutover). On a cluster where a plain Secret
# of the same name already exists, the controller will NOT overwrite it unless
# it carries the annotation sealedsecrets.bitnami.com/managed=true — so applying
# these is non-disruptive. See cluster-setup/secrets-dr/README.md for cutover.
SEALED_DIR="$SCRIPT_DIR/k8s/sealed"
if [ -d "$SEALED_DIR" ] && kubectl get crd sealedsecrets.bitnami.com >/dev/null 2>&1; then
  echo "[deploy] Applying SealedSecrets from k8s/sealed/ (controller present)..."
  for f in "$SEALED_DIR"/*.yaml; do
    [ -e "$f" ] || continue
    echo "  → $f"
    kubectl apply -f "$f"
  done
else
  echo "[deploy] SealedSecrets controller not found (crd sealedsecrets.bitnami.com missing) — skipping k8s/sealed/; relying on .env-created Secrets above."
fi

echo "[deploy] (6/8) Applying k8s manifests (envsubst → kubectl apply)"
K8S_DIR="$SCRIPT_DIR/k8s"
ENVSUBST_VARS='${HOMELAB_NAMESPACE} ${BACKEND_IMAGE} ${BACKEND_TAG} ${FRONTEND_IMAGE} ${FRONTEND_TAG} ${HOMELAB_TIER_HDD_PATH} ${HOMELAB_HDD_PATH} ${IMMICH_TIER_SCHEDULE} ${JELLYFIN_TIER_SCHEDULE} ${IMMICH_BACKUP_SCHEDULE} ${DB_BACKUP_SCHEDULE} ${STORAGE_CONSOLE_JWT_SECRET} ${STORAGE_CONSOLE_ADMIN_USERNAME} ${STORAGE_CONSOLE_ADMIN_PASSWORD} ${STORAGE_CONSOLE_ADMIN_DISPLAY_NAME}'
for f in \
    "$K8S_DIR"/10-backend.yaml \
    "$K8S_DIR"/20-frontend.yaml \
    "$K8S_DIR"/30-ingress.yaml \
    "$K8S_DIR"/40-cronjobs.yaml \
    "$K8S_DIR"/60-db-backup.yaml; do
  echo "  → $(basename "$f")"
  envsubst "$ENVSUBST_VARS" < "$f" | kubectl apply -f -
done

# ── 10. Wait for rollouts ───────────────────────────────────────────────────
echo ""
echo "[deploy] (7/8) Waiting for rollouts"
for dep in storage-console-backend storage-console-frontend; do
  echo "  → $dep"
  kubectl -n "$HOMELAB_NAMESPACE" rollout status "deployment/$dep" --timeout=5m
done

# ── 11. Done ────────────────────────────────────────────────────────────────
echo ""
echo "[deploy] (8/8) Done"
cat <<EOF

────────────────────────────────────────────────────────────────────────
✓ storage-console v2 deployed successfully.
────────────────────────────────────────────────────────────────────────

  Access (on Tailnet):    https://tier.stoat-perch.ts.net
  Admin login:            ${STORAGE_CONSOLE_ADMIN_USERNAME} / (see .env)

  Three cards on the dashboard, each backed by a CronJob:
    • Immich Tier   (cronjob/tier-mover-immich)
    • Jellyfin Tier (cronjob/tier-mover-jellyfin)
    • Immich Backup (cronjob/immich-backup)

  All CronJobs start in MANUAL mode (suspend=true).  Toggle Auto in the UI
  to enable the 3 AM schedule.  HDD must be mounted at:
    ${HOMELAB_TIER_HDD_PATH}

  Rotate admin password immediately if this is a first deploy:
  See docs/MAINTENANCE.md → "Rotate admin password"
EOF
