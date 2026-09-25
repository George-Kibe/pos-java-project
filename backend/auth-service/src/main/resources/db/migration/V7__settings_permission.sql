-- settings:manage: the wording of the emails the system sends (subject, opening and closing text;
-- the layout stays in code). Granted to no role: the administrator holds it through '*'.
INSERT INTO permissions (id, code, category, description) VALUES
    (gen_random_uuid(), 'settings:manage', 'settings', 'Change the wording of the emails the system sends')
ON CONFLICT (code) DO NOTHING;
