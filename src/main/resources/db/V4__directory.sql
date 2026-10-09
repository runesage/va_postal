-- Schema 4 (persistent-state phase P3, docs/design/persistent-state.md §5): the network-wide address book,
-- and the servers sharing this database. Each server republishes its own directory rows; routes stay in
-- each server's config.yml.

-- The servers using this database, so two can't share a Network.Server_id unnoticed.
CREATE TABLE postal_server (
    server_id   VARCHAR(64)  NOT NULL PRIMARY KEY,
    instance    CHAR(36)     NOT NULL,
    started_at  BIGINT       NOT NULL,
    last_seen   BIGINT       NOT NULL
);

CREATE TABLE directory_office (
    server_id    VARCHAR(64)  NOT NULL,
    office       VARCHAR(64)  NOT NULL,
    owner_uuid   CHAR(36),
    is_open      INT          NOT NULL,
    is_central   INT          NOT NULL,
    location_key VARCHAR(160),
    updated_at   BIGINT       NOT NULL,
    PRIMARY KEY (server_id, office)
);

CREATE TABLE directory_address (
    server_id    VARCHAR(64)  NOT NULL,
    office       VARCHAR(64)  NOT NULL,
    address      VARCHAR(64)  NOT NULL,
    owner_uuid   CHAR(36),
    is_open      INT          NOT NULL,
    location_key VARCHAR(160),
    updated_at   BIGINT       NOT NULL,
    PRIMARY KEY (server_id, office, address)
);
