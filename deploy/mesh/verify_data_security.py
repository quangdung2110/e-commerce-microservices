#!/usr/bin/env python3
"""Verify the three protected data endpoints on the isolated local kind cluster."""

import argparse
import base64
import http.client
import json
import os
import socket
import ssl
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib.parse import urlsplit
from uuid import uuid4

PORTS = {"checkout-redis": (6379, 6379), "elasticsearch": (9200, 9200), "kafka-connect": (8083, 8443)}
CA = "/run/data-trust/ca.crt"


def reaches_listener(host, port, tls=True):
    try:
        with socket.create_connection((host, port), timeout=3) as connection:
            # Ambient can accept the local TCP connection before the remote policy blocks it.
            if not tls:
                connection.sendall(b"HEAD / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                return bool(connection.recv(1))
            context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
            context.check_hostname = False
            context.verify_mode = ssl.CERT_NONE
            with context.wrap_socket(connection, server_hostname=host):
                return True
    except (TimeoutError, ConnectionRefusedError, ConnectionResetError, BrokenPipeError, ssl.SSLEOFError):
        return False
    except ssl.SSLError:
        # A TLS alert (for example a missing client certificate) means the listener was reached.
        return True


def https(service, path, *, method="GET", body=None, password=None, client_certificate=False):
    context = ssl.create_default_context(cafile=CA)
    if client_certificate:
        context.load_cert_chain("/run/data-trust/tls.crt", "/run/data-trust/tls.key")
    headers = {"Content-Type": "application/json"}
    if password is not None:
        value = base64.b64encode(f"search-service:{password}".encode()).decode()
        headers["Authorization"] = f"Basic {value}"
    client = http.client.HTTPSConnection(f"{service}.data.svc.cluster.local", PORTS[service][0],
                                         context=context, timeout=30)
    try:
        client.request(method, path, None if body is None else json.dumps(body), headers)
        response = client.getresponse()
        data = response.read()
        return response.status, json.loads(data) if data and response.getheader("Content-Type", "").startswith(
            "application/json") else None
    finally:
        client.close()


def redis_check(check, scripts):
    host = "checkout-redis.data.svc.cluster.local"
    context = ssl.create_default_context(cafile=CA)
    with socket.create_connection((host, 6379), timeout=5) as connection:
        with context.wrap_socket(connection, server_hostname=host) as conn, conn.makefile("rb") as reply:
            def response():
                line = reply.readline(4096).rstrip()
                if line.startswith(b"$") and line != b"$-1":
                    length = int(line[1:])
                    return reply.read(length + 2)[:-2]
                if line.startswith(b"*"):
                    return [response() for _ in range(int(line[1:]))]
                return line

            def command(*parts):
                words = [part.encode() for part in parts]
                conn.sendall(f"*{len(words)}\r\n".encode() + b"".join(
                    f"${len(word)}\r\n".encode() + word + b"\r\n" for word in words))
                return response()

            check("Redis without AUTH", command("PING").startswith(b"-NOAUTH"))
            check("Redis with wrong credential", command("AUTH", "checkout", "invalid").startswith(b"-WRONGPASS"))
            password = urlsplit(os.environ["CHECKOUT_REDIS_URL"]).password
            check("Redis with Checkout credential", command("AUTH", "checkout", password) == b"+OK")
            check("Checkout ACL blocks keys outside checkout", command("GET", "p1:outside").startswith(b"-NOPERM"))

            shas = {name: command("SCRIPT", "LOAD", script).decode() for name, script in scripts.items()}
            check("Checkout script readiness", command("SCRIPT", "EXISTS", *shas.values()) == [b":1"] * len(shas))

            def evalsha(name, keys, args):
                return command("EVALSHA", shas[name], str(len(keys)), *keys, *args)

            fixture = "p1-" + uuid4().hex
            now = int(time.time() * 1000)
            session, active, idem, payload, result = [f"checkout:{kind}:{fixture}" for kind in
                                                      ("session", "active", "idem", "submit-payload", "result")]
            preview = ["checkout:session:", "checkout:submit-payload:", str(now), fixture, "fingerprint",
                       "", fixture, "30000", "30000", str(now + 30000), "1", "fixture-snapshot", "fixture-preview"]
            check("Checkout Preview Lua", evalsha("create_preview", [active, session, idem], preview)[0] == b"CREATED")
            check("Checkout Preview Lua replays", evalsha("create_preview", [active, session, idem], preview)
                  == [b"REPLAY", b"fixture-preview"])
            claim = [str(now), fixture, "first-attempt", "fixture-payload", "30000", fixture]
            check("Checkout Submit Lua claims", evalsha("claim", [session, payload, active], claim) == [b"CLAIMED"])
            claim[2] = "duplicate-attempt"
            duplicate = evalsha("claim", [session, payload, active], claim)
            check("Checkout duplicate Submit Lua keeps first attempt",
                  isinstance(duplicate, list) and duplicate[0] == b"SUBMITTING" and duplicate[3] == b"first-attempt")
            finish = [fixture, str(now), "SUCCEEDED", "fixture-order", "", "30000"]
            check("Checkout finalizes one result", evalsha("finalize", [session, result], finish) == [b"FINALIZED"])
            finish[3] = "duplicate-order"
            check("Checkout finalization preserves original result",
                  evalsha("finalize", [session, result], finish) == [b"ALREADY", b"SUCCEEDED"]
                  and command("HGET", result, "order_id") == b"fixture-order")


def search_check(check):
    service = "elasticsearch"
    health = "/_cluster/health?filter_path=status"
    check("Elasticsearch without credentials", https(service, health)[0] == 401)
    check("Elasticsearch with wrong credentials", https(service, health, password="invalid")[0] == 401)
    password = os.environ["ES_PASSWORD"]
    check("Elasticsearch with Search credential", https(service, health, password=password)[0] == 200)
    check("Search role cannot manage security", https(service, "/_security/user", password=password)[0] == 403)
    prefix = f"products_vp1_{uuid4().hex}"
    source, dest, alias = [prefix + "_" + name for name in ("source", "dest", "alias")]
    try:
        for index in (source, dest):
            status, _ = https(service, f"/{index}", method="PUT", body={}, password=password)
            check(f"Search role can create {index.rsplit('_', 1)[-1]} index", status == 200)
        status, _ = https(service, f"/{source}/_doc/test?refresh=true", method="PUT", body={"p1": True},
                          password=password)
        check("Search role can index", status == 201)
        status, result = https(service, "/_reindex?refresh=true", method="POST",
                               body={"source": {"index": source}, "dest": {"index": dest}}, password=password)
        check("Search role can reindex", status == 200 and result["created"] == 1 and not result["failures"])
        status, _ = https(service, "/_aliases", method="POST",
                          body={"actions": [{"add": {"index": source, "alias": alias}}]}, password=password)
        check("Search role can add aliases", status == 200)
        status, _ = https(service, "/_aliases", method="POST", body={"actions": [
            {"remove": {"index": "*", "alias": alias, "must_exist": False}},
            {"add": {"index": dest, "alias": alias}},
        ]}, password=password)
        check("Search role can switch aliases as in IndexAdmin", status == 200)
        status, result = https(service, f"/{alias}/_count", password=password)
        check("Search role can read reindexed data", status == 200 and result["count"] == 1)
    finally:
        status, _ = https(service, f"/{source},{dest}?ignore_unavailable=true", method="DELETE", password=password)
        check("Synthetic Elasticsearch indices cleaned up", status == 200)


def run_probe(caller, addresses, scripts):
    checks = {}

    def check(label, passed):
        checks[label] = bool(passed)

    if caller in ("order", "outside", "outside-cross-node", "other-data"):
        targets = [(service, f"{service}.data.svc.cluster.local", service_port, True)
                   for service, (service_port, _) in PORTS.items()]
        targets += [(f"{service} pod IP", addresses[service], pod_port, True) for service, (_, pod_port) in PORTS.items()]
        targets.append(("Connect raw pod IP", addresses["kafka-connect"], 8083, False))
        with ThreadPoolExecutor() as pool:
            results = pool.map(lambda target: reaches_listener(*target[1:]), targets)
            for (label, *_), result in zip(targets, results):
                check(f"{label} blocked", not result)
    elif caller == "checkout":
        redis_check(check, scripts)
    elif caller == "search":
        search_check(check)
    elif caller == "connect":
        service = "kafka-connect"
        check("Kafka Connect direct REST blocked", not reaches_listener(addresses[service], 8083, tls=False))
        try:
            status, _ = https(service, "/connectors")
            check("Kafka Connect without client certificate", status in (400, 401, 403))
        except ssl.SSLError:
            check("Kafka Connect without client certificate", True)
        status, result = https(service, "/connectors?expand=status", client_certificate=True)
        check("Kafka Connect with registrar certificate", status == 200)
        check("All six connectors and tasks running", status == 200 and len(result) == 6 and all(
            item["status"]["connector"]["state"] == "RUNNING" and item["status"]["tasks"]
            and all(task["state"] == "RUNNING" for task in item["status"]["tasks"]) for item in result.values()))
    print(json.dumps(checks))


def main():
    from local import ROOT, apply, guard, kube

    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    guard()
    namespace = json.loads(kube("get", "namespace", "ecommerce", "-o", "json", capture=True))
    labels = namespace["metadata"]["labels"]
    if labels.get("istio.io/dataplane-mode") != "ambient" or labels.get("istio.io/use-waypoint") != "ecommerce-waypoint":
        raise RuntimeError("The ecommerce namespace must be enrolled in the local Istio mesh.")
    addresses = {service: kube("-n", "data", "get", "pod", "-l", f"app={service}",
                               "-o", "jsonpath={.items[0].status.podIP}", capture=True).strip()
                 for service in PORTS}
    pod_code = Path(__file__).read_text(encoding="utf-8")
    lua = ROOT / "java/services/checkout-service/src/main/resources/lua"
    scripts = {name: (lua / f"{name}.lua").read_text(encoding="utf-8")
               for name in ("create_preview", "claim", "finalize")}
    cases = (
        ("outside", "default", {}, None, None),
        ("outside-cross-node", "default", {}, None, None),
        ("other-data", "data", {}, None, None),
        ("checkout", "ecommerce", {"app.kubernetes.io/name": "checkout-service"}, "data-server-trust",
         ("CHECKOUT_REDIS_URL", "checkout-redis-client", "url")),
        ("search", "ecommerce", {"app.kubernetes.io/name": "search-service"}, "data-server-trust",
         ("ES_PASSWORD", "elasticsearch-client", "password")),
        ("connect", "data", {"batch.kubernetes.io/job-name": "kafka-connect-init"}, "kafka-connect-rest-client", None),
    )
    suffix = uuid4().hex[:8]
    probes = []
    for caller, namespace, labels, trust, credential in cases:
        container = {"name": "probe", "image": "ecommerce-mesh-probe:local", "imagePullPolicy": "Never",
                     "command": ["sleep", "300"]}
        spec = {"restartPolicy": "Never", "automountServiceAccountToken": False,
                "nodeSelector": {"kubernetes.io/hostname": "ecommerce-mesh-worker"},
                # Pods with allowed labels must never become endpoints of an application Service.
                "readinessGates": [{"conditionType": "security.ecommerce/traffic-disabled"}],
                "containers": [container]}
        if caller == "outside-cross-node":
            spec["nodeSelector"]["kubernetes.io/hostname"] = "ecommerce-mesh-control-plane"
            spec["tolerations"] = [{"key": "node-role.kubernetes.io/control-plane", "effect": "NoSchedule"}]
        if trust:
            spec["volumes"] = [{"name": "trust", "secret": {"secretName": trust}}]
            container["volumeMounts"] = [{"name": "trust", "mountPath": "/run/data-trust", "readOnly": True}]
        if credential:
            env, secret, key = credential
            container["env"] = [{"name": env, "valueFrom": {"secretKeyRef": {"name": secret, "key": key}}}]
        probes.append((caller, {"apiVersion": "v1", "kind": "Pod", "spec": spec,
                                "metadata": {"name": f"p1-data-{caller}-{suffix}",
                                             "namespace": namespace, "labels": labels}}))

    def collect(caller, namespace, pod_name):
        result = kube("-n", namespace, "exec", "-i", pod_name, "--", "python", "-", "--probe", caller,
                      json.dumps(addresses), json.dumps(scripts if caller == "checkout" else {}),
                      capture=True, input=pod_code)
        return json.loads(result)

    try:
        apply([pod for _, pod in probes])
        with ThreadPoolExecutor() as pool:
            def wait_and_collect(caller, meta):
                namespace, pod_name = meta["namespace"], meta["name"]
                kube("-n", namespace, "wait", "--for=jsonpath={.status.phase}=Running",
                     f"pod/{pod_name}", "--timeout=60s", capture=True)
                return collect(caller, namespace, pod_name)

            futures = {caller: pool.submit(wait_and_collect, caller, pod["metadata"]) for caller, pod in probes}
            futures["order"] = pool.submit(collect, "order", "ecommerce", "mesh-probe-order")
            results = {caller: future.result() for caller, future in futures.items()}
        args.out.parent.mkdir(parents=True, exist_ok=True)
        args.out.write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
        passed = sum(sum(checks.values()) for checks in results.values())
        total = sum(map(len, results.values()))
        print(f"{passed}/{total} checks passed")
        return 0 if passed == total else 1
    finally:
        for _, pod in probes:
            meta = pod["metadata"]
            kube("-n", meta["namespace"], "delete", "pod", meta["name"], "--ignore-not-found",
                 "--grace-period=1", "--wait=false")


if __name__ == "__main__":
    if sys.argv[1:2] == ["--probe"]:
        run_probe(sys.argv[2], json.loads(sys.argv[3]), json.loads(sys.argv[4]))
    else:
        sys.exit(main())
