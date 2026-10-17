-- Schema 6 (docs/design/deeds.md §7): deeds, documents bought in person and used on a chest. One table for both
-- kinds: ADDRESS (an address deed from a local office) and CHARTER (a post office charter from Central, later).
-- PENDING and ISSUED deeds reserve their name and hold their price in Central's account.
CREATE TABLE deed (
    deed_id     CHAR(36)     NOT NULL PRIMARY KEY,
    kind        VARCHAR(16)  NOT NULL,
    state       VARCHAR(16)  NOT NULL,
    holder_uuid CHAR(36)     NOT NULL,
    holder_name VARCHAR(16)  NOT NULL,
    server_id   VARCHAR(64)  NOT NULL,
    office      VARCHAR(64)  NOT NULL,
    name        VARCHAR(64)  NOT NULL,
    name_lower  VARCHAR(64)  NOT NULL,
    price       DECIMAL(19,2) NOT NULL,
    issue_no    INT          NOT NULL,
    created_at  BIGINT       NOT NULL,
    expires_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL
);

CREATE INDEX deed_name ON deed (kind, office, name_lower, state);
CREATE INDEX deed_holder ON deed (holder_uuid);
CREATE INDEX deed_due ON deed (server_id, state, expires_at);
