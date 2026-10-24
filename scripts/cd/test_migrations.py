#!/usr/bin/env python3
"""Exercise every up migration twice in an isolated, disposable Docker PostgreSQL instance."""
import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
POSTGRES = "postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"


def docker(*args, env=None, input_text=None, check=True):
    result = subprocess.run(["docker", *args], env=env, input=input_text, text=True, encoding="utf-8", capture_output=True)
    if check and result.returncode:
        # DB URL/password can appear in client errors. Keep detailed diagnostics local.
        raise RuntimeError("Docker migration check failed: " + (result.stderr.splitlines()[-1][:200] if "run" not in args else "runner exited unsuccessfully (output redacted)"))
    return result


def verify(image, output=None, previous_image=None):
    prefix = "cd-migrations-" + uuid.uuid4().hex[:10]
    database = prefix + "-pg"
    password = secrets.token_hex(24)
    env = {**os.environ, "POSTGRES_PASSWORD": password}
    network_created = pg_created = False
    reports = []
    try:
        docker("network", "create", "--internal", prefix)
        network_created = True
        docker("run", "-d", "--name", database, "--network", prefix, "--network-alias", "database", "-e", "POSTGRES_PASSWORD", POSTGRES, env=env)
        pg_created = True
        for _ in range(60):
            if docker("exec", database, "pg_isready", "-U", "postgres", check=False).returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError("Disposable PostgreSQL did not become ready")
        components = json.loads((ROOT / "deploy/gitops/catalog.json").read_text(encoding="utf-8"))["components"]
        for name, component in components.items():
            engine = component.get("migration")
            if not engine:
                continue
            db = name.replace("-", "_")
            role = db + "_owner"
            sql = f"CREATE ROLE {role} LOGIN PASSWORD '{password}';\nCREATE DATABASE {db} OWNER {role};\n"
            docker("exec", "-i", database, "psql", "-v", "ON_ERROR_STOP=1", "-U", "postgres", input_text=sql)
            migration_env = {**os.environ, "MIGRATION_SERVICE": name, "FLYWAY_URL": f"jdbc:postgresql://database:5432/{db}",
                             "FLYWAY_USER": role, "FLYWAY_PASSWORD": password,
                             "MIGRATE_DATABASE_URL": f"postgres://{role}:{password}@database:5432/{db}?sslmode=disable"}
            flags = [item for key in ("MIGRATION_SERVICE", "FLYWAY_URL", "FLYWAY_USER", "FLYWAY_PASSWORD", "MIGRATE_DATABASE_URL") for item in ("-e", key)]
            runner = prefix + "-runner"
            runners = ([previous_image] if previous_image else []) + [image, image]
            for runner_image in runners:
                try:
                    docker("run", "--rm", "--name", runner, "--network", prefix, "--memory", "512m", "--cpus", "1", "--read-only", "--tmpfs", "/tmp:rw,size=256m,mode=1777", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", *flags, runner_image, env=migration_env)
                finally:
                    docker("rm", "-f", runner, check=False)
            table = "flyway_schema_history" if engine == "flyway" else "schema_migrations"
            count = docker("exec", database, "psql", "-U", "postgres", "-d", db, "-Atc", "SELECT count(*) FROM public." + table).stdout.strip()
            if not count.isdigit() or int(count) < 1:
                raise RuntimeError("Migration history was not recorded for " + name)
            reports.append({"service": name, "engine": engine, "first_run": "passed", "repeat_run": "passed", "history_rows": int(count),
                            "previous_engine_upgrade": "passed" if previous_image else "not-requested"})
            print(name + ": initial and repeat migration passed", flush=True)
    finally:
        if pg_created:
            docker("rm", "-fv", database, check=False)
        if network_created:
            docker("network", "rm", prefix, check=False)
    if output:
        Path(output).parent.mkdir(parents=True, exist_ok=True)
        Path(output).write_text(json.dumps({"postgres_image": POSTGRES, "results": reports}, indent=2) + "\n", encoding="utf-8")
    return reports


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", required=True)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--previous-image", help="Optional old runner: first populate its history, then upgrade and repeat with the new runner")
    args = parser.parse_args()
    verify(args.image, args.output, args.previous_image)
