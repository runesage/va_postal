-- Schema 9 (docs/design/po-boxes.md): P.O. box leases. A box is numbered within its office; one renter per box. Rent
-- is charged each Postal day; what couldn't be paid is arrears, and arrears_days counts the days it has been owed (the
-- lease lapses after Boxes.Grace_days of it).
CREATE TABLE po_box (
    server_id    VARCHAR(64)   NOT NULL,
    office       VARCHAR(64)   NOT NULL,
    number       INT           NOT NULL,
    renter_uuid  CHAR(36)      NOT NULL,
    renter_name  VARCHAR(16)   NOT NULL,
    rented_at    BIGINT        NOT NULL,
    arrears      DECIMAL(19,2) NOT NULL,
    arrears_days INT           NOT NULL,
    PRIMARY KEY (server_id, office, number)
);

CREATE INDEX po_box_renter ON po_box (renter_uuid);
