-- Schema 5 (persistent-state phase P4, docs/design/persistent-state.md §17): the players the network knows,
-- one row per player UUID. Each server records its players joining and leaving, so mail can be addressed to
-- any player by name, whichever server they are on and whether or not they're online.
CREATE TABLE network_player (
    player_uuid CHAR(36)     NOT NULL PRIMARY KEY,
    name        VARCHAR(16)  NOT NULL,
    name_lower  VARCHAR(16)  NOT NULL,
    server_id   VARCHAR(64)  NOT NULL,
    online      INT          NOT NULL,
    last_seen   BIGINT       NOT NULL
);

CREATE INDEX network_player_name ON network_player (name_lower);

-- The destination's poll for letters waiting for it.
CREATE INDEX mail_network ON mail (state, dest_server);
