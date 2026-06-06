package com.nila.storageconsole.tasks;

import com.nila.storageconsole.audit.AuditEvent;
import com.nila.storageconsole.audit.AuditEventRepository;
import com.nila.storageconsole.k8s.HddProbeService;
import com.nila.storageconsole.k8s.KubernetesService;
import com.nila.storageconsole.security.AuthUser;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private static final Logger log = LoggerFactory.getLogger(TaskController.class);

    private final KubernetesService k8s;
    private final HddProbeService hdd;
    private final AuditEventRepository audits;

    public TaskController(KubernetesService k8s, HddProbeService hdd, AuditEventRepository audits) {
        this.k8s = k8s;
        this.hdd = hdd;
        this.audits = audits;
    }

    // ─────────────────────────── GET /api/tasks ──────────────────────────────

    @GetMapping
    public List<TaskView> list() {
        HddProbeService.Status hddStatus = hdd.status();
        List<TaskView> out = new ArrayList<>();
        for (TaskCatalog.TaskDef def : TaskCatalog.ALL) {
            out.add(toView(def, hddStatus.connected()));
        }
        return out;
    }

    @GetMapping("/{id}")
    public TaskView one(@PathVariable String id) {
        TaskCatalog.TaskDef def = TaskCatalog.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown task: " + id));
        return toView(def, hdd.status().connected());
    }

    private TaskView toView(TaskCatalog.TaskDef def, boolean hddConnected) {
        Optional<CronJob> cj = k8s.getCronJob(def.cronJobName());
        if (cj.isEmpty()) {
            return new TaskView(
                    def.id(),
                    def.displayName(),
                    def.description(),
                    def.cronJobName(),
                    "manual",
                    null,
                    null,
                    null,
                    hddConnected,
                    "CronJob not found in cluster — deploy via storage-console k8s manifests."
            );
        }
        CronJob c = cj.get();
        boolean suspended = Boolean.TRUE.equals(c.getSpec().getSuspend());
        String mode = suspended ? "manual" : "auto";
        String schedule = c.getSpec().getSchedule();
        String tz = c.getSpec().getTimeZone();
        Instant next = suspended ? null : KubernetesService.estimateNextRun(schedule, tz);

        List<Job> recent = k8s.listJobsForCronJob(def.cronJobName(), 1);
        RunView lastRun = recent.isEmpty() ? null : toRunView(KubernetesService.summarize(recent.get(0)));

        return new TaskView(
                def.id(),
                def.displayName(),
                def.description(),
                def.cronJobName(),
                mode,
                schedule,
                next == null ? null : next.toString(),
                lastRun,
                hddConnected,
                null
        );
    }

    // ─────────────────── PUT /api/tasks/{id}/mode ────────────────────────────

    @PutMapping("/{id}/mode")
    public TaskView setMode(@PathVariable String id,
                            @Valid @RequestBody ModeRequest body,
                            @AuthenticationPrincipal AuthUser principal) {
        TaskCatalog.TaskDef def = TaskCatalog.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown task: " + id));
        boolean suspend = body.mode().equalsIgnoreCase("manual");
        try {
            k8s.setSuspend(def.cronJobName(), suspend);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
        audits.save(new AuditEvent(
                principal == null ? null : principal.username(),
                "task.mode.set",
                def.id(),
                "mode=" + body.mode()
        ));
        return toView(def, hdd.status().connected());
    }

    // ─────────────────── POST /api/tasks/{id}/trigger ────────────────────────

    @PostMapping("/{id}/trigger")
    public TriggerResponse trigger(@PathVariable String id,
                                    @AuthenticationPrincipal AuthUser principal) {
        String actor = principal == null ? "anonymous" : principal.username();
        TaskCatalog.TaskDef def = TaskCatalog.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown task: " + id));
        log.info("event=task.trigger.requested actor={} task={} cronJob={}",
                actor, def.id(), def.cronJobName());
        CronJob cj = k8s.getCronJob(def.cronJobName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CronJob not found"));
        boolean suspended = Boolean.TRUE.equals(cj.getSpec().getSuspend());
        if (!suspended) {
            log.info("event=task.trigger.rejected actor={} task={} outcome=409 reason=auto-mode",
                    actor, def.id());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Task is in auto mode — switch to manual before triggering.");
        }
        // Concurrency guard: refuse to spawn a second run while one is in-flight.
        // Generic across all tasks — a restic/tier backup must never overlap itself.
        KubernetesService.ActiveJobs active = k8s.activeJobsForCronJob(def.cronJobName());
        log.info("event=task.trigger.activecheck actor={} task={} activeJobs={} activeJobName={}",
                actor, def.id(), active.count(), active.firstJobName());
        if (active.count() > 0) {
            log.info("event=task.trigger.rejected actor={} task={} outcome=409 reason=already-running activeJobName={}",
                    actor, def.id(), active.firstJobName());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Backup already running — wait for it to finish.");
        }
        String stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(java.time.ZoneOffset.UTC)
                .format(Instant.now());
        String jobName = def.cronJobName() + "-manual-" + stamp;
        Job j = k8s.createJobFromCronJob(def.cronJobName(), jobName);
        log.info("event=task.trigger.created actor={} task={} jobName={} outcome=201",
                actor, def.id(), j.getMetadata().getName());
        audits.save(new AuditEvent(
                principal == null ? null : principal.username(),
                "task.trigger",
                def.id(),
                "jobName=" + j.getMetadata().getName()
        ));
        return new TriggerResponse(j.getMetadata().getName(), k8s.namespace());
    }

    // ─────────────────── GET /api/tasks/{id}/runs ────────────────────────────

    @GetMapping("/{id}/runs")
    public List<RunView> runs(@PathVariable String id,
                              @RequestParam(name = "limit", defaultValue = "10") int limit) {
        TaskCatalog.TaskDef def = TaskCatalog.find(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown task: " + id));
        int capped = Math.max(1, Math.min(limit, 50));
        return k8s.listJobsForCronJob(def.cronJobName(), capped).stream()
                .map(KubernetesService::summarize)
                .map(TaskController::toRunView)
                .toList();
    }

    // ─────────── GET /api/tasks/{id}/runs/{jobName}/logs ─────────────────────

    @GetMapping(value = "/{id}/runs/{jobName}/logs", produces = "text/plain")
    public String logs(@PathVariable String id,
                       @PathVariable String jobName,
                       @RequestParam(name = "lines", defaultValue = "500") int lines) {
        TaskCatalog.find(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown task: " + id));
        int capped = Math.max(1, Math.min(lines, 5000));
        return k8s.tailJobLogs(jobName, capped);
    }

    private static RunView toRunView(KubernetesService.RunSummary s) {
        return new RunView(s.jobName(), s.startedAt(), s.finishedAt(), s.outcome());
    }

    // ────────────────────────────── DTOs ─────────────────────────────────────

    public record ModeRequest(@Pattern(regexp = "auto|manual") String mode) {}

    public record TaskView(
            String id,
            String displayName,
            String description,
            String cronJobName,
            String mode,
            String schedule,
            String nextRun,
            RunView lastRun,
            boolean hddConnected,
            String warning
    ) {}

    public record RunView(String jobName, String startedAt, String finishedAt, String outcome) {}

    public record TriggerResponse(String jobName, String namespace) {}
}
