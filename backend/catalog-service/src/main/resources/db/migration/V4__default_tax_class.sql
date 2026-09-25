-- The tax class a new product starts with, so the common case - standard-rated - is not chosen by
-- hand every time. One default at most; the seeded STANDARD class starts as it.
--
-- A column with a default, and an index: safe with the previous version still running.
ALTER TABLE tax_classes ADD COLUMN is_default BOOLEAN NOT NULL DEFAULT FALSE;
UPDATE tax_classes SET is_default = TRUE WHERE code = 'STANDARD';
CREATE UNIQUE INDEX uq_tax_classes_one_default ON tax_classes (is_default) WHERE is_default;
