// Storage Console — vanilla JS, no framework
(function () {
  'use strict';

  const SESSION_KEY = 'storage_console_job';
  const POLL_INTERVAL_MS = 2000;

  let pollTimer = null;
  let elapsedTimer = null;
  let startedAt = null;
  let currentJob = null;
  let logsVisible = true;

  // DOM refs
  const panel      = document.getElementById('job-panel');
  const idleMsg    = document.getElementById('idle-msg');
  const jobNameEl  = document.getElementById('job-name');
  const badge      = document.getElementById('phase-badge');
  const elapsed    = document.getElementById('elapsed');
  const banner     = document.getElementById('banner');
  const logPre     = document.getElementById('log-pre');
  const logToggle  = document.getElementById('log-toggle');
  const buttons    = document.querySelectorAll('.action-btn');

  // ── helpers ──────────────────────────────────────────────────────────────

  function fmtElapsed(ms) {
    const s = Math.floor(ms / 1000);
    if (s < 60)  return s + 's';
    const m = Math.floor(s / 60);
    const r = s % 60;
    return m + 'm ' + r + 's';
  }

  function setBadge(phase) {
    const map = {
      pending:   ['Pending',   'badge-pending'],
      running:   ['Running',   'badge-running'],
      succeeded: ['Succeeded', 'badge-success'],
      failed:    ['Failed',    'badge-failed'],
    };
    const [text, cls] = map[phase] || ['Unknown', 'badge-pending'];
    badge.textContent = text;
    badge.className = 'badge ' + cls;
  }

  function setButtonsDisabled(disabled) {
    buttons.forEach(b => b.disabled = disabled);
  }

  function showPanel(jobName) {
    currentJob = jobName;
    jobNameEl.textContent = jobName;
    panel.classList.remove('hidden');
    idleMsg.classList.add('hidden');
    banner.classList.add('hidden');
    banner.textContent = '';
    logPre.textContent = '';
  }

  function hidePanel() {
    panel.classList.add('hidden');
    idleMsg.classList.remove('hidden');
    currentJob = null;
    sessionStorage.removeItem(SESSION_KEY);
    stopPolling();
    stopElapsed();
    setButtonsDisabled(false);
  }

  function startElapsedTimer(since) {
    startedAt = since ? new Date(since) : new Date();
    stopElapsed();
    function tick() {
      elapsed.textContent = 'Elapsed: ' + fmtElapsed(Date.now() - startedAt.getTime());
    }
    tick();
    elapsedTimer = setInterval(tick, 1000);
  }

  function stopElapsed() {
    if (elapsedTimer) { clearInterval(elapsedTimer); elapsedTimer = null; }
  }

  function stopPolling() {
    if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  }

  // ── log fetching ──────────────────────────────────────────────────────────

  async function fetchLogs(jobName) {
    try {
      const r = await fetch('/api/jobs/' + encodeURIComponent(jobName) + '/logs');
      if (!r.ok) return;
      const text = await r.text();
      if (text.trim()) {
        logPre.textContent = text;
        // auto-scroll to bottom
        logPre.scrollTop = logPre.scrollHeight;
      }
    } catch (_) { /* ignore */ }
  }

  // ── status polling ────────────────────────────────────────────────────────

  async function pollStatus(jobName) {
    try {
      const r = await fetch('/api/jobs/' + encodeURIComponent(jobName) + '/status');
      if (!r.ok) return;
      const data = await r.json();

      setBadge(data.phase);

      if (data.startedAt && !startedAt) {
        startElapsedTimer(data.startedAt);
      }

      if (data.phase === 'running' || data.phase === 'pending') {
        await fetchLogs(jobName);
        return; // keep polling
      }

      // terminal state
      stopPolling();
      stopElapsed();
      setButtonsDisabled(false);
      sessionStorage.removeItem(SESSION_KEY);

      await fetchLogs(jobName); // final logs

      if (data.phase === 'succeeded') {
        // try to parse SUMMARY line from logs
        const summary = parseSummary(logPre.textContent);
        banner.textContent = summary
          ? '✓ Done — ' + summary
          : '✓ Done — job completed successfully';
        banner.className = 'banner banner-success';
      } else {
        banner.textContent = '✗ Failed — see logs below';
        banner.className = 'banner banner-failed';
      }
      banner.classList.remove('hidden');

    } catch (err) {
      console.error('poll error', err);
    }
  }

  function parseSummary(text) {
    // Looks for a line like: SUMMARY: moved 12 files, 4.2 GiB
    const m = text.match(/SUMMARY:\s*(.+)/i);
    return m ? m[1].trim() : null;
  }

  function startPolling(jobName) {
    stopPolling();
    pollStatus(jobName);                         // immediate first hit
    pollTimer = setInterval(() => pollStatus(jobName), POLL_INTERVAL_MS);
  }

  // ── actions ───────────────────────────────────────────────────────────────

  async function runAction(action) {
    setButtonsDisabled(true);
    try {
      const r = await fetch('/api/run/' + action, { method: 'POST' });
      if (!r.ok) {
        const txt = await r.text();
        alert('Error starting job: ' + txt);
        setButtonsDisabled(false);
        return;
      }
      const data = await r.json();
      const jobName = data.jobName;

      sessionStorage.setItem(SESSION_KEY, jobName);
      showPanel(jobName);
      setBadge('pending');
      elapsed.textContent = 'Elapsed: 0s';
      startElapsedTimer(null);
      startPolling(jobName);
    } catch (err) {
      alert('Network error: ' + err.message);
      setButtonsDisabled(false);
    }
  }

  // ── event wiring ──────────────────────────────────────────────────────────

  document.querySelectorAll('.action-btn').forEach(btn => {
    btn.addEventListener('click', () => {
      if (btn.disabled) return;
      runAction(btn.dataset.action);
    });
  });

  logToggle.addEventListener('click', () => {
    logsVisible = !logsVisible;
    logPre.classList.toggle('hidden', !logsVisible);
    logToggle.textContent = logsVisible ? 'Hide logs ▲' : 'Show logs ▼';
  });

  // ── restore session on page load ──────────────────────────────────────────

  const savedJob = sessionStorage.getItem(SESSION_KEY);
  if (savedJob) {
    showPanel(savedJob);
    setBadge('pending');
    elapsed.textContent = 'Checking…';
    startPolling(savedJob);
    setButtonsDisabled(true);
  }

})();
