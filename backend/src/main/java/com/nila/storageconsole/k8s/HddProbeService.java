package com.nila.storageconsole.k8s;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Probes whether the homelab HDD is mounted on the Mac host.
 *
 * Mechanism: the backend Deployment mounts the host path
 * (HOMELAB_TIER_HDD_PATH on macOS) into the backend pod at a known path (see
 * application.yml -> storage-console.hdd.probe-path).  We check that the path
 * exists AND is non-empty (a freshly unmounted external HDD typically leaves a
 * macOS auto-created empty mountpoint; we treat a directory with at least one
 * entry as "connected").
 *
 * Result is cached for 30s to avoid hammering the filesystem on UI poll.
 */
@Service
public class HddProbeService {

    private static final Logger log = LoggerFactory.getLogger(HddProbeService.class);
    private static final long CACHE_TTL_MS = 30_000L;

    private final Path probePath;
    private final String hostPath;
    private volatile CachedResult cached;

    public HddProbeService(@Value("${storage-console.hdd.host-path}") String hostPath,
                           @Value("${storage-console.hdd.probe-path}") String probePath) {
        this.hostPath = hostPath;
        this.probePath = Path.of(probePath);
    }

    public Status status() {
        CachedResult c = cached;
        long now = System.currentTimeMillis();
        if (c != null && now - c.checkedAtMs < CACHE_TTL_MS) {
            return new Status(hostPath, c.connected, Instant.ofEpochMilli(c.checkedAtMs));
        }
        boolean connected = probe();
        cached = new CachedResult(connected, now);
        return new Status(hostPath, connected, Instant.ofEpochMilli(now));
    }

    private boolean probe() {
        try {
            if (!Files.isDirectory(probePath)) return false;
            // Mounted HDD will have at least one subdir (immich-library, jellyfin-media, backups).
            try (var stream = Files.list(probePath)) {
                return stream.findAny().isPresent();
            }
        } catch (Exception e) {
            log.debug("HDD probe failed on {}: {}", probePath, e.getMessage());
            return false;
        }
    }

    public record Status(String hostPath, boolean connected, Instant lastChecked) {}

    private record CachedResult(boolean connected, long checkedAtMs) {}
}
