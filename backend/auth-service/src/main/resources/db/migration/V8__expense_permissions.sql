-- The expenses register, for profit and loss.
--
-- expense:record       record a branch's expenses, and void one entered in error.
-- expense:approve      approve or refuse an expense above the approval limit; never one's own.
-- expense:view         read the register.
-- expense:head-office  see and record head office's expenses. Granted to no role: SUPER_ADMIN
--                      holds '*', expanded to every permission when a token is issued.
--
-- Branch managers record and approve their branches' expenses - another manager, or the
-- administrator, approves what a manager recorded. Accountants and auditors read them.
INSERT INTO permissions (id, code, category, description) VALUES
    (gen_random_uuid(), 'expense:record',      'expenses', 'Record and void branch expenses'),
    (gen_random_uuid(), 'expense:approve',     'expenses', 'Approve or refuse expenses above the approval limit'),
    (gen_random_uuid(), 'expense:view',        'expenses', 'See the expenses register'),
    (gen_random_uuid(), 'expense:head-office', 'expenses', 'See and record head office expenses (administrators only)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('expense:record', 'expense:approve', 'expense:view')
WHERE r.code = 'BRANCH_MANAGER'
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'expense:view'
WHERE r.code IN ('ACCOUNTANT', 'AUDITOR')
ON CONFLICT DO NOTHING;
