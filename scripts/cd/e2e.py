#!/usr/bin/env python3
"""Staging-only purchase, cancellation, duplicate webhook and refund against Stripe sandbox."""
import hashlib
import hmac
import json
import os
import re
import sys
import time
from urllib.parse import urlencode, urlparse
import urllib.error
import urllib.request
import uuid


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise RuntimeError("Unexpected redirect; credentials must not follow another endpoint")


def request(method, url, payload=None, token=None, key=None, form=False, headers=None):
    data = (payload if isinstance(payload, bytes) else (urlencode(payload).encode() if form else json.dumps(payload).encode())) if payload is not None else None
    request_headers = {"Accept": "application/json", **(headers or {})}
    if token:
        request_headers["Authorization"] = "Bearer " + token
    if key:
        request_headers["Idempotency-Key"] = key
    if data is not None:
        request_headers["Content-Type"] = "application/x-www-form-urlencoded" if form else "application/json"
    req = urllib.request.Request(url, data=data, headers=request_headers, method=method)
    try:
        with urllib.request.build_opener(NoRedirect).open(req, timeout=20) as response:
            raw, status = response.read(), response.status
    except urllib.error.HTTPError as error:
        raw, status = error.read(), error.code
    body = json.loads(raw) if raw else {}
    if status not in (200, 201, 202, 204):
        raise RuntimeError(f"E2E request failed with HTTP {status}; response body redacted")
    return body


class Scenario:
    def __init__(self, environ=None):
        self.env = dict(os.environ if environ is None else environ)
        if self.env.get("DEPLOYMENT_ENVIRONMENT") != "staging":
            raise ValueError("Mutating E2E is permitted only in staging")
        if not self.env.get("POD_NAMESPACE") or self.env["POD_NAMESPACE"] != self.env.get("E2E_ALLOWED_NAMESPACE"):
            raise ValueError("E2E namespace does not match its staging deployment")
        self.stripe_key = self.env["E2E_STRIPE_SECRET_KEY"]
        if not self.stripe_key.startswith(("sk_test_", "rk_test_")):
            raise ValueError("Stripe sandbox credential required")
        self.base = self.env["API_BASE_URL"].rstrip("/")
        if urlparse(self.base).scheme != "https" or self.base != self.env["STOREFRONT_URL"].rstrip("/"):
            raise ValueError("E2E must address its configured staging HTTPS origin")
        self.tokens = {}
        self.created = []

    def api(self, method, path, body=None, role="buyer", key=None):
        return request(method, self.base + path, body, self.tokens.get(role), key)

    def stripe(self, method, path, body=None, key=None):
        return request(method, "https://api.stripe.com/v1/" + path, body, self.stripe_key, key, form=True)

    def login(self):
        for role, prefix in (("buyer", "E2E_"), ("admin", "E2E_ADMIN_")):
            session = self.api("POST", "/api/v1/auth/sessions", {"email": self.env[prefix + "EMAIL"], "password": self.env[prefix + "PASSWORD"]}, role=role)
            self.tokens[role] = session["access_token"]
        cart = self.api("GET", "/api/v1/carts/mine")
        if cart.get("items"):
            raise RuntimeError("Dedicated E2E account must start with an empty cart")

    def poll(self, order_id, predicate, seconds=180):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            detail = self.api("GET", "/api/v1/orders/" + order_id)
            if predicate(detail):
                return detail
            time.sleep(2)
        raise RuntimeError("Order did not converge before the E2E deadline")

    def create_order(self):
        self.api("POST", "/api/v1/carts/mine/items", {"sku_id": self.env["E2E_SKU_ID"], "delta": 1}, key=str(uuid.uuid4()))
        preview = self.api("POST", "/api/v1/checkout/preview", {"delivery_address_id": self.env["E2E_ADDRESS_ID"]}, key=str(uuid.uuid4()))
        checkout_token = preview["checkout_token"]
        result = self.api("POST", "/api/v1/checkout/submit", {"checkout_token": checkout_token})
        deadline = time.monotonic() + 90
        while not result.get("order_id") and time.monotonic() < deadline:
            time.sleep(2)
            result = self.api("GET", "/api/v1/checkout/submissions/" + checkout_token)
        if not result.get("order_id"):
            raise RuntimeError("Checkout did not produce an order")
        order = str(result["order_id"])
        self.created.append(order)
        # Only our fixture SKU is removed; unrelated/shared carts are never cleared.
        self.api("DELETE", "/api/v1/carts/mine/items", {"sku_ids": [self.env["E2E_SKU_ID"]]}, key=str(uuid.uuid4()))
        return order, result

    def replay_webhook(self, payment_intent):
        deadline = time.monotonic() + 60
        event = None
        while event is None and time.monotonic() < deadline:
            events = self.stripe("GET", "events?type=payment_intent.succeeded&limit=100")
            event = next((item for item in events["data"] if item.get("data", {}).get("object", {}).get("id") == payment_intent), None)
            if event is None:
                time.sleep(2)
        if event is None or event.get("livemode") is not False:
            raise RuntimeError("No matching sandbox event found")
        payload = json.dumps(event, separators=(",", ":")).encode()
        stamp = str(int(time.time()))
        signature = hmac.new(self.env["E2E_WEBHOOK_SECRET"].encode(), stamp.encode() + b"." + payload, hashlib.sha256).hexdigest()
        for _ in range(2):
            request("POST", self.base + "/v1/payments/webhook/stripe", payload,
                    headers={"Stripe-Signature": f"t={stamp},v1={signature}"})

    def run(self):
        self.login()
        cancelled, _ = self.create_order()
        self.api("POST", f"/api/v1/orders/{cancelled}/cancellation-requests", {"reason_code": "USER_CANCEL"}, key=str(uuid.uuid4()))
        self.poll(cancelled, lambda d: d["order_status"] == "CANCELLED")
        order, submission = self.create_order()
        intent = submission["payment"]["client_secret"].split("_secret_", 1)[0]
        if not re.fullmatch(r"pi_[A-Za-z0-9]+", intent):
            raise RuntimeError("Checkout returned an invalid PaymentIntent identifier")
        provider = self.stripe("GET", "payment_intents/" + intent)
        if provider.get("livemode") is not False:
            raise RuntimeError("Refusing to mutate a live PaymentIntent")
        self.stripe("POST", f"payment_intents/{intent}/confirm", {"payment_method": "pm_card_visa", "return_url": self.env["STOREFRONT_URL"] + f"/orders/{order}/payment/complete"}, key=str(uuid.uuid4()))
        self.replay_webhook(intent)
        paid = self.poll(order, lambda d: d["payment_status"] == "PAID")
        if paid["payment"]["active_attempt_number"] != 1:
            raise RuntimeError("Duplicate delivery unexpectedly changed the payment attempt")
        key = str(uuid.uuid4())
        for _ in range(2):
            self.api("POST", f"/api/v1/admin/orders/{order}/pre-dispatch-refund-requests", {"reason_code": "ADMIN_REFUND", "reason_details": "Staging E2E cleanup"}, role="admin", key=key)
        self.poll(order, lambda d: d["payment_status"] == "REFUNDED" and (d.get("refund_case") or {}).get("status") == "SUCCEEDED")
        print(json.dumps({"phase": "e2e", "result": "passed", "cases": ["auth-cart-checkout", "cancel-before-payment", "sandbox-payment", "duplicate-webhook", "idempotent-refund"]}))


if __name__ == "__main__":
    try:
        Scenario().run()
    except Exception as error:
        # Never log credentials, raw provider responses, client_secret or PII.
        print(f"Staging E2E failed: {type(error).__name__}; inspect dedicated fixture/orders securely", file=sys.stderr)
        raise SystemExit(1)
