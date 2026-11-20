#!/usr/bin/env python3
"""Provision per-cluster development credentials for the protected data endpoints."""

import argparse
import base64
import hashlib
import json
import os
import secrets
import shutil
import subprocess
from pathlib import Path
from tempfile import TemporaryDirectory

ROOT = Path(__file__).resolve().parents[2]
SECRET_NAMES = {
    "data": ("checkout-redis-server", "elasticsearch-server",
             "kafka-connect-rest-server", "kafka-connect-rest-client"),
    "ecommerce": ("checkout-redis-client", "elasticsearch-client", "data-server-trust"),
    "observability": ("checkout-redis-exporter", "data-server-trust"),
}


def b64(value):
    return base64.b64encode(value).decode("ascii")


def es_password(password):
    salt = secrets.token_bytes(32)
    rounds = 50000
    digest = hashlib.pbkdf2_hmac("sha512", password.encode(), salt, rounds, dklen=32)
    return f"{{PBKDF2}}{rounds}${b64(salt)}${b64(digest)}"


def secret(namespace, name, **values):
    return {
        "apiVersion": "v1",
        "kind": "Secret",
        "metadata": {"namespace": namespace, "name": name},
        "immutable": True,
        "data": {key: b64(value.encode() if isinstance(value, str) else value)
                 for key, value in values.items()},
    }


def generate(directory):
    openssl = shutil.which("openssl") or os.environ.get("OPENSSL")
    if not openssl and os.name == "nt":
        candidate = Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/usr/bin/openssl.exe"
        if candidate.is_file():
            openssl = candidate
    if not openssl:
        raise RuntimeError("OpenSSL with pkcs12 -jdktrust support is required.")

    def ssl(*args):
        subprocess.run([str(openssl), *args], cwd=directory, check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)

    def read(name):
        return (directory / name).read_bytes()

    def ca(name):
        ssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "365",
            "-subj", f"/CN={name}", "-keyout", f"{name}.key", "-out", f"{name}.crt",
            "-addext", "basicConstraints=critical,CA:TRUE,pathlen:0",
            "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        return read(f"{name}.crt")

    def certificate(name, issuer, *, client=False):
        ext = ["basicConstraints=critical,CA:FALSE", "keyUsage=critical,digitalSignature,keyEncipherment",
               "extendedKeyUsage=clientAuth" if client else "extendedKeyUsage=serverAuth"]
        if not client:
            ext.append(f"subjectAltName=DNS:{name},DNS:{name}.data,DNS:{name}.data.svc,"
                       f"DNS:{name}.data.svc.cluster.local,DNS:localhost,IP:127.0.0.1")
        (directory / "ext.cnf").write_text("\n".join(ext) + "\n", encoding="ascii")
        ssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", f"/CN={name}",
            "-keyout", f"{name}.key", "-out", f"{name}.csr")
        ssl("x509", "-req", "-in", f"{name}.csr", "-CA", f"{issuer}.crt", "-CAkey", f"{issuer}.key",
            "-set_serial", f"0x{secrets.token_hex(16)}", "-sha256", "-days", "365",
            "-extfile", "ext.cnf", "-out", f"{name}.crt")
        return {"tls.crt": read(f"{name}.crt"), "tls.key": read(f"{name}.key")}

    server_ca = ca("ecommerce-data-ca")
    client_ca = ca("ecommerce-connect-client-ca")
    ssl("pkcs12", "-export", "-nokeys", "-in", "ecommerce-data-ca.crt", "-out", "trust.p12",
        "-name", "ecommerce-data-ca", "-jdktrust", "anyExtendedKeyUsage", "-passout", "pass:changeit")

    checkout, exporter, redis_probe, search, es_probe = (secrets.token_urlsafe(32) for _ in range(5))

    def acl_hash(password):
        return hashlib.sha256(password.encode()).hexdigest()

    es_users = f"search-service:{es_password(search)}\nsearch-probe:{es_password(es_probe)}\n"
    documents = [
        secret("data", "checkout-redis-server", **certificate("checkout-redis", "ecommerce-data-ca"),
               **{"ca.crt": server_ca, "checkout-hash": acl_hash(checkout), "exporter-hash": acl_hash(exporter),
                  "probe-hash": acl_hash(redis_probe), "probe-password": redis_probe}),
        secret("data", "elasticsearch-server", **certificate("elasticsearch", "ecommerce-data-ca"),
               **{"ca.crt": server_ca, "users": es_users, "probe-password": es_probe,
                  "users_roles": "search_application:search-service\nsearch_probe:search-probe\n"}),
        secret("data", "kafka-connect-rest-server", **certificate("kafka-connect", "ecommerce-data-ca"),
               **{"client-ca.crt": client_ca}),
        secret("data", "kafka-connect-rest-client",
               **certificate("kafka-connect-init", "ecommerce-connect-client-ca", client=True),
               **{"ca.crt": server_ca}),
        secret("ecommerce", "checkout-redis-client",
               url=f"rediss://checkout:{checkout}@checkout-redis:6379/0"),
        secret("ecommerce", "elasticsearch-client", password=search),
        secret("ecommerce", "data-server-trust", **{"ca.crt": server_ca, "trust.p12": read("trust.p12")}),
        secret("observability", "checkout-redis-exporter", password=exporter),
        secret("observability", "data-server-trust", **{"ca.crt": server_ca}),
    ]
    admin = certificate("kafka-connect-admin", "ecommerce-connect-client-ca", client=True)
    return {"secrets": documents,
            "connect-admin": {key: b64(value) for key, value in {"ca.crt": server_ca, **admin}.items()}}


def provision(kubectl, context):
    suffix = hashlib.sha256(context.encode()).hexdigest()[:12]
    state = ROOT / ".tools/k8s-data-security" / suffix
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    bundle = state / "credentials.json"
    for namespace in SECRET_NAMES:
        existing = kubectl("get", "namespace", namespace, "--ignore-not-found", "-o", "name", capture=True)
        if not existing.strip():
            kubectl("create", "namespace", namespace)

    if not bundle.exists():
        for namespace, names in SECRET_NAMES.items():
            existing = kubectl("-n", namespace, "get", "secret", *names,
                               "--ignore-not-found", "-o", "name", capture=True)
            if existing.strip():
                raise RuntimeError(f"Data secrets already exist. Restore the matching local bundle {bundle}.")
        with TemporaryDirectory(dir=state) as tmp:
            contents = generate(Path(tmp))
            candidate = Path(tmp) / bundle.name
            fd = os.open(candidate, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as stream:
                json.dump(contents, stream)
            try:
                os.link(candidate, bundle)
            except FileExistsError:
                pass

    contents = json.loads(bundle.read_text(encoding="utf-8"))
    admin_dir = state / "connect-admin"
    admin_dir.mkdir(exist_ok=True, mode=0o700)
    for name, value in contents["connect-admin"].items():
        path = admin_dir / name
        fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
        with os.fdopen(fd, "wb") as stream:
            stream.write(base64.b64decode(value))

    kubectl("apply", "--server-side", "--field-manager=data-security-bootstrap", "-f", "-",
            input=json.dumps({"apiVersion": "v1", "kind": "List", "items": contents["secrets"]}))
    print(f"Connect admin mTLS files: {admin_dir}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--context", required=True)
    parser.add_argument("--kubeconfig", type=Path)
    args = parser.parse_args()
    command = ["kubectl", "--context", args.context]
    if args.kubeconfig:
        command += ["--kubeconfig", str(args.kubeconfig)]

    def kube(*arguments, capture=False, input=None):
        result = subprocess.run([*command, *arguments], input=input, text=True, check=True,
                                stdout=subprocess.PIPE if capture else None)
        return result.stdout

    provision(kube, args.context)


if __name__ == "__main__":
    main()
