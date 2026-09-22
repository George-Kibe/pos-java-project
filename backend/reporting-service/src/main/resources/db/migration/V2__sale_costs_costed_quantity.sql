-- How much of each sale line inventory could actually cost.
--
-- Stock sold ahead of its delivery (negative stock) comes out of no batch, so it has no cost. Until
-- now it was projected as costing nothing, which reported it at a 100% margin. The line quantity
-- stays; costed_quantity says how much of it the cost covers, and the reports show the rest as
-- uncosted.
--
-- Existing rows are assumed fully costed. They were projected before the distinction existed; a
-- rebuild (POST /api/v1/reports/rebuild) recomputes them from the event log.
ALTER TABLE report_sale_costs ADD COLUMN costed_quantity NUMERIC(19, 3);
UPDATE report_sale_costs SET costed_quantity = quantity;
ALTER TABLE report_sale_costs ALTER COLUMN costed_quantity SET NOT NULL;
