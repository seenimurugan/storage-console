package com.nila.storageconsole.tasks;

import java.util.List;
import java.util.Optional;

/**
 * Hardcoded catalog of the three storage operations.
 *
 * Task IDs are the public/UI keys. CronJob names are what's stored in Kubernetes
 * (must match the names in k8s/40-cronjobs.yaml). This indirection keeps the
 * frontend URL paths stable even if we rename a CronJob.
 */
public final class TaskCatalog {

    public record TaskDef(
            String id,
            String displayName,
            String description,
            String cronJobName
    ) {}

    public static final TaskDef IMMICH_TIER = new TaskDef(
            "immich-tier",
            "Immich Tier",
            "Move Immich files larger than 2 GiB from SSD to HDD",
            "tier-mover-immich"
    );

    public static final TaskDef JELLYFIN_TIER = new TaskDef(
            "jellyfin-tier",
            "Jellyfin Tier",
            "Move Jellyfin media larger than 3 GiB from SSD to HDD",
            "tier-mover-jellyfin"
    );

    public static final TaskDef IMMICH_BACKUP = new TaskDef(
            "immich-backup",
            "Immich Backup",
            "pg_dump + library tar of Immich to the HDD backups directory",
            "immich-backup"
    );

    public static final List<TaskDef> ALL = List.of(IMMICH_TIER, JELLYFIN_TIER, IMMICH_BACKUP);

    public static Optional<TaskDef> find(String id) {
        return ALL.stream().filter(t -> t.id().equals(id)).findFirst();
    }

    private TaskCatalog() {}
}
