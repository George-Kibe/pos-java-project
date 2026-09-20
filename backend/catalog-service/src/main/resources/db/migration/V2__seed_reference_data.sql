-- Reference data a supermarket cannot open without.
--
-- Tax classes and rates reflect Kenyan VAT at the time of writing. They are data, not code: when
-- a rate changes, a new tax_rates row is added with the new valid_from and the old row is closed.
-- Nothing is edited, so historical receipts keep reprinting correctly.

INSERT INTO units_of_measure (id, code, name, allows_decimal, decimal_places) VALUES
    (gen_random_uuid(), 'EA',    'Each',       FALSE, 0),
    (gen_random_uuid(), 'KG',    'Kilogram',   TRUE,  3),
    (gen_random_uuid(), 'G',     'Gram',       TRUE,  0),
    (gen_random_uuid(), 'L',     'Litre',      TRUE,  3),
    (gen_random_uuid(), 'ML',    'Millilitre', TRUE,  0),
    (gen_random_uuid(), 'PACK',  'Pack',       FALSE, 0),
    (gen_random_uuid(), 'CASE',  'Case',       FALSE, 0),
    (gen_random_uuid(), 'DOZEN', 'Dozen',      FALSE, 0);

INSERT INTO tax_classes (id, code, name, description) VALUES
    (gen_random_uuid(), 'STANDARD',   'Standard rated', 'Attracts VAT at the standard rate'),
    (gen_random_uuid(), 'ZERO_RATED', 'Zero rated',     'Taxable at 0%: unprocessed foods, bread, milk'),
    (gen_random_uuid(), 'EXEMPT',     'Exempt',         'Outside VAT entirely; no input tax recoverable');

-- Zero-rated and exempt both compute to no tax, but they are genuinely different things and a VAT
-- return reports them separately - which is why they are separate classes rather than one rate of
-- zero.
INSERT INTO tax_rates (id, tax_class_id, rate, valid_from)
SELECT gen_random_uuid(), id, 0.160000, TIMESTAMPTZ '2000-01-01 00:00:00+00'
FROM tax_classes WHERE code = 'STANDARD';

INSERT INTO tax_rates (id, tax_class_id, rate, valid_from)
SELECT gen_random_uuid(), id, 0.000000, TIMESTAMPTZ '2000-01-01 00:00:00+00'
FROM tax_classes WHERE code IN ('ZERO_RATED', 'EXEMPT');

INSERT INTO categories (id, code, name) VALUES
    (gen_random_uuid(), 'GROCERY',   'Grocery'),
    (gen_random_uuid(), 'FRESH',     'Fresh produce'),
    (gen_random_uuid(), 'DAIRY',     'Dairy'),
    (gen_random_uuid(), 'BAKERY',    'Bakery'),
    (gen_random_uuid(), 'BUTCHERY',  'Butchery'),
    (gen_random_uuid(), 'BEVERAGES', 'Beverages'),
    (gen_random_uuid(), 'HOUSEHOLD', 'Household'),
    (gen_random_uuid(), 'UNCATEGORISED', 'Uncategorised');

-- A common scale format: 20 IIIII VVVVV C - prefix, five-digit item code, five-digit weight in
-- grams, check digit. Configured rather than assumed, because the layout differs by scale vendor.
INSERT INTO scale_barcode_rules
    (id, prefix, name, item_code_start, item_code_length, value_start, value_length, embedded_type, value_divisor)
VALUES
    (gen_random_uuid(), '20', 'Weight-embedded (grams)', 2, 5, 7, 5, 'WEIGHT', 1000),
    (gen_random_uuid(), '21', 'Price-embedded (cents)',  2, 5, 7, 5, 'PRICE',  100);
