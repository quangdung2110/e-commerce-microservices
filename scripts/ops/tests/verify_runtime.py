#!/usr/bin/env python3
"""Exercise the real k6 runner against owned 200/503 fixtures, without accessing a project stack."""
import argparse
import json
import os
from pathlib import Path
import sys
import time
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from performance import run_k6, evaluate
from recovery import docker
from common import write_json

PYTHON = "python:3.12-alpine@sha256:b658b05ce9c4eb97ec6fc19424818a1f31993659c2b505a532f470e83807815d"
SERVER = """
import json, os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        data=json.dumps({'product_id':'fixture-only','slug':'fixture'}).encode()
        self.send_response(int(os.environ['FIXTURE_STATUS']))
        self.send_header('Content-Type','application/json')
        self.send_header('Content-Length',str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def log_message(self,*args): pass
ThreadingHTTPServer(('0.0.0.0',8080),Handler).serve_forever()
"""


def verify():
    network = "operations-read-fixture-" + uuid.uuid4().hex[:10]
    server = network + "-http"
    created = False
    results = []
    policy = {"fixture_slug": "fixture", "requests_per_second": 2, "duration_seconds": 10, "max_vus": 5,
              "maximum_p95_ms": 1000, "maximum_error_rate": 0.01, "maximum_p95_regression_fraction": 0.2,
              "baseline": {"p95_ms": 1000}}
    try:
        docker("network", "create", "--internal", network)
        created = True
        for status in (200, 503):
            try:
                docker("run", "-d", "--name", server, "--network", network, "--network-alias", "fixture", "--read-only", "--tmpfs", "/tmp:rw,size=32m,mode=1777", "--user", "10001:10001", "--cap-drop", "ALL", "--security-opt", "no-new-privileges", "-e", "FIXTURE_STATUS=" + str(status), PYTHON, "python", "-c", SERVER)
                for _ in range(30):
                    probe = docker("exec", server, "python", "-c", "import socket; socket.create_connection(('127.0.0.1',8080),2).close()", check=False)
                    if probe.returncode == 0:
                        break
                    time.sleep(0.5)
                else:
                    raise RuntimeError("Read fixture did not become ready")
                observations, code = run_k6("http://fixture:8080", policy, network=network, fixture=True)
                outcome, limits = evaluate(policy, observations)
                if status == 200 and (outcome != "passed" or code != 0):
                    raise RuntimeError("Healthy fixture did not pass the real k6 runner")
                if status == 503 and (outcome != "failed" or code == 0):
                    raise RuntimeError("Failed fixture was incorrectly accepted")
                results.append({"http_status": status, "exit_code": code, "decision": outcome, "observations": observations})
            finally:
                docker("rm", "-f", server, check=False)
    finally:
        if created:
            docker("network", "rm", network, check=False)
    return {"schema_version": 1, "mode": "isolated-http-fixture", "production_performance_verified": False, "results": results}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    write_json(args.output, verify())
    print("Real k6 runner accepted HTTP 200 and rejected HTTP 503 fixtures")
