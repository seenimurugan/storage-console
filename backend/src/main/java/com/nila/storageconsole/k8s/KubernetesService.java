package com.nila.storageconsole.k8s;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpec;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.PodResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Thin wrapper around the fabric8 Kubernetes client for storage-console's
 * concrete needs: toggle CronJob suspend, spawn a Job from a CronJob template,
 * list recent Jobs spawned by each CronJob, fetch pod logs, probe the HDD mount.
 */
@Service
public class KubernetesService {

    private static final Logger log = LoggerFactory.getLogger(KubernetesService.class);

    private final KubernetesClient client;
    private final String namespace;

    public KubernetesService(KubernetesClient client,
                             @Value("${storage-console.k8s.namespace}") String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    public String namespace() { return namespace; }

    // ──────────────────────────── CronJob ────────────────────────────────────

    public Optional<CronJob> getCronJob(String name) {
        return Optional.ofNullable(
                client.batch().v1().cronjobs().inNamespace(namespace).withName(name).get());
    }

    public boolean isSuspended(String cronJobName) {
        return getCronJob(cronJobName)
                .map(cj -> Boolean.TRUE.equals(cj.getSpec().getSuspend()))
                .orElse(true);
    }

    /** Patch CronJob.spec.suspend.  Returns the new value. */
    public boolean setSuspend(String cronJobName, boolean suspend) {
        CronJob cj = getCronJob(cronJobName)
                .orElseThrow(() -> new IllegalArgumentException("CronJob not found: " + cronJobName));
        client.batch().v1().cronjobs()
                .inNamespace(namespace)
                .withName(cronJobName)
                .edit(c -> {
                    c.getSpec().setSuspend(suspend);
                    return c;
                });
        log.info("CronJob {} suspend={}", cronJobName, suspend);
        return suspend;
    }

    // ─────────────────────────────── Jobs ────────────────────────────────────

    /**
     * Spawn a new Job from the named CronJob's template — mirrors
     * "kubectl create job --from=cronjob/<name>".
     */
    public Job createJobFromCronJob(String cronJobName, String jobName) {
        CronJob cj = getCronJob(cronJobName)
                .orElseThrow(() -> new IllegalStateException("CronJob not found: " + cronJobName));
        JobSpec jobSpec = cj.getSpec().getJobTemplate().getSpec();

        Map<String, String> labels = new HashMap<>();
        Map<String, String> annotations = new HashMap<>();
        if (cj.getSpec().getJobTemplate().getMetadata() != null) {
            ObjectMeta tplMeta = cj.getSpec().getJobTemplate().getMetadata();
            if (tplMeta.getLabels() != null) labels.putAll(tplMeta.getLabels());
            if (tplMeta.getAnnotations() != null) annotations.putAll(tplMeta.getAnnotations());
        }
        // Mark which CronJob owns this Job so we can list it back.
        labels.put("storage-console.nila/cronjob", cronJobName);
        labels.put("storage-console.nila/trigger", "manual");

        Job job = new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(namespace)
                    .withLabels(labels)
                    .withAnnotations(annotations)
                .endMetadata()
                .withSpec(jobSpec)
                .build();

        Job created = client.batch().v1().jobs().inNamespace(namespace).resource(job).create();
        log.info("Created Job {} from CronJob {}", created.getMetadata().getName(), cronJobName);
        return created;
    }

    /** Most recent Jobs (by creation time desc) that belong to this CronJob. */
    public List<Job> listJobsForCronJob(String cronJobName, int limit) {
        // Jobs spawned by the cronjob controller carry the annotation
        //   batch.kubernetes.io/cronjob-name: <name>
        // Jobs we create manually carry the label storage-console.nila/cronjob.
        // We must merge both — there is no single label/selector that covers both.
        List<Job> all = client.batch().v1().jobs().inNamespace(namespace).list().getItems();
        return all.stream()
                .filter(j -> {
                    Map<String, String> labels = j.getMetadata().getLabels();
                    Map<String, String> ann = j.getMetadata().getAnnotations();
                    if (labels != null && cronJobName.equals(labels.get("storage-console.nila/cronjob"))) return true;
                    if (ann != null && cronJobName.equals(ann.get("batch.kubernetes.io/cronjob-name"))) return true;
                    // Also match Jobs created by older kubectl versions that put it on labels.
                    if (labels != null && cronJobName.equals(labels.get("cronjob-name"))) return true;
                    return false;
                })
                .sorted(Comparator.comparing(
                        (Job j) -> Optional.ofNullable(j.getMetadata().getCreationTimestamp()).orElse(""))
                        .reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Count Jobs belonging to this CronJob that are still in-flight — i.e. have
     * {@code status.active > 0} or have neither succeeded nor failed yet. Used by
     * the Trigger-Now concurrency guard to reject a second run while one is live.
     */
    public ActiveJobs activeJobsForCronJob(String cronJobName) {
        // Broader matcher than listJobsForCronJob(): the guard must also catch
        // Jobs created out-of-band by `kubectl create job --from=cronjob/<name>`
        // (which carry only annotation cronjob.kubernetes.io/instantiate=manual
        // and the conventional name prefix "<cronJobName>-"), otherwise an
        // overlapping run from the CLI fallback would slip past the guard.
        List<Job> all = client.batch().v1().jobs().inNamespace(namespace).list().getItems();
        List<Job> inFlight = all.stream()
                .filter(j -> belongsToCronJob(j, cronJobName))
                .filter(KubernetesService::isInFlight)
                .toList();
        String firstName = inFlight.stream()
                .map(j -> j.getMetadata().getName())
                .findFirst()
                .orElse(null);
        return new ActiveJobs(inFlight.size(), firstName);
    }

    /** True if a Job belongs to the named CronJob, by owning label/annotation OR name convention. */
    private static boolean belongsToCronJob(Job j, String cronJobName) {
        Map<String, String> labels = j.getMetadata().getLabels();
        Map<String, String> ann = j.getMetadata().getAnnotations();
        if (labels != null && cronJobName.equals(labels.get("storage-console.nila/cronjob"))) return true;
        if (labels != null && cronJobName.equals(labels.get("cronjob-name"))) return true;
        if (ann != null && cronJobName.equals(ann.get("batch.kubernetes.io/cronjob-name"))) return true;
        // Jobs from `kubectl create job --from=cronjob/<name>` (and our own
        // "<cronJobName>-manual-<stamp>") follow the name-prefix convention and
        // carry the instantiate annotation but no cronjob-name back-reference.
        String name = j.getMetadata().getName();
        boolean manualInstantiate = ann != null
                && ann.containsKey("cronjob.kubernetes.io/instantiate");
        return name != null && name.startsWith(cronJobName + "-") && manualInstantiate;
    }

    /** A Job is in-flight if it is actively running OR has not yet completed/failed. */
    private static boolean isInFlight(Job j) {
        var status = j.getStatus();
        if (status == null) return true; // just-created, no status populated yet
        Integer active = status.getActive();
        Integer succeeded = status.getSucceeded();
        Integer failed = status.getFailed();
        if (active != null && active > 0) return true;
        boolean done = (succeeded != null && succeeded > 0) || (failed != null && failed > 0);
        return !done;
    }

    public record ActiveJobs(long count, String firstJobName) {}

    public Optional<Job> getJob(String jobName) {
        return Optional.ofNullable(
                client.batch().v1().jobs().inNamespace(namespace).withName(jobName).get());
    }

    /** Fetch a tail of logs for the pod that ran this Job. */
    public String tailJobLogs(String jobName, int lines) {
        try {
            var pods = client.pods().inNamespace(namespace)
                    .withLabel("job-name", jobName).list().getItems();
            if (pods.isEmpty()) return "";
            var pod = pods.get(pods.size() - 1);
            PodResource pr = client.pods().inNamespace(namespace).withName(pod.getMetadata().getName());
            return pr.tailingLines(lines).getLog();
        } catch (Exception e) {
            log.warn("Failed to read logs for job {}: {}", jobName, e.getMessage());
            return "";
        }
    }

    // ─────────────────────────────── Util ─────────────────────────────────────

    public static RunSummary summarize(Job j) {
        String name = j.getMetadata().getName();
        String startedAt = Optional.ofNullable(j.getStatus())
                .map(s -> s.getStartTime())
                .orElse(j.getMetadata().getCreationTimestamp());
        String finishedAt = Optional.ofNullable(j.getStatus())
                .map(s -> s.getCompletionTime())
                .orElse(null);
        String outcome;
        Integer succeeded = Optional.ofNullable(j.getStatus()).map(s -> s.getSucceeded()).orElse(null);
        Integer failed = Optional.ofNullable(j.getStatus()).map(s -> s.getFailed()).orElse(null);
        Integer active = Optional.ofNullable(j.getStatus()).map(s -> s.getActive()).orElse(null);
        if (succeeded != null && succeeded > 0) outcome = "succeeded";
        else if (failed != null && failed > 0) outcome = "failed";
        else if (active != null && active > 0) outcome = "running";
        else outcome = "pending";
        return new RunSummary(name, startedAt, finishedAt, outcome);
    }

    public record RunSummary(String jobName, String startedAt, String finishedAt, String outcome) {}

    /**
     * Estimate the next scheduled run if the CronJob is unsuspended.
     * We do not parse the full cron expression — we only handle the simple
     * "M H * * *" form (daily at H:M) which is what all three of our CronJobs use.
     * If parsing fails we return null and the UI shows a fallback string.
     */
    public static Instant estimateNextRun(String cronExpr, String timeZone) {
        try {
            String[] parts = cronExpr.trim().split("\\s+");
            if (parts.length < 5) return null;
            int minute = Integer.parseInt(parts[0]);
            int hour = Integer.parseInt(parts[1]);
            ZoneId zone = timeZone == null || timeZone.isBlank()
                    ? ZoneId.systemDefault()
                    : ZoneId.of(timeZone);
            ZonedDateTime now = ZonedDateTime.now(zone);
            ZonedDateTime candidate = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
            if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
            return candidate.toInstant();
        } catch (Exception ignored) {
            return null;
        }
    }
}
