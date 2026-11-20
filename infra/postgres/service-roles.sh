#!/bin/sh
# Brings the shared PostgreSQL to infra/postgres/service-roles.txt (one-shot, idempotent; mesh
# ADR-004): for each line, a LOGIN role that is not a superuser, owns its database and every
# object in it, and is the only non-superuser allowed to connect. Runs on every
# `docker compose up` (postgres-init) and as the Job postgres-roles-init on Kubernetes.
#
# Objects created later by the superuser (a schema loaded by hand, `docker exec postgres psql`)
# stay readable and writable through default privileges; the next run makes the role their owner.
# Environment: PGHOST, PGUSER, PGPASSWORD of the superuser; ROLES_FILE; one password variable per
# line of that file.
set -eu

ROLES_FILE="${ROLES_FILE:-/scripts/service-roles.txt}"

until psql -X -q -d postgres -c 'SELECT 1' >/dev/null 2>&1; do
  echo "waiting for PostgreSQL at ${PGHOST}"
  sleep 2
done

grep -v '^[[:space:]]*#' "$ROLES_FILE" | grep -v '^[[:space:]]*$' | while read -r db role password_var; do
  password=$(printenv "$password_var" || true)
  if [ -z "$password" ]; then
    echo "service-roles: $password_var is not set (role $role)" >&2
    exit 1
  fi
  # psql interpolates :'db' only in statements read from stdin, not in -c.
  exists=$(echo "SELECT 1 FROM pg_database WHERE datname = :'db';" | psql -X -At -d postgres -v db="$db")
  if [ "$exists" != "1" ]; then
    echo "service-roles: database $db does not exist, skipping $role"
    continue
  fi

  psql -X -q -v ON_ERROR_STOP=1 -d postgres -v db="$db" -v role="$role" -v password="$password" <<'SQL'
SELECT format('CREATE ROLE %I LOGIN', :'role')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'role')
\gexec
ALTER ROLE :"role" WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'password';
ALTER DATABASE :"db" OWNER TO :"role";
REVOKE CONNECT, TEMPORARY ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, TEMPORARY ON DATABASE :"db" TO :"role";
SQL

  psql -X -q -v ON_ERROR_STOP=1 -d "$db" -v role="$role" <<'SQL'
-- Schemas other than public (public belongs to pg_database_owner, i.e. the role now).
SELECT format('ALTER SCHEMA %I OWNER TO %I', n.nspname, :'role')
FROM pg_namespace n
WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname NOT IN ('information_schema', 'public')
  AND pg_get_userbyid(n.nspowner) <> :'role'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_namespace'::regclass AND d.objid = n.oid AND d.deptype = 'e')
\gexec
-- Tables, partitions, views, materialized views, standalone sequences. Indexes, TOAST and the
-- sequences of serial/identity columns follow their table.
SELECT format('ALTER %s %I.%I OWNER TO %I',
              CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW'
                             WHEN 'S' THEN 'SEQUENCE' WHEN 'f' THEN 'FOREIGN TABLE' ELSE 'TABLE' END,
              n.nspname, c.relname, :'role')
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r', 'p', 'v', 'm', 'S', 'f')
  AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
  AND pg_get_userbyid(c.relowner) <> :'role'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_class'::regclass AND d.objid = c.oid
                    AND (d.deptype = 'e' OR (c.relkind = 'S' AND d.deptype IN ('a', 'i'))))
\gexec
SELECT format('ALTER %s %s OWNER TO %I',
              CASE p.prokind WHEN 'p' THEN 'PROCEDURE' WHEN 'a' THEN 'AGGREGATE' ELSE 'FUNCTION' END,
              p.oid::regprocedure, :'role')
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
  AND pg_get_userbyid(p.proowner) <> :'role'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_proc'::regclass AND d.objid = p.oid AND d.deptype = 'e')
\gexec
-- Enums, domains, ranges and standalone composite types.
SELECT format('ALTER %s %s OWNER TO %I', CASE t.typtype WHEN 'd' THEN 'DOMAIN' ELSE 'TYPE' END,
              t.oid::regtype, :'role')
FROM pg_type t
JOIN pg_namespace n ON n.oid = t.typnamespace
LEFT JOIN pg_class c ON c.oid = t.typrelid
WHERE (t.typtype IN ('e', 'd', 'r') OR (t.typtype = 'c' AND c.relkind = 'c'))
  AND n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
  AND pg_get_userbyid(t.typowner) <> :'role'
  AND NOT EXISTS (SELECT 1 FROM pg_depend d
                  WHERE d.classid = 'pg_type'::regclass AND d.objid = t.oid AND d.deptype = 'e')
\gexec
-- What the superuser creates by hand until the next run stays usable by the service.
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA %I GRANT ALL ON %s TO %I',
              n.nspname, kind, :'role')
FROM pg_namespace n
CROSS JOIN (VALUES ('TABLES'), ('SEQUENCES'), ('FUNCTIONS')) AS k(kind)
WHERE n.nspname NOT LIKE 'pg\_%' AND n.nspname <> 'information_schema'
\gexec
SQL
  echo "service-roles: $role owns $db"
done
