-- Postal mail store, schema 1 (docs/design/persistent-state.md §5). Dialect-neutral: runs unchanged on
-- SQLite and MySQL/MariaDB. UUIDs CHAR(36), times epoch-millis BIGINT, no vendor types.

CREATE TABLE mail (
    mail_id         CHAR(36)      NOT NULL PRIMARY KEY,
    kind            VARCHAR(16)   NOT NULL,
    state           VARCHAR(24)   NOT NULL,
    version         INT           NOT NULL,
    origin_server   VARCHAR(64)   NOT NULL,
    dest_server     VARCHAR(64)   NOT NULL,
    origin_office   VARCHAR(64),
    dest_office     VARCHAR(64),
    dest_address    VARCHAR(64),
    custody_server  VARCHAR(64)   NOT NULL,
    custody_kind    VARCHAR(8)    NOT NULL,
    custody_ref     VARCHAR(160),
    pending_state   VARCHAR(24),
    pending_kind    VARCHAR(8),
    pending_ref     VARCHAR(160),
    sender_uuid     CHAR(36),
    attention_uuid  CHAR(36),
    cod_amount      DECIMAL(19,2) NOT NULL,
    postage_paid    DECIMAL(19,2) NOT NULL,
    payload_format  VARCHAR(32)   NOT NULL,
    payload         BLOB,
    mc_data_version INT           NOT NULL,
    created_at      BIGINT        NOT NULL,
    updated_at      BIGINT        NOT NULL,
    due_at          BIGINT,
    -- Items never cross servers: only letters may wait in the network (§6, decision 2).
    CONSTRAINT mail_network_letters_only CHECK (state <> 'IN_NETWORK' OR kind = 'LETTER')
);

CREATE INDEX mail_custody ON mail (custody_server, custody_kind);
CREATE INDEX mail_state ON mail (state);

-- One row per transition, written in the same transaction as the mail update; (mail_id, version) is unique.
CREATE TABLE mail_event (
    mail_id     CHAR(36)     NOT NULL,
    version     INT          NOT NULL,
    at          BIGINT       NOT NULL,
    server_id   VARCHAR(64)  NOT NULL,
    from_state  VARCHAR(24),
    to_state    VARCHAR(24)  NOT NULL,
    actor_kind  VARCHAR(16)  NOT NULL,
    actor_ref   VARCHAR(64),
    detail      VARCHAR(255),
    PRIMARY KEY (mail_id, version)
);

-- A postman run in progress, so a restart or an NPC death can resume or requeue it.
CREATE TABLE route_run (
    run_id      CHAR(36)     NOT NULL PRIMARY KEY,
    server_id   VARCHAR(64)  NOT NULL,
    office      VARCHAR(64)  NOT NULL,
    address     VARCHAR(64)  NOT NULL,
    npc_ref     VARCHAR(64),
    started_at  BIGINT       NOT NULL,
    waypoint    INT          NOT NULL,
    direction   VARCHAR(8)   NOT NULL
);
