package handlers

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

var namespace = func() string {
	if v := os.Getenv("NAMESPACE"); v != "" {
		return v
	}
	return "homelab"
}()

// actionToCronJob maps the URL action param to the CronJob name in-cluster.
var actionToCronJob = map[string]string{
	"move-images":   "tier-mover-immich",
	"move-movies":   "tier-mover-jellyfin",
	"backup-immich": "immich-backup",
}

// ── helpers ──────────────────────────────────────────────────────────────────

func runKubectl(args ...string) (string, string, error) {
	cmd := exec.Command("kubectl", args...)
	var stdout, stderr bytes.Buffer
	cmd.Stdout = &stdout
	cmd.Stderr = &stderr
	err := cmd.Run()
	return stdout.String(), stderr.String(), err
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_ = json.NewEncoder(w).Encode(v)
}

func writeError(w http.ResponseWriter, code int, msg string) {
	http.Error(w, msg, code)
}

// ── POST /api/run/{action} ────────────────────────────────────────────────────

func RunAction(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		writeError(w, http.StatusMethodNotAllowed, "POST required")
		return
	}

	// Extract action from path: /api/run/<action>
	action := strings.TrimPrefix(r.URL.Path, "/api/run/")
	action = strings.Trim(action, "/")

	cronJob, ok := actionToCronJob[action]
	if !ok {
		writeError(w, http.StatusBadRequest, fmt.Sprintf("unknown action %q; valid: move-images, move-movies, backup-immich", action))
		return
	}

	// Validate the CronJob exists before spawning a Job from it.
	_, stderr, err := runKubectl("get", "cronjob", cronJob, "-n", namespace, "--ignore-not-found")
	if err != nil {
		writeError(w, http.StatusServiceUnavailable,
			fmt.Sprintf("cannot reach cluster or cronjob %q not found: %s", cronJob, stderr))
		return
	}

	jobName := fmt.Sprintf("%s-%d", action, time.Now().Unix())

	_, stderr, err = runKubectl(
		"create", "job", jobName,
		"--from=cronjob/"+cronJob,
		"-n", namespace,
	)
	if err != nil {
		writeError(w, http.StatusInternalServerError,
			fmt.Sprintf("failed to create job: %s", stderr))
		return
	}

	writeJSON(w, http.StatusOK, map[string]string{
		"jobName":   jobName,
		"namespace": namespace,
	})
}

// ── GET /api/jobs/{name}/status ───────────────────────────────────────────────

type JobStatus struct {
	Phase       string `json:"phase"`        // pending | running | succeeded | failed
	StartedAt   string `json:"startedAt"`    // RFC3339 or ""
	CompletedAt string `json:"completedAt"`  // RFC3339 or ""
	Succeeded   int    `json:"succeeded"`
	Failed      int    `json:"failed"`
}

func JobStatusHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "GET required")
		return
	}

	jobName := extractJobName(r.URL.Path, "/api/jobs/", "/status")

	// kubectl get job <name> -n homelab -o json
	out, stderr, err := runKubectl("get", "job", jobName, "-n", namespace, "-o", "json")
	if err != nil {
		writeError(w, http.StatusNotFound, fmt.Sprintf("job not found: %s", stderr))
		return
	}

	// Parse the status fields we need via a minimal struct.
	var raw struct {
		Status struct {
			StartTime      string `json:"startTime"`
			CompletionTime string `json:"completionTime"`
			Succeeded      int    `json:"succeeded"`
			Failed         int    `json:"failed"`
			Active         int    `json:"active"`
		} `json:"status"`
	}
	if err := json.Unmarshal([]byte(out), &raw); err != nil {
		writeError(w, http.StatusInternalServerError, "failed to parse job JSON")
		return
	}

	s := raw.Status
	var phase string
	switch {
	case s.Succeeded > 0:
		phase = "succeeded"
	case s.Failed > 0 && s.Active == 0:
		phase = "failed"
	case s.Active > 0:
		phase = "running"
	default:
		phase = "pending"
	}

	writeJSON(w, http.StatusOK, JobStatus{
		Phase:       phase,
		StartedAt:   s.StartTime,
		CompletedAt: s.CompletionTime,
		Succeeded:   s.Succeeded,
		Failed:      s.Failed,
	})
}

// ── GET /api/jobs/{name}/logs ─────────────────────────────────────────────────

func JobLogsHandler(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "GET required")
		return
	}

	jobName := extractJobName(r.URL.Path, "/api/jobs/", "/logs")

	// Find the pod created by this Job (label job-name=<jobName>).
	podOut, _, err := runKubectl(
		"get", "pods",
		"-n", namespace,
		"-l", "job-name="+jobName,
		"--no-headers",
		"-o", "custom-columns=NAME:.metadata.name",
	)
	if err != nil || strings.TrimSpace(podOut) == "" {
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusOK)
		_, _ = io.WriteString(w, "Waiting for pod to be scheduled…\n")
		return
	}

	podName := strings.TrimSpace(strings.Split(podOut, "\n")[0])

	logOut, _, _ := runKubectl(
		"logs", podName,
		"-n", namespace,
		"--tail=200",
	)

	// Strip ANSI escape codes for clean display.
	clean := stripANSI(logOut)

	w.Header().Set("Content-Type", "text/plain")
	w.WriteHeader(http.StatusOK)
	_, _ = io.WriteString(w, clean)
}

// ── GET /api/healthz ──────────────────────────────────────────────────────────

func Healthz(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/plain")
	_, _ = io.WriteString(w, "ok\n")
}

// ── internals ─────────────────────────────────────────────────────────────────

// extractJobName strips a prefix and suffix from the URL path to get the job name.
// e.g. /api/jobs/move-images-1234567890/status → move-images-1234567890
func extractJobName(path, prefix, suffix string) string {
	s := strings.TrimPrefix(path, prefix)
	s = strings.TrimSuffix(s, suffix)
	return strings.Trim(s, "/")
}

var ansiRE = regexp.MustCompile(`\x1b\[[0-9;]*m`)

func stripANSI(s string) string {
	return ansiRE.ReplaceAllString(s, "")
}
