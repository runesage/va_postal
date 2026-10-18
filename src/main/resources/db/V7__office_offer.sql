-- Schema 7 (docs/design/central-office.md §3, "Selling an office"): an office's owner offers it to another player
-- for a price; the buyer accepts at Central's counter. One OPEN offer per office at a time; an offer that isn't
-- accepted in time EXPIRES. Nothing is paid or held until it's accepted.
CREATE TABLE office_offer (
    offer_id    CHAR(36)     NOT NULL PRIMARY KEY,
    server_id   VARCHAR(64)  NOT NULL,
    office      VARCHAR(64)  NOT NULL,
    seller_uuid CHAR(36)     NOT NULL,
    seller_name VARCHAR(16)  NOT NULL,
    buyer_uuid  CHAR(36)     NOT NULL,
    buyer_name  VARCHAR(16)  NOT NULL,
    price       DECIMAL(19,2) NOT NULL,
    state       VARCHAR(16)  NOT NULL,
    created_at  BIGINT       NOT NULL,
    expires_at  BIGINT       NOT NULL,
    updated_at  BIGINT       NOT NULL
);

CREATE INDEX office_offer_office ON office_offer (server_id, office, state);
CREATE INDEX office_offer_buyer ON office_offer (buyer_uuid, state);
