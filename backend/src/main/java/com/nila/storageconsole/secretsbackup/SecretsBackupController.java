package com.nila.storageconsole.secretsbackup;

import com.nila.storageconsole.audit.AuditEvent;
import com.nila.storageconsole.audit.AuditEventRepository;
import com.nila.storageconsole.security.AuthUser;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * REST endpoints for the secrets-backup card.
 *
 * <ul>
 *   <li>GET  /api/secrets-backup       — last-run status</li>
 *   <li>POST /api/secrets-backup/trigger — spawn a one-off Job</li>
 *   <li>GET  /api/secrets-backup/{jobName}/logs — tail Job logs</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/secrets-backup")
public class SecretsBackupController {

    private static final Logger log = LoggerFactory.getLogger(SecretsBackupController.class);

    private final SecretsBackupService service;
    private final AuditEventRepository audits;

    public SecretsBackupController(SecretsBackupService service, AuditEventRepository audits) {
        this.service = service;
        this.audits = audits;
    }

    // ── GET /api/secrets-backup ──────────────────────────────────────────────

    @GetMapping
    public SecretsBackupService.SecretsBackupStatus status() {
        log.info("event=secrets_backup.status.request");
        SecretsBackupService.SecretsBackupStatus s = service.getStatus();
        log.info("event=secrets_backup.status.response lastJobName={} outcome={}",
                s.lastJobName(), s.outcome());
        return s;
    }

    // ── POST /api/secrets-backup/trigger ─────────────────────────────────────

    @PostMapping("/trigger")
    @ResponseStatus(HttpStatus.CREATED)
    public TriggerResponse trigger(@AuthenticationPrincipal AuthUser principal) {
        String actor = principal == null ? "anonymous" : principal.username();
        log.info("event=secrets_backup.trigger.request actor={}", actor);
        try {
            Job job = service.trigger(actor);
            String jobName = job.getMetadata().getName();
            log.info("event=secrets_backup.trigger.success actor={} jobName={} outcome=201", actor, jobName);
            audits.save(new AuditEvent(actor, "secrets_backup.trigger", jobName, "actor=" + actor));
            return new TriggerResponse(jobName, job.getMetadata().getNamespace());
        } catch (IllegalStateException e) {
            log.warn("event=secrets_backup.trigger.rejected actor={} outcome=409 reason={}", actor, e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    // ── GET /api/secrets-backup/{jobName}/logs ───────────────────────────────

    @GetMapping(value = "/{jobName}/logs", produces = "text/plain")
    public String logs(@PathVariable String jobName,
                       @RequestParam(name = "lines", defaultValue = "500") int lines) {
        log.info("event=secrets_backup.logs.request jobName={} lines={}", jobName, lines);
        int capped = Math.max(1, Math.min(lines, 5000));
        String result = service.getLogs(jobName, capped);
        log.info("event=secrets_backup.logs.response jobName={} chars={}", jobName, result.length());
        return result;
    }

    // ── DTOs ─────────────────────────────────────────────────────────────────

    public record TriggerResponse(String jobName, String namespace) {}
}
