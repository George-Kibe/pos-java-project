#!/usr/bin/env bash
# Creates one schema and one login role per service, with grants scoped to that
# schema only. A cross-schema query therefore fails at the database, not at review.
# Runs once, on first initialisation of an empty data directory.
set -euo pipefail

SERVICES="auth catalog inventory purchasing sales payment customer notification reporting"
DB="${POSTGRES_DB:-pos}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB" <<-SQL
	-- Nobody gets anything by default.
	REVOKE ALL ON SCHEMA public FROM PUBLIC;
	REVOKE ALL ON DATABASE "$DB" FROM PUBLIC;
	CREATE EXTENSION IF NOT EXISTS pgcrypto;
	-- Needed by catalog's exclusion constraint on tax rate periods. Installed here because a
	-- per-service role has rights on its own schema only and cannot create an extension.
	CREATE EXTENSION IF NOT EXISTS btree_gist;
SQL

for svc in $SERVICES; do
    upper=$(echo "$svc" | tr '[:lower:]' '[:upper:]')
    user_var="${upper}_DB_USER"
    pass_var="${upper}_DB_PASSWORD"
    user="${!user_var:-${svc}_user}"
    pass="${!pass_var:-}"

    if [ -z "$pass" ]; then
        echo "FATAL: ${pass_var} is not set. Refusing to create role '${user}' without a password." >&2
        exit 1
    fi

    echo "  -> schema '${svc}' owned by role '${user}'"
    psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$DB" <<-SQL
		DO \$\$
		BEGIN
		    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '${user}') THEN
		        CREATE ROLE "${user}" LOGIN PASSWORD '${pass}';
		    ELSE
		        ALTER ROLE "${user}" WITH LOGIN PASSWORD '${pass}';
		    END IF;
		END
		\$\$;

		CREATE SCHEMA IF NOT EXISTS "${svc}" AUTHORIZATION "${user}";

		GRANT CONNECT ON DATABASE "$DB" TO "${user}";
		GRANT USAGE, CREATE ON SCHEMA "${svc}" TO "${user}";

		-- Flyway and the app own everything they create in their own schema.
		ALTER DEFAULT PRIVILEGES FOR ROLE "${user}" IN SCHEMA "${svc}"
		    GRANT ALL ON TABLES TO "${user}";
		ALTER DEFAULT PRIVILEGES FOR ROLE "${user}" IN SCHEMA "${svc}"
		    GRANT ALL ON SEQUENCES TO "${user}";

		-- Resolve unqualified names inside the owned schema only.
		ALTER ROLE "${user}" SET search_path = "${svc}";

		-- Explicitly deny the shared public schema.
		REVOKE ALL ON SCHEMA public FROM "${user}";
	SQL
done

echo "Schema bootstrap complete: ${SERVICES}"
