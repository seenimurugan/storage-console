#!/usr/bin/env bash
# undeploy.sh — tear down storage-console deployment/service/ingress/RBAC
# PVCs/PVs are NOT deleted (storage-console has none, but the pattern is
# consistent across all homelab apps).
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Load .env for HOMELAB_NAMESPACE ──────────────────────────────────────────
ENV_FILE="$SCRIPT_DIR/.env"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
fi
HOMELAB_NAMESPACE="${HOMELAB_NAMESPACE:-homelab}"

echo "Undeploying storage-console from namespace '$HOMELAB_NAMESPACE'..."
echo "(PVCs/PVs are NOT deleted.)"
echo ""

# ── Deployment ────────────────────────────────────────────────────────────────
kubectl -n "$HOMELAB_NAMESPACE" delete deployment storage-console --ignore-not-found

# ── Service ───────────────────────────────────────────────────────────────────
kubectl -n "$HOMELAB_NAMESPACE" delete service storage-console --ignore-not-found

# ── Ingress ───────────────────────────────────────────────────────────────────
kubectl -n "$HOMELAB_NAMESPACE" delete ingress storage-console --ignore-not-found

# ── RBAC ──────────────────────────────────────────────────────────────────────
kubectl -n "$HOMELAB_NAMESPACE" delete rolebinding storage-console --ignore-not-found
kubectl -n "$HOMELAB_NAMESPACE" delete role storage-console --ignore-not-found
kubectl -n "$HOMELAB_NAMESPACE" delete serviceaccount storage-console --ignore-not-found

echo ""
echo "✓ storage-console torn down."
echo ""
echo "  Kept:"
echo "    - All PVCs/PVs (none used by this app, noted for consistency)"
echo "    - CronJobs (tier-mover-immich, tier-mover-jellyfin, immich-backup)"
echo ""
echo "  To redeploy:  ./deploy.sh"
