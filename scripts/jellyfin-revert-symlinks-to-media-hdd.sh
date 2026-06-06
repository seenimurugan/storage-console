#!/usr/bin/env bash
# jellyfin-revert-symlinks-to-media-hdd.sh
# ---------------------------------------------------------------------------
# REVERSE of the propagation-safe migration for Jellyfin tiered symlinks.
#
# The migration re-pointed tiered symlinks under /media from the legacy direct
# HDD mount (/media-hdd/<rel>) to the propagation-safe mount
# (/hdd-root/homelab-hdd/jellyfin-media/<rel>). This script undoes that:
# it rewrites any symlink under /media whose target starts with
#   /hdd-root/homelab-hdd/jellyfin-media/
# back to
#   /media-hdd/
#
# SAFETY: pointer rewrite ONLY (ln -sfn). It never touches, moves, or deletes
# the actual media file on the HDD. Run this if the propagation-safe path
# regresses AND the legacy /media-hdd mount is restored on the pod.
#
# Usage (from a host with kubectl context on the cluster):
#   ./jellyfin-revert-symlinks-to-media-hdd.sh            # apply revert
#   DRY_RUN=1 ./jellyfin-revert-symlinks-to-media-hdd.sh  # print only
#
# Requires the legacy /media-hdd mount to be present on the jellyfin pod
# (re-add the hdd-jellyfin volumeMount in jellyfin-values.yaml + helm upgrade)
# before the reverted symlinks will resolve.
# ---------------------------------------------------------------------------
set -euo pipefail

NS="${HOMELAB_NAMESPACE:-homelab}"
NEW_PREFIX="/hdd-root/homelab-hdd/jellyfin-media"
OLD_PREFIX="/media-hdd"

POD="$(kubectl -n "$NS" get pods -l app.kubernetes.io/name=jellyfin \
        -o jsonpath='{.items[0].metadata.name}')"
if [[ -z "$POD" ]]; then
  echo "ERROR: no jellyfin pod found in namespace $NS" >&2
  exit 1
fi
echo "[revert] jellyfin pod: $POD (ns=$NS)"
echo "[revert] rewriting ${NEW_PREFIX}/* -> ${OLD_PREFIX}/* (DRY_RUN=${DRY_RUN:-0})"

kubectl -n "$NS" exec "$POD" -- sh -c '
NEW="'"$NEW_PREFIX"'"
OLD="'"$OLD_PREFIX"'"
DRY="'"${DRY_RUN:-0}"'"
count=0
find /media -type l 2>/dev/null | while read -r link; do
  tgt=$(readlink "$link")
  case "$tgt" in
    ${NEW}/*)
      rel=${tgt#${NEW}/}
      newtgt="${OLD}/${rel}"
      if [ "$DRY" = "1" ]; then
        echo "WOULD: $link  ->  $newtgt"
      else
        ln -sfn "$newtgt" "$link"
        echo "REVERTED: $link  ->  $newtgt"
      fi
      count=$((count+1))
    ;;
  esac
done
'
echo "[revert] done."
