#!/usr/bin/env bash
# undeploy.sh — tear down storage-console v2.
#   Removes Deployments/Services/Ingress/Secrets/RBAC.
#   Preserves: CronJobs (and their .spec.suspend mode), DB data, shared-postgres-secret.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

ENV_FILE="$SCRIPT_DIR/.env"
if [[ -f "$ENV_FILE" ]]; then
  # shellcheck disable=SC1090
  set -a; source "$ENV_FILE"; set +a
fi
NAMESPACE="${HOMELAB_NAMESPACE:-homelab}"

echo "Undeploying storage-console from namespace '$NAMESPACE'..."
echo "(CronJobs preserved with their current Auto/Manual mode.)"
echo ""

kubectl -n "$NAMESPACE" delete deployment storage-console-backend  --ignore-not-found
kubectl -n "$NAMESPACE" delete deployment storage-console-frontend --ignore-not-found

kubectl -n "$NAMESPACE" delete service storage-console-backend  --ignore-not-found
kubectl -n "$NAMESPACE" delete service storage-console-frontend --ignore-not-found

kubectl -n "$NAMESPACE" delete ingress storage-console --ignore-not-found

kubectl -n "$NAMESPACE" delete secret storage-console-backend-secret --ignore-not-found

kubectl -n "$NAMESPACE" delete rolebinding    storage-console --ignore-not-found
kubectl -n "$NAMESPACE" delete role           storage-console --ignore-not-found
kubectl -n "$NAMESPACE" delete serviceaccount storage-console --ignore-not-found

echo ""
echo "✓ storage-console torn down."
echo ""
echo "  Kept:"
echo "    - CronJobs (tier-mover-immich, tier-mover-jellyfin, immich-backup)"
echo "      and their suspend (auto/manual) state."
echo "    - shared-postgres-secret (shared with other apps)"
echo "    - storage_console DB inside shared-postgres"
echo ""
echo "  Full wipe (DROP DB):  kubectl -n $NAMESPACE exec shared-postgres-0 -- \\"
echo "    psql -U postgres -c 'DROP DATABASE storage_console;'"
echo ""
echo "  Drop CronJobs too:   kubectl -n $NAMESPACE delete cronjob \\"
echo "    tier-mover-immich tier-mover-jellyfin immich-backup"
echo ""
echo "  Redeploy:            ./deploy.sh"
