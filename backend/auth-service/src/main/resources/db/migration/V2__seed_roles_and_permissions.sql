-- Seed permissions, the default roles and a first branch.
--
-- These roles are a starting point, not a fixed set: the business can create its
-- own roles and re-bundle these permissions at runtime through the role builder.
-- What cannot change is the permission vocabulary itself, because endpoints are
-- written against it - so adding a permission here is a code change, while
-- adding a role is not.

-- ---------------------------------------------------------------------------
-- Permissions
-- ---------------------------------------------------------------------------
INSERT INTO permissions (id, code, category, description) VALUES
    (gen_random_uuid(), '*',                  'system',     'Unrestricted access; satisfies every permission check'),
    (gen_random_uuid(), 'branch:access:all',  'system',     'Act in any branch, bypassing branch assignment'),

    (gen_random_uuid(), 'user:view',          'access',     'View users'),
    (gen_random_uuid(), 'user:manage',        'access',     'Create, update and deactivate users'),
    (gen_random_uuid(), 'role:view',          'access',     'View roles and their permissions'),
    (gen_random_uuid(), 'role:manage',        'access',     'Create and edit roles'),
    (gen_random_uuid(), 'branch:view',        'access',     'View branches'),
    (gen_random_uuid(), 'branch:manage',      'access',     'Create and edit branches'),
    (gen_random_uuid(), 'audit:view',         'access',     'Read the audit log'),

    (gen_random_uuid(), 'product:view',       'catalog',    'View products and prices'),
    (gen_random_uuid(), 'product:manage',     'catalog',    'Create and edit products'),
    (gen_random_uuid(), 'price:manage',       'catalog',    'Manage price lists'),
    (gen_random_uuid(), 'price:override',     'catalog',    'Override a price at the till'),
    (gen_random_uuid(), 'promotion:manage',   'catalog',    'Create and edit promotions'),
    (gen_random_uuid(), 'tax:manage',         'catalog',    'Manage tax classes and rates'),

    (gen_random_uuid(), 'inventory:view',     'inventory',  'View stock levels and batches'),
    (gen_random_uuid(), 'inventory:adjust',   'inventory',  'Post stock adjustments'),
    (gen_random_uuid(), 'stocktake:manage',   'inventory',  'Run and approve stock takes'),
    (gen_random_uuid(), 'transfer:manage',    'inventory',  'Create and receive inter-branch transfers'),

    (gen_random_uuid(), 'supplier:manage',    'purchasing', 'Create and edit suppliers'),
    (gen_random_uuid(), 'purchase:view',      'purchasing', 'View purchase orders'),
    (gen_random_uuid(), 'purchase:create',    'purchasing', 'Raise purchase orders'),
    (gen_random_uuid(), 'purchase:approve',   'purchasing', 'Approve purchase orders'),
    (gen_random_uuid(), 'purchase:receive',   'purchasing', 'Receive goods against a purchase order'),

    (gen_random_uuid(), 'shift:open',         'sales',      'Open a till session'),
    (gen_random_uuid(), 'shift:close',        'sales',      'Close your own till session'),
    (gen_random_uuid(), 'shift:close:any',    'sales',      'Close any till session'),
    (gen_random_uuid(), 'cash:drop',          'sales',      'Record a cash drop'),
    (gen_random_uuid(), 'cart:manage',        'sales',      'Build and amend a cart'),
    (gen_random_uuid(), 'sale:create',        'sales',      'Complete a sale'),
    (gen_random_uuid(), 'sale:void',          'sales',      'Void a sale or a line'),
    (gen_random_uuid(), 'sale:refund',        'sales',      'Process a return or refund'),

    (gen_random_uuid(), 'payment:take',       'payments',   'Take payment'),
    (gen_random_uuid(), 'payment:reconcile',  'payments',   'Reconcile payments and settlements'),

    (gen_random_uuid(), 'customer:view',      'customers',  'Look up customers'),
    (gen_random_uuid(), 'customer:manage',    'customers',  'Create and edit customers'),
    (gen_random_uuid(), 'loyalty:adjust',     'customers',  'Adjust loyalty points manually'),

    (gen_random_uuid(), 'report:view',        'reporting',  'View reports across all branches'),
    (gen_random_uuid(), 'report:view:branch', 'reporting',  'View reports for your own branches'),
    (gen_random_uuid(), 'export:data',        'reporting',  'Export report data');

-- ---------------------------------------------------------------------------
-- Roles
-- ---------------------------------------------------------------------------
INSERT INTO roles (id, code, name, description, system_role) VALUES
    (gen_random_uuid(), 'SUPER_ADMIN',      'Super administrator', 'Unrestricted access to everything', TRUE),
    (gen_random_uuid(), 'BRANCH_MANAGER',   'Branch manager',      'Runs one or more branches', TRUE),
    (gen_random_uuid(), 'SUPERVISOR',       'Supervisor',          'Floor supervisor; authorises overrides, voids and refunds', TRUE),
    (gen_random_uuid(), 'CASHIER',          'Cashier',             'Works a register', TRUE),
    (gen_random_uuid(), 'STOCK_CONTROLLER', 'Stock controller',    'Receives, counts and adjusts stock', TRUE),
    (gen_random_uuid(), 'ACCOUNTANT',       'Accountant',          'Financial oversight and reconciliation', TRUE),
    (gen_random_uuid(), 'AUDITOR',          'Auditor',             'Read-only access across the business', TRUE);

-- ---------------------------------------------------------------------------
-- Role to permission mapping
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION grant_permissions(role_code TEXT, permission_codes TEXT[])
RETURNS VOID AS $$
    INSERT INTO role_permissions (role_id, permission_id)
    SELECT r.id, p.id
    FROM roles r
    JOIN permissions p ON p.code = ANY(permission_codes)
    WHERE r.code = role_code
    ON CONFLICT DO NOTHING;
$$ LANGUAGE SQL;

SELECT grant_permissions('SUPER_ADMIN', ARRAY['*']);

SELECT grant_permissions('BRANCH_MANAGER', ARRAY[
    'user:view', 'user:manage', 'role:view', 'branch:view', 'audit:view',
    'product:view', 'price:manage', 'price:override', 'promotion:manage',
    'inventory:view', 'inventory:adjust', 'stocktake:manage', 'transfer:manage',
    'supplier:manage', 'purchase:view', 'purchase:create', 'purchase:approve', 'purchase:receive',
    'shift:open', 'shift:close', 'shift:close:any', 'cash:drop',
    'cart:manage', 'sale:create', 'sale:void', 'sale:refund',
    'payment:take', 'payment:reconcile',
    'customer:view', 'customer:manage', 'loyalty:adjust',
    'report:view:branch', 'export:data'
]);

SELECT grant_permissions('SUPERVISOR', ARRAY[
    'product:view', 'price:override',
    'inventory:view', 'inventory:adjust',
    'shift:open', 'shift:close', 'shift:close:any', 'cash:drop',
    'cart:manage', 'sale:create', 'sale:void', 'sale:refund',
    'payment:take', 'customer:view', 'customer:manage',
    'report:view:branch'
]);

SELECT grant_permissions('CASHIER', ARRAY[
    'product:view',
    'shift:open', 'shift:close',
    'cart:manage', 'sale:create',
    'payment:take',
    'customer:view'
]);

SELECT grant_permissions('STOCK_CONTROLLER', ARRAY[
    'product:view', 'product:manage',
    'inventory:view', 'inventory:adjust', 'stocktake:manage', 'transfer:manage',
    'purchase:view', 'purchase:receive', 'supplier:manage',
    'report:view:branch'
]);

SELECT grant_permissions('ACCOUNTANT', ARRAY[
    'product:view', 'inventory:view',
    'purchase:view', 'supplier:manage',
    'payment:reconcile',
    'report:view', 'report:view:branch', 'export:data',
    'audit:view'
]);

SELECT grant_permissions('AUDITOR', ARRAY[
    'user:view', 'role:view', 'branch:view', 'audit:view',
    'product:view', 'inventory:view', 'purchase:view', 'customer:view',
    'report:view', 'report:view:branch'
]);

DROP FUNCTION grant_permissions(TEXT, TEXT[]);

-- ---------------------------------------------------------------------------
-- A first branch, so a bootstrapped administrator has somewhere to act.
-- ---------------------------------------------------------------------------
INSERT INTO branches (id, code, name) VALUES (gen_random_uuid(), 'HQ', 'Head Office');
