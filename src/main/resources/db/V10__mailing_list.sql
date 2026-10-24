-- Schema 10 (mass mailers, docs/design/central-office.md §9): who is on which mailing list. A list is an office's
-- subscribers ("office:<office>", opt-in: mode SUBSCRIBED) or a Towny town's residents ("town:<town>", every resident
-- is on it unless they opted out: mode OPTED_OUT). One row per player per list.
CREATE TABLE mailing_list (
    server_id   VARCHAR(64) NOT NULL,
    list        VARCHAR(80) NOT NULL,
    player_uuid CHAR(36)    NOT NULL,
    player_name VARCHAR(16) NOT NULL,
    mode        VARCHAR(16) NOT NULL,
    at          BIGINT      NOT NULL,
    PRIMARY KEY (server_id, list, player_uuid)
);

CREATE INDEX mailing_list_player ON mailing_list (player_uuid);
