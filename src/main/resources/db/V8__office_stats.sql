-- Schema 8 (docs/design/central-office.md §7): one row per office per day (UTC days since the epoch), summarised from
-- mail_event by the stats roll-up. Today's row is rewritten as the day goes on; earlier days settle once complete.
-- transit_ms is the sum, over the day's deliveries, of the time from pickup to delivery.
CREATE TABLE office_stats (
    server_id  VARCHAR(64)  NOT NULL,
    office     VARCHAR(64)  NOT NULL,
    day        INT          NOT NULL,
    sent       INT          NOT NULL,
    delivered  INT          NOT NULL,
    on_time    INT          NOT NULL,
    lost       INT          NOT NULL,
    transit_ms BIGINT       NOT NULL,
    PRIMARY KEY (server_id, office, day)
);
