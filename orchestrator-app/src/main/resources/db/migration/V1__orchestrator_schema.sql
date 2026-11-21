-- The orchestrator's database. workflow_event is the source of truth (an append-only, hash-chained
-- log); run, stage_instance and approval are projections of it, rewritten in the same transaction
-- as each append so they can never disagree with the log.

-- Runs, as launched (scenario, workflow file) and as projected (status, mode, level).
create table run (
    id               text primary key,
    workflow_id      text        not null,
    workflow_file    text        not null default '',
    scenario_id      text,
    mode             text        not null,
    autonomy_level   text        not null,
    status           text        not null,
    created_at       timestamptz not null,
    updated_at       timestamptz not null
);
create index run_status_idx on run (status);

-- The log. id gives global order; seq is per run (1, 2, …, no gaps: the hash chain counts on it).
-- payload_text is the exact canonical JSON that was hashed (jsonb would reorder keys and reformat
-- numbers); payload is the same as jsonb, for queries.
create table workflow_event (
    id            bigserial   primary key,
    run_id        text        not null,
    seq           bigint      not null,
    type          text        not null,
    stage_id      text,
    actor         text        not null,
    payload_text  text        not null,
    payload       jsonb       generated always as (payload_text::jsonb) stored,
    ts            timestamptz not null,
    prev_hash     char(64)    not null,
    hash          char(64)    not null,
    constraint workflow_event_run_seq unique (run_id, seq),
    constraint workflow_event_seq_positive check (seq >= 1)
);
create index workflow_event_type_idx on workflow_event (run_id, type);

-- Append-only, enforced by the database itself, not only by the application:
-- in production the application's role also has no UPDATE/DELETE/TRUNCATE rights:
--   REVOKE UPDATE, DELETE, TRUNCATE ON workflow_event FROM orchestrator_app;
-- and these triggers stop anyone else (including the table owner) from rewriting history.
create function workflow_event_is_append_only() returns trigger
    language plpgsql as $$
begin
    raise exception 'workflow_event is append-only: % is not allowed', tg_op
        using errcode = 'insufficient_privilege';
end
$$;

create trigger workflow_event_no_update_or_delete
    before update or delete on workflow_event
    for each row execute function workflow_event_is_append_only();

create trigger workflow_event_no_truncate
    before truncate on workflow_event
    for each statement execute function workflow_event_is_append_only();

-- Stage instances as projected from the log (key = stage id, or stage id/task id).
create table stage_instance (
    run_id      text        not null references run (id),
    stage_key   text        not null,
    stage_id    text        not null,
    task_id     text,
    status      text        not null,
    attempt     int         not null,
    loop_count  int         not null,
    started_at  timestamptz,
    ended_at    timestamptz,
    primary key (run_id, stage_key)
);

-- Human checkpoints as projected from the log: one row per APPROVAL_REQUESTED.
-- status: PENDING, GRANTED, REJECTED, or VOIDED (the stage was halted, invalidated or rolled back
-- before anyone decided).
create table approval (
    id            text        primary key,          -- <run id>:<seq of APPROVAL_REQUESTED>
    run_id        text        not null references run (id),
    stage_id      text        not null,
    status        text        not null,
    reason        text        not null,
    summary       jsonb       not null,              -- the request payload: artifacts, risk, policies, diff
    requested_at  timestamptz not null,
    decided_at    timestamptz,
    decided_by    text,
    comment       text
);
create index approval_status_idx on approval (status);
create index approval_run_idx on approval (run_id);

-- Versioned artifacts. content_text is exactly what was hashed; content is the jsonb view.
create table artifact (
    id            text        primary key,          -- <run>:<stage>:<type>:v<version>
    run_id        text        not null,
    stage_id      text        not null,
    type          text        not null,
    version       int         not null,
    content_text  text        not null,
    content       jsonb       generated always as (content_text::jsonb) stored,
    content_hash  char(64)    not null,
    produced_by   jsonb       not null,              -- {agent, model?, promptHash?}
    lineage       jsonb       not null,              -- inputs: [{artifactId, type, version, contentHash}]
    rationale     text        not null,
    created_at    timestamptz not null,
    seq           bigserial   not null,              -- save order (latest of a type = highest)
    constraint artifact_version unique (run_id, stage_id, type, version)
);
create index artifact_run_type_idx on artifact (run_id, type, seq);
