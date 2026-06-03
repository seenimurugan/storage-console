package com.nila.storageconsole.tasks;

import com.nila.storageconsole.k8s.KubernetesService;
import com.nila.storageconsole.security.AuthUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Exposes GET /api/thresholds and PUT /api/thresholds/{task} so users can
 * configure per-task tiering size thresholds (stored in the tiering-thresholds
 * ConfigMap, read by the mover CronJob pods at runtime).
 *
 * Units presented to the client are GiB (decimals allowed, e.g. 1.5).
 * Internally stored and passed to Kubernetes as bytes (long).
 */
@RestController
@RequestMapping("/api/thresholds")
public class ThresholdController {

    private static final Logger log = LoggerFactory.getLogger(ThresholdController.class);

    /** ConfigMap name containing the threshold byte values. */
    static final String THRESHOLDS_CM = "tiering-thresholds";

    /** ConfigMap key names per task. */
    static final Map<String, String> TASK_KEY_MAP = Map.of(
            "immich-tier",    "immich-threshold-bytes",
            "jellyfin-tier",  "jellyfin-threshold-bytes"
    );

    private static final double GIB = 1_073_741_824.0;

    private final KubernetesService k8s;

    public ThresholdController(KubernetesService k8s) {
        this.k8s = k8s;
    }

    // ─────────────────────── GET /api/thresholds ─────────────────────────────

    /**
     * Returns current thresholds for all tier tasks in GiB.
     */
    @GetMapping
    public ThresholdsView getAll() {
        log.info("event=thresholds.get.all action=read outcome=ok");
        double immich = readGib("immich-threshold-bytes");
        double jellyfin = readGib("jellyfin-threshold-bytes");
        log.info("event=thresholds.get.all.result immichGib={} jellyfinGib={}", immich, jellyfin);
        return new ThresholdsView(immich, jellyfin);
    }

    // ──────────────── PUT /api/thresholds/{task} ─────────────────────────────

    /**
     * Patches the ConfigMap key for the given task with the new bytes value,
     * converting the client-supplied GiB to bytes.
     */
    @PutMapping("/{task}")
    public ThresholdView setThreshold(
            @PathVariable String task,
            @Valid @RequestBody ThresholdRequest body,
            @AuthenticationPrincipal AuthUser principal) {

        String actor = principal == null ? "anonymous" : principal.username();

        String cmKey = TASK_KEY_MAP.get(task);
        if (cmKey == null) {
            log.warn("event=threshold.set.rejected actor={} task={} reason=unknown-task outcome=404",
                    actor, task);
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown tiering task: " + task);
        }

        if (body.gib() <= 0) {
            log.warn("event=threshold.set.rejected actor={} task={} gib={} reason=non-positive outcome=400",
                    actor, task, body.gib());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "gib must be > 0");
        }

        long newBytes = Math.round(body.gib() * GIB);

        // Read old value for audit log
        String oldRaw = null;
        try {
            oldRaw = k8s.getConfigMapValue(THRESHOLDS_CM, cmKey);
        } catch (Exception e) {
            log.warn("event=threshold.set.old-read-failed actor={} task={} key={} reason={}",
                    actor, task, cmKey, e.getMessage());
        }

        log.info("event=threshold.set.requested actor={} task={} key={} oldBytes={} newGib={} newBytes={}",
                actor, task, cmKey, oldRaw, body.gib(), newBytes);

        try {
            k8s.patchConfigMapValue(THRESHOLDS_CM, cmKey, String.valueOf(newBytes));
        } catch (Exception e) {
            log.error("event=threshold.set.failed actor={} task={} key={} newBytes={} error={}",
                    actor, task, cmKey, newBytes, e.getMessage(), e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to patch ConfigMap: " + e.getMessage());
        }

        double resultGib = (double) newBytes / GIB;
        log.info("event=threshold.set.ok actor={} task={} key={} oldBytes={} newBytes={} resultGib={} outcome=200",
                actor, task, cmKey, oldRaw, newBytes, resultGib);

        return new ThresholdView(task, resultGib, newBytes);
    }

    // ─────────────────────────── helpers ──────────────────────────────────────

    private double readGib(String cmKey) {
        try {
            String raw = k8s.getConfigMapValue(THRESHOLDS_CM, cmKey);
            if (raw == null || raw.isBlank()) return 1.0;
            return Long.parseLong(raw.trim()) / GIB;
        } catch (Exception e) {
            log.warn("event=threshold.read.failed key={} reason={} fallback=1.0", cmKey, e.getMessage());
            return 1.0;
        }
    }

    // ─────────────────────────── DTOs ─────────────────────────────────────────

    public record ThresholdRequest(@DecimalMin(value = "0.0", inclusive = false) double gib) {}

    public record ThresholdView(String task, double gib, long bytes) {}

    public record ThresholdsView(double immichGib, double jellyfinGib) {}
}
