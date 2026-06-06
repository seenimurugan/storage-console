package com.nila.storageconsole.secretsbackup;

import com.nila.storageconsole.k8s.KubernetesService;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates and monitors one-off secrets-backup Jobs.
 * <p>
 * The Job runs alpine + age + kubectl, dumps all cluster secrets across DR-relevant
 * namespaces, encrypts to the hardcoded age recipient public key, and writes a
 * {@code secrets-<date>.age} file to the backup HDD.
 * <p>
 * The age key is a PUBLIC key — safe to embed in a ConfigMap.
 * The matching private key is offline and is never present in the cluster.
 */
@Service
public class SecretsBackupService {

    private static final Logger log = LoggerFactory.getLogger(SecretsBackupService.class);

    /** Job name prefix — must match the RBAC resourceNames entry in 70-secrets-backup.yaml. */
    static final String JOB_NAME_PREFIX = "secrets-backup-";

    /** Label used to list/identify jobs created by this service. */
    static final String LABEL_KEY = "storage-console.nila/task";
    static final String LABEL_VALUE = "secrets-backup";

    /** SA that has cluster-wide get/list on secrets — dedicated, isolated. */
    private static final String SECRETS_BACKUP_SA = "secrets-backup";

    /** age recipient public key: offline DR key, encrypt-only. */
    private static final String AGE_RECIPIENT =
            "age1u79eew0x7dm8s6pnk7yvpzg2m0q3mgmrf72cn0wmm55fv5zu2gqs29y59m";

    private static final String BACKUP_HDD_PATH = "/hdd-root/homelab-backup-hdd/secrets";
    private static final String BACKUP_HDD_SENTINEL = "/hdd-root/homelab-backup-hdd/.homelab-backup-hdd";

    private final KubernetesClient client;
    private final KubernetesService k8sSvc;
    private final String namespace;

    public SecretsBackupService(KubernetesClient client,
                                KubernetesService k8sSvc,
                                @Value("${storage-console.k8s.namespace}") String namespace) {
        this.client = client;
        this.k8sSvc = k8sSvc;
        this.namespace = namespace;
    }

    /**
     * Spawn a one-off secrets-backup Job. Refuses if one is already in-flight.
     *
     * @return the created Job
     * @throws IllegalStateException if a backup is already running
     */
    public Job trigger(String actor) {
        log.info("event=secrets_backup.trigger.start actor={} namespace={}", actor, namespace);

        // Concurrency guard
        Optional<Job> inFlight = findInFlightJob();
        if (inFlight.isPresent()) {
            String name = inFlight.get().getMetadata().getName();
            log.warn("event=secrets_backup.trigger.rejected actor={} reason=already-running jobName={}", actor, name);
            throw new IllegalStateException("A secrets-backup job is already running: " + name);
        }

        String stamp = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
        String jobName = JOB_NAME_PREFIX + stamp;

        // Inline shell script — mirrors backup-secrets.sh logic but adapted for in-cluster
        // execution: no host-side .env loading, age/kubectl installed via apk.
        String script = """
                #!/bin/sh
                # ash (busybox) does not support pipefail; avoid set -e to allow
                # per-namespace error handling without aborting the whole script.
                set -u

                RECIPIENT="%s"
                NAMESPACES="homelab monitoring tailscale default kube-system"
                SECRETS_DIR="%s"
                SENTINEL="%s"
                DATE="$(date +%%Y%%m%%d-%%H%%M%%S)"
                OUT="${SECRETS_DIR}/secrets-${DATE}.age"
                TMP="${OUT}.tmp"
                KEEP_SECRETS=12

                log() { echo "ts=$(date +%%Y-%%m-%%dT%%H:%%M:%%S) actor=secrets-backup $*"; }

                log "event=secrets_backup.start outcome=begin"

                # ── tooling ────────────────────────────────────────────────────────────
                log "event=secrets_backup.toolinstall outcome=begin reason=apk-add-kubectl-age"
                if ! apk add --no-cache kubectl age >/dev/null 2>&1; then
                  log "event=secrets_backup.toolinstall outcome=fail reason=apk-add-failed"
                  exit 1
                fi
                log "event=secrets_backup.toolinstall outcome=success"

                # ── HDD guard ─────────────────────────────────────────────────────────
                if [ ! -f "${SENTINEL}" ]; then
                  log "event=secrets_backup.guard outcome=fail reason=backup-hdd-sentinel-missing path=${SENTINEL}"
                  exit 1
                fi
                log "event=secrets_backup.guard outcome=pass reason=sentinel-present"

                if ! mkdir -p "${SECRETS_DIR}" 2>/dev/null; then
                  log "event=secrets_backup.guard outcome=fail reason=mkdir-failed path=${SECRETS_DIR}"
                  exit 1
                fi
                if ! ( : > "${SECRETS_DIR}/.writetest" ) 2>/dev/null; then
                  log "event=secrets_backup.guard outcome=fail reason=write-test-failed path=${SECRETS_DIR}"
                  exit 1
                fi
                rm -f "${SECRETS_DIR}/.writetest"
                log "event=secrets_backup.guard outcome=pass reason=writable path=${SECRETS_DIR}"

                # ── cluster reachable (use a permitted call, not cluster-info which needs svc list) ──
                if ! kubectl auth can-i get secrets >/dev/null 2>&1; then
                  log "event=secrets_backup.preflight outcome=fail reason=cluster-unreachable-or-no-rbac"
                  exit 1
                fi

                # ── count secrets per namespace ────────────────────────────────────────
                # Use direct secret listing to check namespace reachability — no 'get namespace'
                # permission needed (ClusterRole only has get/list on secrets).
                PRESENT_NS=""
                NS_OK=0
                SECRET_COUNT=0
                for ns in $NAMESPACES; do
                  NS_OUT=$(kubectl -n "$ns" get secret --no-headers 2>/tmp/ns-err-$ns || true)
                  if grep -qiE 'NotFound|Forbidden|not found' /tmp/ns-err-$ns 2>/dev/null; then
                    log "event=secrets_backup.namespace outcome=skip ns=$ns reason=not-found-or-forbidden"
                    rm -f /tmp/ns-err-$ns
                    continue
                  fi
                  if [ -s /tmp/ns-err-$ns ]; then
                    NS_ERR=$(head -1 /tmp/ns-err-$ns)
                    log "event=secrets_backup.namespace outcome=error ns=$ns reason=${NS_ERR}"
                    rm -f /tmp/ns-err-$ns
                    exit 1
                  fi
                  rm -f /tmp/ns-err-$ns
                  NCOUNT=$(echo "$NS_OUT" | grep -c . || true)
                  PRESENT_NS="${PRESENT_NS} ${ns}"
                  NS_OK=$((NS_OK + 1))
                  SECRET_COUNT=$((SECRET_COUNT + NCOUNT))
                  log "event=secrets_backup.namespace outcome=ok ns=$ns secrets=$NCOUNT"
                done

                if [ "$NS_OK" -eq 0 ]; then
                  log "event=secrets_backup.dump outcome=fail reason=no-namespaces-resolved"
                  exit 1
                fi

                # ── dump → encrypt (plaintext only in-memory pipe, never on disk) ──────
                # Write to TMP via age; check TMP exists and is non-empty.
                # Using echo to keep shell format specifiers out of the Java text block.
                {
                  echo "# homelab secrets DR dump"
                  echo "# generated=${DATE}"
                  echo "# namespaces=${PRESENT_NS}"
                  for ns in $PRESENT_NS; do
                    echo "---"
                    echo "# namespace: ${ns}"
                    kubectl -n "$ns" get secret -o yaml 2>/dev/null || true
                  done
                } | age -r "${RECIPIENT}" -o "${TMP}"

                if [ ! -s "${TMP}" ]; then
                  rm -f "${TMP}"
                  log "event=secrets_backup.encrypt outcome=fail reason=empty-or-missing-output"
                  exit 1
                fi

                mv "${TMP}" "${OUT}"
                SIZE=$(du -h "${OUT}" | cut -f1)
                log "event=secrets_backup.encrypt outcome=success dest=${OUT} size=${SIZE} namespaces_ok=${NS_OK} secrets_dumped=${SECRET_COUNT}"

                # ── prune old dumps (keep newest KEEP_SECRETS) ─────────────────────────
                log "event=secrets_backup.prune.start keep=${KEEP_SECRETS}"
                ( cd "${SECRETS_DIR}" && ls -t secrets-*.age 2>/dev/null | tail -n +$((KEEP_SECRETS + 1)) | while IFS= read -r old; do
                    log "event=secrets_backup.prune.delete target=${old} outcome=success"
                    rm -f "${old}"
                  done ) || true
                log "event=secrets_backup.prune.end outcome=done"

                log "event=secrets_backup.end outcome=success dest=${OUT}"
                exit 0
                """.formatted(AGE_RECIPIENT, BACKUP_HDD_PATH, BACKUP_HDD_SENTINEL);

        Job job = new JobBuilder()
                .withNewMetadata()
                    .withName(jobName)
                    .withNamespace(namespace)
                    .withLabels(Map.of(LABEL_KEY, LABEL_VALUE, "storage-console.nila/trigger", "manual"))
                .endMetadata()
                .withNewSpec()
                    .withBackoffLimit(1)
                    .withActiveDeadlineSeconds(1800L)
                    .withNewTemplate()
                        .withNewSpec()
                            .withServiceAccountName(SECRETS_BACKUP_SA)
                            .withRestartPolicy("OnFailure")
                            .withNewSecurityContext()
                                .withRunAsUser(0L)
                            .endSecurityContext()
                            .withContainers(buildContainer(script))
                            .withVolumes(buildHddVolume())
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();

        Job created = client.batch().v1().jobs().inNamespace(namespace).resource(job).create();
        log.info("event=secrets_backup.trigger.created actor={} jobName={} outcome=201",
                actor, created.getMetadata().getName());
        return created;
    }

    /** Status / last-run information for the secrets-backup card. */
    public SecretsBackupStatus getStatus() {
        List<Job> all = listJobs(10);
        if (all.isEmpty()) {
            log.info("event=secrets_backup.status outcome=no-runs");
            return new SecretsBackupStatus(null, null, null, null);
        }
        Job latest = all.get(0);
        KubernetesService.RunSummary s = KubernetesService.summarize(latest);

        Optional<Job> inFlight = all.stream()
                .filter(SecretsBackupService::isInFlight)
                .findFirst();

        log.info("event=secrets_backup.status latestJob={} outcome={} inFlight={}",
                s.jobName(), s.outcome(), inFlight.isPresent());

        return new SecretsBackupStatus(
                s.jobName(),
                s.startedAt(),
                s.finishedAt(),
                s.outcome()
        );
    }

    /** Fetch logs for a specific job name. */
    public String getLogs(String jobName, int lines) {
        log.info("event=secrets_backup.logs.request jobName={} lines={}", jobName, lines);
        return k8sSvc.tailJobLogs(jobName, lines);
    }

    // ─────────────────────────── helpers ────────────────────────────────────

    private Optional<Job> findInFlightJob() {
        return listJobs(20).stream()
                .filter(SecretsBackupService::isInFlight)
                .findFirst();
    }

    private List<Job> listJobs(int limit) {
        return client.batch().v1().jobs().inNamespace(namespace)
                .withLabelSelector(LABEL_KEY + "=" + LABEL_VALUE)
                .list().getItems()
                .stream()
                .sorted(java.util.Comparator.comparing(
                        (Job j) -> Optional.ofNullable(j.getMetadata().getCreationTimestamp()).orElse(""))
                        .reversed())
                .limit(limit)
                .toList();
    }

    private static boolean isInFlight(Job j) {
        var status = j.getStatus();
        if (status == null) return false;
        Integer active = status.getActive();
        // Only block a new trigger when a pod is actively running.
        // All-null / succeeded / failed states (including evicted or hung jobs)
        // are NOT considered in-flight; activeDeadlineSeconds (1800s) reaps stuck jobs.
        return active != null && active > 0;
    }

    private Container buildContainer(String script) {
        return new ContainerBuilder()
                .withName("secrets-backup")
                .withImage("alpine:3.21")
                .withCommand("/bin/sh", "-c", script)
                .withResources(new ResourceRequirementsBuilder()
                        .withRequests(Map.of(
                                "memory", new Quantity("128Mi"),
                                "cpu", new Quantity("100m")))
                        .withLimits(Map.of(
                                "memory", new Quantity("512Mi"),
                                "cpu", new Quantity("500m")))
                        .build())
                .withVolumeMounts(new VolumeMountBuilder()
                        .withName("hdd-root")
                        .withMountPath("/hdd-root")
                        .withMountPropagation("HostToContainer")
                        .build())
                .build();
    }

    private Volume buildHddVolume() {
        return new VolumeBuilder()
                .withName("hdd-root")
                .withNewHostPath()
                    .withPath("/Volumes")
                    .withType("")
                .endHostPath()
                .build();
    }

    public record SecretsBackupStatus(
            String lastJobName,
            String startedAt,
            String finishedAt,
            String outcome
    ) {}
}
