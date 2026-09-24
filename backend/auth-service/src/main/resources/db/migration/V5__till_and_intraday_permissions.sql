-- Tills and the branch's intraday cash.
--
-- till:manage     name and number a branch's tills, and set how much cash a till may hold before
--                 it must deposit - per branch and per person.
-- cash:intraday   hold the branch's intraday cash: top it up, bank it, and approve handing it to a
--                 till that has run short of change.
--
-- Supervisors and branch managers run the floor, so both get both. SUPER_ADMIN holds '*', which is
-- expanded to every permission (these included) when a token is issued.
INSERT INTO permissions (id, code, category, description) VALUES
    (gen_random_uuid(), 'till:manage',   'sales', 'Name tills and set their cash limits'),
    (gen_random_uuid(), 'cash:intraday', 'sales', 'Hold the branch intraday cash and replenish tills from it')
ON CONFLICT (code) DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('till:manage', 'cash:intraday')
WHERE r.code IN ('SUPERVISOR', 'BRANCH_MANAGER')
ON CONFLICT DO NOTHING;
