-- Source of link ids. The service calls nextval() BEFORE insert because the short code is derived
-- from the id (Feistel permutation + Base62, see ADR-0001).
CREATE SEQUENCE link_id_seq;

CREATE TABLE link (
    id           BIGINT       PRIMARY KEY,
    code         VARCHAR(32)  NOT NULL UNIQUE,
    original_url TEXT         NOT NULL,
    custom_alias BOOLEAN      NOT NULL DEFAULT FALSE,
    status       VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'DELETED')),
    created_by   VARCHAR(64),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version      INT          NOT NULL DEFAULT 0
);

-- Custom aliases are unique case-insensitively ("MyLink" vs "mylink"); the DB settles races.
-- Generated codes stay case-sensitive (Base62 needs both cases), so this index is partial.
CREATE UNIQUE INDEX ux_link_alias_lower ON link (lower(code)) WHERE custom_alias;

CREATE TABLE idempotency_record (
    key             VARCHAR(128) PRIMARY KEY,
    api_key_name    VARCHAR(64),
    request_hash    CHAR(64),
    response_status INT,
    response_body   JSONB,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX ix_idempotency_record_created_at ON idempotency_record (created_at);
