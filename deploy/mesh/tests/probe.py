"""One read-only request from inside a caller pod; prints a machine-readable result."""
import json
import sys
import urllib.error
import urllib.request

spec = json.loads(sys.argv[1])
if spec.get("protocol") == "grpc":
    import grpc
    sku = b"00000000-0000-0000-0000-000000000000"
    request = bytes([10, len(sku)]) + sku
    try:
        with grpc.insecure_channel(spec["target"]) as channel:
            response = channel.unary_unary("/ecommerce.product.v1.ProductService/GetSku")(
                request, metadata=(("x-service-identity", spec["identity"]),), timeout=30)
        print(json.dumps({"status": "OK", "body_hex": response.hex()}))
    except grpc.RpcError as error:
        print(json.dumps({"status": error.code().name, "body": error.details()}))
else:
    headers = {"X-Correlation-Id": "mesh-p1-verification", "X-Causation-Id": "mesh-p1-verification",
               **spec.get("headers", {})}
    if "identity" in spec:
        headers["X-Service-Identity"] = spec["identity"]
    body = None
    if "body" in spec:
        body = json.dumps(spec["body"]).encode()
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(spec["target"], data=body, headers=headers, method=spec.get("method", "GET"))
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            print(json.dumps({"status": response.status, "body": response.read().decode(errors="replace")[:2000]}))
    except urllib.error.HTTPError as error:
        print(json.dumps({"status": error.code, "body": error.read().decode(errors="replace")[:2000]}))
    except (urllib.error.URLError, TimeoutError, ConnectionError, OSError) as error:
        print(json.dumps({"status": "CONNECTION_REJECTED", "body": str(error)}))
