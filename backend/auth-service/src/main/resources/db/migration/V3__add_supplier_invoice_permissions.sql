-- Supplier invoice permissions, for purchasing-service's three-way matching endpoints.
--
-- REQUIREMENTS.md always gave the ACCOUNTANT role `supplier-invoice:*`, but the permission itself
-- was never seeded - there was nothing to grant until purchasing existed. Added forward rather
-- than by editing V2, which has shipped.
--
-- Matching is split in two deliberately. Viewing an invoice and its variances is a reporting
-- activity a buyer or auditor needs; accepting an exception commits the business to paying more
-- than the delivery justifies, and that is a financial decision.

INSERT INTO permissions (id, code, category, description)
VALUES
    (gen_random_uuid(), 'supplier-invoice:view',   'purchasing',
     'View supplier invoices and their match variances'),
    (gen_random_uuid(), 'supplier-invoice:manage', 'purchasing',
     'Record invoices, accept match exceptions and clear invoices for payment')
ON CONFLICT (code) DO NOTHING;

-- Financial oversight: the role that settles what suppliers are owed.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.code = 'ACCOUNTANT'
  AND p.code IN ('supplier-invoice:view', 'supplier-invoice:manage')
ON CONFLICT DO NOTHING;

-- A buyer needs to see whether an invoice matched, but not to wave through an exception.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
         CROSS JOIN permissions p
WHERE r.code IN ('BRANCH_MANAGER', 'STOCK_CONTROLLER', 'AUDITOR')
  AND p.code = 'supplier-invoice:view'
ON CONFLICT DO NOTHING;

-- SUPER_ADMIN carries '*', which AccessTokenIssuer expands into the concrete permission list at
-- issue time, so it picks these up with no grant here.
