-- Receipt numbers are gapless per branch (receipt_sequences keeps one counter per branch), so every
-- branch issues R-000001. V1 made them unique across the whole business, which let only the first
-- branch ever trade: the second branch's first receipt was refused as a duplicate. Unique per branch.
--
-- Only relaxes constraints, so it is safe with the previous version still running.

ALTER TABLE sales DROP CONSTRAINT uq_sales_receipt_number;
ALTER TABLE sales ADD CONSTRAINT uq_sales_branch_receipt_number UNIQUE (branch_id, receipt_number);

ALTER TABLE receipts DROP CONSTRAINT uq_receipts_number_type;
ALTER TABLE receipts ADD CONSTRAINT uq_receipts_branch_number_type UNIQUE (branch_id, receipt_number, type);
