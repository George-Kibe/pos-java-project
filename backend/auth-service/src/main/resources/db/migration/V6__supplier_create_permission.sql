-- Adding a supplier is the administrator's alone. Everyone who deals with suppliers keeps
-- supplier:manage - editing one, putting them on hold, their price list - but a new supplier, a
-- new place money can be sent, is only ever created with supplier:create.
--
-- No role is granted it. SUPER_ADMIN holds '*', which is expanded to every permission (this one
-- included) when a token is issued; authorization stays on a permission, never a role name.
INSERT INTO permissions (id, code, category, description) VALUES
    (gen_random_uuid(), 'supplier:create', 'purchasing', 'Add a new supplier (administrators only)')
ON CONFLICT (code) DO NOTHING;
