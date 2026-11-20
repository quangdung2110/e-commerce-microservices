#!/usr/bin/env python3
"""Rehearse PostgreSQL backup/loss/restore using owned Docker fixtures only. No external DSN input."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "cd"))
from common import now, read_json, write_json

POSTGRES = "postgres:16-alpine@sha256:cf78e76683b9ca8c5733cbbdce6c9262b45b6767934dd0a95e671f9a0fc20685"


def docker(*args, payload=None, env=None, check=True):
    data = payload.encode() if isinstance(payload, str) else payload
    result = subprocess.run(["docker", *args], input=data, env=env, capture_output=True, timeout=120)
    if check and result.returncode:
        raise RuntimeError("Recovery fixture command failed; output redacted")
    return result


def verify_backup(payload, checksum):
    if not payload.startswith(b"PGDMP") or hashlib.sha256(payload).hexdigest() != checksum:
        raise ValueError("Backup is corrupt or does not match its checksum")


def drill(policy):
    if policy.get("mode") != "isolated-fixture":
        raise ValueError("This tool only supports its isolated fixture; no existing database can be targeted")
    for key, maximum in (("rpo_seconds", 300), ("rto_seconds", 1800)):
        if type(policy.get(key)) is not int or not 1 <= policy[key] <= maximum:
            raise ValueError("Invalid recovery objective")
    prefix = "operations-recovery-" + uuid.uuid4().hex[:10]
    container = prefix + "-pg"
    network_created = container_created = False
    password = secrets.token_hex(24)
    env = {**os.environ, "POSTGRES_PASSWORD": password}
    try:
        docker("network", "create", "--internal", prefix)
        network_created = True
        docker("run", "-d", "--name", container, "--network", prefix, "--memory", "512m", "--cpus", "1", "-e", "POSTGRES_PASSWORD", POSTGRES, env=env)
        container_created = True
        # The image initializes with a socket-only server before the final server starts.
        for _ in range(120):
            if docker("exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "postgres", check=False).returncode == 0:
                break
            time.sleep(0.5)
        else:
            raise RuntimeError("Recovery fixture did not become ready")

        def sql(database, statement, role="postgres"):
            return docker("exec", "-i", container, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-U", role, "-d", database, "-At", payload=statement).stdout.decode().strip()

        sql("postgres", "CREATE ROLE fixture_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;\nCREATE ROLE restore_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE;\nCREATE DATABASE fixture_source OWNER fixture_owner;")
        sql("fixture_source", """
CREATE TABLE checkpoint (id integer PRIMARY KEY, recorded_at timestamptz NOT NULL);
INSERT INTO checkpoint VALUES (1, clock_timestamp());
CREATE TABLE orders (id integer PRIMARY KEY, status text NOT NULL CHECK (status IN ('PAID','CANCELLED','REFUNDED')), amount integer NOT NULL CHECK (amount >= 0));
CREATE TABLE payments (id integer PRIMARY KEY REFERENCES orders(id), amount integer NOT NULL CHECK (amount >= 0));
INSERT INTO orders VALUES (1,'PAID',100),(2,'CANCELLED',0),(3,'REFUNDED',50);
INSERT INTO payments VALUES (1,100),(3,50);
""", "fixture_owner")
        fingerprint_query = "SELECT md5(string_agg(id::text || ':' || status || ':' || amount::text, ',' ORDER BY id)) FROM orders;"
        expected = sql("fixture_source", fingerprint_query, "fixture_owner")
        checkpoint = sql("fixture_source", "SELECT extract(epoch FROM recorded_at) FROM checkpoint WHERE id=1;", "fixture_owner")
        backup = docker("exec", container, "pg_dump", "-U", "fixture_owner", "-d", "fixture_source", "--format=custom", "--no-owner", "--no-acl").stdout
        checksum = hashlib.sha256(backup).hexdigest()
        verify_backup(backup, checksum)
        # This acknowledged write occurs after the backup and must be absent after restoration.
        sql("fixture_source", "INSERT INTO orders VALUES (999,'PAID',1);", "fixture_owner")
        failed_at = time.time()
        start = time.monotonic()
        sql("postgres", "DROP DATABASE fixture_source;\nCREATE DATABASE fixture_restored OWNER restore_owner;")
        docker("exec", "-i", container, "pg_restore", "-U", "restore_owner", "-d", "fixture_restored", "--no-owner", "--no-privileges", "--single-transaction", "--exit-on-error", payload=backup)
        restored = sql("fixture_restored", fingerprint_query, "restore_owner")
        orphan_count = int(sql("fixture_restored", "SELECT count(*) FROM payments p LEFT JOIN orders o USING(id) WHERE o.id IS NULL;", "restore_owner"))
        lost_marker = int(sql("fixture_restored", "SELECT count(*) FROM orders WHERE id=999;", "restore_owner"))
        superuser = sql("fixture_restored", "SELECT rolsuper FROM pg_roles WHERE rolname=current_user;", "restore_owner")
        restored_checkpoint = sql("fixture_restored", "SELECT extract(epoch FROM recorded_at) FROM checkpoint WHERE id=1;", "restore_owner")
        rto = time.monotonic() - start
        rpo = failed_at - float(restored_checkpoint)
        correct = restored == expected and orphan_count == lost_marker == 0 and superuser == "f" and restored_checkpoint == checkpoint
        passed = correct and 0 <= rpo <= policy["rpo_seconds"] and rto <= policy["rto_seconds"]
        return {"schema_version": 1, "phase": "recovery", "mode": "isolated-fixture", "result": "passed" if passed else "failed",
                "evaluated_at": now(), "postgres_image": POSTGRES, "backup_sha256": checksum, "backup_bytes": len(backup),
                "rpo_seconds": round(rpo, 3), "rto_seconds": round(rto, 3), "objectives": policy,
                "fingerprint_matches": restored == expected, "foreign_keys_consistent": orphan_count == 0,
                "post_backup_write_lost_as_expected": lost_marker == 0, "restore_role_is_superuser": superuser != "f",
                "production_recovery_verified": False,
                "coverage": "PostgreSQL custom-format fixture only; excludes PITR, offsite/encryption/secret/Temporal/Kafka/object-storage recovery"}
    finally:
        if container_created:
            docker("rm", "-fv", container, check=False)
        if network_created:
            docker("network", "rm", prefix, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--policy", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = drill(read_json(args.policy)["recovery"])
        write_json(args.output, report)
        print("Isolated PostgreSQL recovery: " + report["result"])
        sys.exit(0 if report["result"] == "passed" else 1)
    except Exception as error:
        print("Recovery drill failed: " + type(error).__name__ + "; output redacted", file=sys.stderr)
        sys.exit(1)
