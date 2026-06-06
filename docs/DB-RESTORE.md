# Database Restore Runbook

Restore procedures for every homelab database backed up by the **`db-backup`**
CronJob (owned by storage-console, manifest `k8s/60-db-backup.yaml`).

The job writes restorable, gzipped dumps to the **backup HDD**:

```
/Volumes/homelab-backup-hdd/backups/databases/
  ├── postgres/<db>-<timestamp>.sql.gz   # logical pg_dump, --clean --if-exists
  └── sqlite/<app>-<timestamp>.db.gz      # consistent sqlite .backup (single file)
```

`<timestamp>` is `YYYY-MM-DDThh-mm-ss`. Retention: newest **14** per DB (`KEEP_DB`).

> All commands run from a Mac with `kubectl` context on the homelab cluster.
> Pick the desired snapshot file first: `ls -lt <dir>`.

---

## Postgres — shared-postgres-0 (kidstasks, reminders, moviesda, emailmatrix, storage_console)

The dumps were taken with `pg_dump --no-owner --clean --if-exists`, so the
restore is **idempotent** — it drops then recreates each object. The target DB
must already exist (the shared-postgres init creates it on first start). The
restore stream is just `psql` of the decompressed dump.

```bash
DB=kidstasks                 # or reminders | moviesda | emailmatrix | storage_console
FILE=kidstasks-2026-06-06T05-00-00.sql.gz
HDD=/Volumes/homelab-backup-hdd/backups/databases/postgres

# Stream the gzipped dump straight into psql inside the pod:
gzcat "$HDD/$FILE" | kubectl -n homelab exec -i shared-postgres-0 -- \
    psql -U postgres -d "$DB" -v ON_ERROR_STOP=1
```

Verify:
```bash
kubectl -n homelab exec shared-postgres-0 -- \
    psql -U postgres -d "$DB" -c '\dt'
```

> If the target DB does not exist, create it first:
> `kubectl -n homelab exec shared-postgres-0 -- psql -U postgres -c "CREATE DATABASE $DB;"`

---

## Postgres — immich-postgres-0 (immich)

Same shape; the dump user is `immich`.

```bash
FILE=immich-2026-06-06T05-00-00.sql.gz
HDD=/Volumes/homelab-backup-hdd/backups/databases/postgres

gzcat "$HDD/$FILE" | kubectl -n homelab exec -i immich-postgres-0 -- \
    psql -U immich -d immich -v ON_ERROR_STOP=1
```

> Immich-specific: stop the Immich server pods before a full restore so the
> app does not write mid-restore:
> `kubectl -n homelab scale deploy immich-server immich-microservices --replicas=0`
> then scale back to 1 after the restore and verify the library loads.

---

## SQLite — jellyfin, radarr, prowlarr, bazarr, grocy, filebrowser

The `.db.gz` files are a **single, already-consistent SQLite database file**
(produced by `sqlite3 .backup`, which checkpoints the WAL into one file — no
`-wal`/`-shm` sidecars needed). Restore = stop the app, drop the file in place,
remove any stale WAL sidecars, start the app.

| App         | Container | DB path inside pod          |
| ----------- | --------- | --------------------------- |
| jellyfin    | (single)  | `/config/data/jellyfin.db`  |
| radarr      | `radarr`  | `/config/radarr.db`         |
| prowlarr    | `prowlarr`| `/config/prowlarr.db`       |
| bazarr      | `bazarr`  | `/config/db/bazarr.db`      |
| grocy       | (single)  | `/config/data/grocy.db`     |
| filebrowser | (single)  | `/database/filebrowser.db`  |

Generic procedure (example: radarr):

```bash
APP=radarr
DEPLOY=radarr
CONTAINER="-c radarr"          # omit -c ... for single-container pods (jellyfin/grocy/filebrowser)
DBPATH=/config/radarr.db
FILE=radarr-2026-06-06T05-00-00.db.gz
HDD=/Volumes/homelab-backup-hdd/backups/databases/sqlite

# 1. Stop the app so nothing holds the DB open.
kubectl -n homelab scale deploy "$DEPLOY" --replicas=0
kubectl -n homelab wait --for=delete pod -l app="$APP" --timeout=120s

# 2. Start it again at 1 replica (need a running pod to copy into) — OR copy
#    into the PVC via a throwaway pod. Simplest: scale to 1, then with the app
#    NOT yet writing heavily, replace the file. For a clean restore, use a
#    maintenance pod mounting the same PVC. Quick path below copies via exec:
kubectl -n homelab scale deploy "$DEPLOY" --replicas=1
POD=$(kubectl -n homelab get pod -l app="$APP" -o jsonpath='{.items[0].metadata.name}')

# 3. Push the decompressed DB into the pod, replacing the live file + WAL set.
gzcat "$HDD/$FILE" | kubectl -n homelab exec -i "$POD" $CONTAINER -- \
    sh -c "cat > '$DBPATH.restore' && rm -f '$DBPATH-wal' '$DBPATH-shm' && mv -f '$DBPATH.restore' '$DBPATH'"

# 4. Integrity check, then restart the pod so the app reopens the new DB.
kubectl -n homelab exec "$POD" $CONTAINER -- sqlite3 "$DBPATH" 'PRAGMA integrity_check;' 2>/dev/null \
  || echo "(no sqlite3 in pod — verify from a maintenance pod or after restart)"
kubectl -n homelab rollout restart deploy "$DEPLOY"
```

For **jellyfin** (single container, helm-labelled): use selector
`app.kubernetes.io/name=jellyfin` and omit `$CONTAINER`; deployment is
`jellyfin`.

> **filebrowser is NOT SQLite** — its `/database/filebrowser.db` is a **BoltDB**
> file. The backup job's `sqlite3 .backup`/`.dump` attempts fail (expected) and
> it falls back to a faithful **raw byte copy** (`method=raw-copy-with-wal-set`),
> which is the correct restorable form for Bolt. Restore = stop filebrowser,
> drop the decompressed `.db` file in place (step 3 above, no `-wal`/`-shm`
> sidecars exist), restart. Do NOT run the `PRAGMA integrity_check` step for
> filebrowser — it will report `file is not a database`, which is normal here.
> Verify instead by starting filebrowser and confirming a login works.

> The app pods do not ship `sqlite3`, so the integrity check in step 4 may be
> skipped in-pod. To verify a `.db.gz` from your Mac before restoring:
> ```bash
> gzcat radarr-....db.gz > /tmp/r.db && sqlite3 /tmp/r.db 'PRAGMA integrity_check;'
> ```
> Expected output: `ok`.

---

## Notes on how backups are produced (for trust)

- **Postgres**: `kubectl exec <pg-pod> -- pg_dump -U <user> -d <db> --no-owner
  --clean --if-exists | gzip`. Restorable with `psql` (idempotent).
- **SQLite**: the app pods have **no** sqlite3/python client, so the job streams
  the consistent file set (`db` + `-wal` + `-shm`) out with `kubectl exec -- tar`
  and runs `sqlite3 .backup` (then `PRAGMA integrity_check`) **inside the backup
  job pod** to emit a single clean file. Fallback order if `.backup` fails:
  `sqlite3 .dump | sqlite3 <newdb>` → raw copy of the streamed WAL set →
  ERROR for that app (the run continues for the others).
- Every dump is written `.tmp` then atomically `mv`-ed into place, and gzip-
  verified (`gzip -t`) before commit, so a partial/failed dump never overwrites
  a previous good one.
