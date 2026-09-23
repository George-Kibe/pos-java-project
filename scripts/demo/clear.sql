-- Remove the demo data that scripts/demo/seed.py created, and nothing else.
--
-- Development only: run through `make demo-clear` against the local stack. The application never
-- deletes transactional records (it reverses them); this is a reset button for a demo database.
--
-- How it finds the demo data:
--   1. The roots are marked: branch, product, supplier, role and category codes starting DEMO,
--      and emails ending @demo.pos.local.
--   2. Across every service schema, a row goes if any of its uuid columns holds a demo id, or its
--      text (event payloads, outbox rows, stored responses, audit details) carries a demo marker or
--      mentions a demo id. The ids - and event ids - of deleted rows join the set, so a sale's lines,
--      its payments, its loyalty entries, its report rows and the consumers' records of its events
--      follow.
--   3. That repeats until a pass deletes nothing.
-- A row that only points at shared reference data (a seeded category, a tax class) holds no demo id,
-- so the reference data stays; so do the administrator's own sign-ins. Foreign-key triggers are
-- suspended for the transaction so the order of deletion does not matter: every dependent row is
-- removed by the same rules.

\set ON_ERROR_STOP on

BEGIN;

SET LOCAL session_replication_role = replica;

CREATE TEMP TABLE demo_ids (id uuid PRIMARY KEY) ON COMMIT DROP;
CREATE TEMP TABLE demo_roots (id uuid PRIMARY KEY) ON COMMIT DROP;

INSERT INTO demo_roots SELECT id FROM auth.branches WHERE code LIKE 'DEMO%' ON CONFLICT DO NOTHING;
INSERT INTO demo_roots SELECT id FROM auth.users WHERE email LIKE '%@demo.pos.local' ON CONFLICT DO NOTHING;
INSERT INTO demo_roots SELECT id FROM auth.roles WHERE code LIKE 'DEMO%' ON CONFLICT DO NOTHING;
INSERT INTO demo_roots SELECT id FROM catalog.products WHERE sku LIKE 'DEMO%' ON CONFLICT DO NOTHING;
INSERT INTO demo_roots SELECT id FROM catalog.categories WHERE code LIKE 'DEMO%' ON CONFLICT DO NOTHING;
INSERT INTO demo_roots SELECT id FROM purchasing.suppliers WHERE code LIKE 'DEMO%' ON CONFLICT DO NOTHING;
INSERT INTO demo_roots SELECT id FROM customer.customers WHERE email LIKE '%@demo.pos.local' ON CONFLICT DO NOTHING;
INSERT INTO demo_ids SELECT id FROM demo_roots;

DO $$
DECLARE
    uuid_re constant text := '([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})';
    roots_pattern text;
    tbl record;
    col record;
    uuid_cond text;
    text_cond text;
    cond text;
    has_id boolean;
    has_event_id boolean;
    harvest text;
    affected bigint;
    pass_total bigint;
    grand_total bigint := 0;
    pass int := 0;
BEGIN
    SELECT string_agg(id::text, '|') INTO roots_pattern FROM demo_roots;
    IF roots_pattern IS NULL THEN
        RAISE NOTICE 'No demo data found - nothing to clear.';
        RETURN;
    END IF;
    -- Text that names a demo root, or carries a demo marker.
    roots_pattern := '(' || roots_pattern || '|@demo\.pos\.local|DEMO-)';

    LOOP
        pass := pass + 1;
        pass_total := 0;
        FOR tbl IN
            SELECT n.nspname AS schema_name, c.relname AS table_name
            FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind IN ('r', 'p')
              AND n.nspname IN ('auth', 'catalog', 'inventory', 'purchasing', 'sales', 'payment',
                                'customer', 'notification', 'reporting')
              AND c.relname <> 'flyway_schema_history'
            ORDER BY 1, 2
        LOOP
            uuid_cond := NULL;
            text_cond := NULL;
            has_id := false;
            has_event_id := false;
            FOR col IN
                SELECT a.attname AS name, format_type(a.atttypid, a.atttypmod) AS type
                FROM pg_attribute a
                WHERE a.attrelid = format('%I.%I', tbl.schema_name, tbl.table_name)::regclass
                  AND a.attnum > 0 AND NOT a.attisdropped
            LOOP
                IF col.type = 'uuid' THEN
                    uuid_cond := coalesce(uuid_cond || ' OR ', '')
                        || format('%I IN (SELECT id FROM demo_ids)', col.name);
                    IF col.name = 'id' THEN has_id := true; END IF;
                ELSIF col.type IN ('text', 'jsonb', 'json') OR col.type LIKE 'character varying%' THEN
                    -- A marker, or any id from the set mentioned inside the text.
                    text_cond := coalesce(text_cond || ' OR ', '')
                        || format('(%1$I::text ~ %2$L OR EXISTS (SELECT 1 FROM regexp_matches(%1$I::text, %3$L, %4$L) m'
                                  || ' WHERE m[1]::uuid IN (SELECT id FROM demo_ids)))',
                                  col.name, roots_pattern, uuid_re, 'g');
                END IF;
                IF col.name = 'event_id' THEN has_event_id := true; END IF;
            END LOOP;

            cond := concat_ws(' OR ', uuid_cond, text_cond);
            CONTINUE WHEN cond IS NULL OR cond = '';

            IF has_id OR has_event_id THEN
                -- The deleted rows' ids and event ids join the set, so whatever points at them -
                -- child rows, and the consumers' records of the events - goes in the next pass.
                harvest := concat_ws(' UNION ',
                    CASE WHEN has_id THEN 'SELECT id::uuid FROM gone' END,
                    CASE WHEN has_event_id THEN
                        format('SELECT event_id::uuid FROM gone WHERE event_id::text ~ %L', '^' || uuid_re || '$')
                    END);
                EXECUTE format(
                    'WITH gone AS (DELETE FROM %I.%I WHERE %s RETURNING *),
                          kept AS (INSERT INTO demo_ids %s ON CONFLICT DO NOTHING)
                     SELECT count(*) FROM gone',
                    tbl.schema_name, tbl.table_name, cond, harvest)
                    INTO affected;
            ELSE
                EXECUTE format('DELETE FROM %I.%I WHERE %s', tbl.schema_name, tbl.table_name, cond);
                GET DIAGNOSTICS affected = ROW_COUNT;
            END IF;
            pass_total := pass_total + affected;
        END LOOP;
        grand_total := grand_total + pass_total;
        EXIT WHEN pass_total = 0 OR pass >= 25;
    END LOOP;
    RAISE NOTICE 'Demo data cleared: % rows in % passes.', grand_total, pass;
END $$;

COMMIT;
