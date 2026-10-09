-- Schema 2 (persistent-state phase P2): the postage hold that pays for a piece of mail, so a book rebuilt
-- from its record keeps it and settles on delivery.
ALTER TABLE mail ADD COLUMN hold_id CHAR(36)
