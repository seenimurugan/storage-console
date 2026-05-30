#!/usr/bin/env bash
# deploy.sh — idempotent deploy for storage-console (homelab storage dashboard)
# Usage: ./deploy.sh
# Safe to re-run; existing resources are patched, not replaced.
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

# ── 2. Prereq checks ─────────────────────────────────────────────────────────
for cmd in kubectl envsubst docker; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: $cmd not found in PATH."
    [[ "$cmd" == "envsubst" ]] && echo "       Install via: brew install gettext"
    exit 1
  fi
done

if ! kubectl cluster-info &>/dev/null; then
  echo "ERROR: Cannot reach the Kubernetes cluster. Is OrbStack running?"
  exit 1
fi

# ── 3. Verify HDD paths exist (storage-console browses them at runtime) ──────
if [[ ! -d "${HOMELAB_HDD_PATH}" ]]; then
  echo "ERROR: HOMELAB_HDD_PATH='${HOMELAB_HDD_PATH}' does not exist or is not mounted."
  echo "       Connect the HDD and retry, or update HOMELAB_HDD_PATH in .env."
  exit 1
fi
if [[ ! -d "${HOMELAB_TIER_HDD_PATH}" ]]; then
  echo "ERROR: HOMELAB_TIER_HDD_PATH='${HOMELAB_TIER_HDD_PATH}' does not exist or is not mounted."
  echo "       Connect the tier HDD and retry, or update HOMELAB_TIER_HDD_PATH in .env."
  exit 1
fi

# ── 4. Build the image (arm64 — OrbStack k3s shares the Mac's Docker daemon) ──
echo "Building ${STORAGE_CONSOLE_IMAGE}:${STORAGE_CONSOLE_TAG} (linux/arm64)..."
docker build --platform linux/arm64 \
  -t "${STORAGE_CONSOLE_IMAGE}:${STORAGE_CONSOLE_TAG}" \
  -f "$SCRIPT_DIR/Dockerfile" \
  "$SCRIPT_DIR"

# ── 5. Ensure namespace exists ───────────────────────────────────────────────
HOMELAB_NAMESPACE="${HOMELAB_NAMESPACE:-homelab}"
if ! kubectl get namespace "$HOMELAB_NAMESPACE" &>/dev/null; then
  echo "Namespace '$HOMELAB_NAMESPACE' not found — creating it."
  kubectl create namespace "$HOMELAB_NAMESPACE"
else
  echo "Namespace '$HOMELAB_NAMESPACE' already exists."
fi

# ── 6. Apply manifests via envsubst ──────────────────────────────────────────
K8S_DIR="$SCRIPT_DIR/k8s"
echo "Applying k8s manifests (envsubst → kubectl apply)..."
echo "  → $K8S_DIR/storage-console.yaml"
envsubst < "$K8S_DIR/storage-console.yaml" | kubectl apply -f -

# ── 7. Wait for rollout ───────────────────────────────────────────────────────
echo "Waiting for storage-console rollout..."
kubectl -n "$HOMELAB_NAMESPACE" rollout status deployment/storage-console --timeout=5m

# ── 8. Done ───────────────────────────────────────────────────────────────────
echo ""
echo "✓ storage-console deployed successfully."
echo ""
echo "  Access (on Tailnet):   https://tier.stoat-perch.ts.net"
echo "  Debug port-forward:    kubectl port-forward -n ${HOMELAB_NAMESPACE} svc/storage-console 8080:8080"
echo "                         then open http://localhost:8080"
echo ""
echo "  The CronJobs the console triggers must exist in namespace '${HOMELAB_NAMESPACE}':"
echo "    tier-mover-immich   (suspend: true)"
echo "    tier-mover-jellyfin (suspend: true)"
echo "    immich-backup       (suspend: true)"
