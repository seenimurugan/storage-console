CREATE TABLE app_user (
    id              BIGSERIAL PRIMARY KEY,
    username        VARCHAR(64)  NOT NULL UNIQUE,
    password_hash   VARCHAR(128) NOT NULL,
    display_name    VARCHAR(128) NOT NULL,
    role            VARCHAR(16)  NOT NULL CHECK (role IN ('ADMIN')),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Audit / run history.  Each row corresponds to a Kubernetes Job we observed
-- for one of the three managed CronJobs.  Populated when the UI triggers a
-- manual run, or via a background poller when an auto run lands.
CREATE TABLE task_run (
    id              BIGSERIAL PRIMARY KEY,
    task_id         VARCHAR(64)  NOT NULL,
    job_name        VARCHAR(253) NOT NULL UNIQUE,
    trigger_kind    VARCHAR(16)  NOT NULL CHECK (trigger_kind IN ('AUTO','MANUAL')),
    triggered_by    VARCHAR(64),
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    outcome         VARCHAR(16),
    exit_code       INT,
    log_excerpt     TEXT
);
CREATE INDEX ix_task_run_task ON task_run(task_id, started_at DESC);

-- Audit trail for mode toggles.
CREATE TABLE audit_event (
    id              BIGSERIAL PRIMARY KEY,
    at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor           VARCHAR(64),
    action          VARCHAR(64)  NOT NULL,
    target          VARCHAR(128) NOT NULL,
    detail          TEXT
);
CREATE INDEX ix_audit_at ON audit_event(at DESC);
