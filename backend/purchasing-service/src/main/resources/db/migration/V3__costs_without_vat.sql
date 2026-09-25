-- Costs are kept without VAT. The business is VAT-registered and reclaims what it pays suppliers,
-- so that VAT is not part of what the goods cost - and the margin reports, which compare cost with
-- a price net of output VAT, need both sides net.
--
-- A delivery may be keyed in as invoiced, with VAT: the cost is then stored without it, and what
-- was typed, the rate and the VAT itself are kept beside it. unit_cost keeps meaning "without VAT",
-- which is what it always held when keyed in that way.
--
-- New columns with defaults: safe with the previous version still running.
ALTER TABLE goods_received_notes
    ADD COLUMN costs_include_tax BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN input_tax_total NUMERIC(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE grn_lines
    -- The cost as keyed in: with VAT when the delivery was, otherwise the same as unit_cost.
    ADD COLUMN entered_unit_cost NUMERIC(19, 4),
    ADD COLUMN tax_rate NUMERIC(9, 6),
    -- The VAT on the accepted quantity: what can be reclaimed.
    ADD COLUMN input_tax NUMERIC(19, 4) NOT NULL DEFAULT 0;

ALTER TABLE purchase_orders
    ADD COLUMN costs_include_tax BOOLEAN NOT NULL DEFAULT FALSE;
