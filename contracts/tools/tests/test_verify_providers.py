import json
import sys
import tempfile
import textwrap
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import verify_providers as vp  # noqa: E402

PROBLEM_SCHEMA = {
    "$schema": "https://json-schema.org/draft/2020-12/schema",
    "type": "object",
    "required": ["code", "status"],
    "properties": {"code": {"type": "string", "pattern": "^[A-Z_]+$"}, "status": {"type": "integer"}},
}

OPENAPI = textwrap.dedent("""
    openapi: 3.1.0
    info: {title: t, version: '1'}
    paths:
      /items/{id}:
        get:
          responses:
            '200':
              description: ok
              headers:
                X-Total: {required: true, schema: {type: integer}}
              content:
                application/json: {schema: {$ref: '#/components/schemas/Item'}}
            '404': {$ref: '#/components/responses/NotFound'}
      /items/special:
        get:
          responses:
            '204': {description: no body}
      /items:
        post:
          responses:
            '201':
              description: created
              content:
                application/json: {schema: {$ref: '#/components/schemas/Item'}}
    components:
      schemas:
        Item:
          type: object
          required: [id, price]
          additionalProperties: false
          properties:
            id: {type: string}
            price: {type: integer, minimum: 0}
      responses:
        NotFound:
          description: missing
          content:
            application/problem+json: {schema: {$ref: '../../../shared/problem.schema.json'}}
""")


class ContractFixture(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        (root / "shared").mkdir()
        (root / "shared" / "problem.schema.json").write_text(json.dumps(PROBLEM_SCHEMA), encoding="utf-8")
        spec_dir = root / "http" / "x" / "v1"
        spec_dir.mkdir(parents=True)
        self.spec = spec_dir / "openapi.yaml"
        self.spec.write_text(OPENAPI, encoding="utf-8")
        self.contract = vp.Contract(self.spec)

    def tearDown(self):
        self.tmp.cleanup()

    def check(self, method, path, status, content_type, headers, body):
        template, _ = self.contract.operation(method, path)
        payload = body if isinstance(body, bytes) else json.dumps(body).encode()
        return self.contract.validate(template, method, status, content_type, headers, payload)


class ContractValidationTest(ContractFixture):
    def test_a_conforming_response_passes(self):
        verdict = self.check("GET", "/items/1", 200, "application/json", {"x-total": "3"}, {"id": "1", "price": 5})
        self.assertTrue(verdict.ok, verdict.problems)

    def test_a_schema_violation_is_reported_with_its_location(self):
        verdict = self.check("GET", "/items/1", 200, "application/json", {"x-total": "3"}, {"id": "1", "price": -1})
        self.assertFalse(verdict.ok)
        self.assertIn("price", verdict.problems[0])

    def test_an_unknown_property_is_reported_when_the_contract_forbids_it(self):
        verdict = self.check("GET", "/items/1", 200, "application/json", {"x-total": "3"},
                             {"id": "1", "price": 1, "extra": True})
        self.assertFalse(verdict.ok)

    def test_a_missing_required_header_is_reported(self):
        verdict = self.check("GET", "/items/1", 200, "application/json", {}, {"id": "1", "price": 1})
        self.assertIn("X-Total", " ".join(verdict.problems))

    def test_an_undeclared_status_is_reported_with_the_declared_ones(self):
        verdict = self.check("GET", "/items/1", 500, "application/json", {}, {})
        self.assertIn("status 500 is not declared", verdict.problems[0])
        self.assertIn("200", verdict.problems[0])

    def test_a_wrong_media_type_is_reported(self):
        verdict = self.check("GET", "/items/1", 404, "text/html", {}, b"<html>")
        self.assertIn("media type", verdict.problems[0])

    def test_a_response_referenced_from_components_and_a_cross_file_schema_resolve(self):
        good = self.check("GET", "/items/1", 404, "application/problem+json; charset=utf-8", {}, {"code": "NOT_FOUND", "status": 404})
        bad = self.check("GET", "/items/1", 404, "application/problem+json", {}, {"code": "not-found", "status": 404})
        self.assertTrue(good.ok, good.problems)
        self.assertFalse(bad.ok)

    def test_a_body_where_the_contract_declares_none_is_reported(self):
        verdict = self.check("GET", "/items/special", 204, "", {}, b'{"x":1}')
        self.assertIn("declares no body", verdict.problems[0])
        self.assertTrue(self.check("GET", "/items/special", 204, "", {}, b"").ok)

    def test_a_literal_path_wins_over_a_templated_one(self):
        template, _ = self.contract.operation("GET", "/items/special")
        self.assertEqual("/items/special", template)

    def test_an_unknown_path_or_method_matches_nothing(self):
        self.assertIsNone(self.contract.operation("GET", "/nope"))
        self.assertIsNone(self.contract.operation("DELETE", "/items/1"))

    def test_invalid_json_is_reported(self):
        verdict = self.check("GET", "/items/1", 200, "application/json", {"x-total": "1"}, b"{not json")
        self.assertIn("not valid JSON", verdict.problems[0])


class Handler(BaseHTTPRequestHandler):
    seen_identity = []

    def do_GET(self):  # noqa: N802
        Handler.seen_identity.append(self.headers.get("X-Service-Identity"))
        if self.path.startswith("/items/known"):
            body = json.dumps({"id": "known", "price": 3}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-Total", "1")
        elif self.path.startswith("/items/drifted"):
            body = json.dumps({"id": "drifted", "price": "free"}).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("X-Total", "1")
        else:
            body = json.dumps({"code": "NOT_FOUND", "status": 404}).encode()
            self.send_response(404)
            self.send_header("Content-Type", "application/problem+json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *args):
        pass


class RunTest(ContractFixture):
    def setUp(self):
        super().setUp()
        Handler.seen_identity = []
        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

        root = Path(self.tmp.name)
        (root / "catalog").mkdir()
        (root / "catalog" / "interactions.yaml").write_text(textwrap.dedent("""
            interactions:
              - {id: read-items, protocol: http, provider: items-service, consumers: [web], contract: http/x/v1/openapi.yaml}
        """), encoding="utf-8")
        self.original_contracts = vp.CONTRACTS
        vp.CONTRACTS = root

    def tearDown(self):
        vp.CONTRACTS = self.original_contracts
        self.server.shutdown()
        super().tearDown()

    def probes(self, probes):
        path = Path(self.tmp.name) / "probes.yaml"
        base = f"http://127.0.0.1:{self.server.server_address[1]}"
        path.write_text(json.dumps({"version": 1, "targets": {"items-service": base}, "probes": probes}), encoding="utf-8")
        return path

    def test_a_conforming_provider_passes_and_the_consumer_identity_is_sent(self):
        path = self.probes([{"id": "known", "interaction": "read-items", "as": "web",
                             "request": {"method": "GET", "path": "/items/known"}, "expect": [200]}])
        self.assertEqual(0, vp.run(path, set(), False, 5))
        self.assertEqual(["web"], Handler.seen_identity)

    def test_a_provider_that_drifted_from_its_contract_fails(self):
        path = self.probes([{"id": "drifted", "interaction": "read-items", "as": "web",
                             "request": {"method": "GET", "path": "/items/drifted"}, "expect": [200]}])
        self.assertEqual(1, vp.run(path, set(), False, 5))

    def test_a_status_other_than_expected_fails_even_when_the_contract_declares_it(self):
        path = self.probes([{"id": "unexpected", "interaction": "read-items", "as": "web",
                             "request": {"method": "GET", "path": "/items/other"}, "expect": [200]}])
        self.assertEqual(1, vp.run(path, set(), False, 5))

    def test_a_probe_can_omit_the_identity_header(self):
        path = self.probes([{"id": "anonymous", "interaction": "read-items", "as": None,
                             "request": {"method": "GET", "path": "/items/other"}, "expect": [404]}])
        self.assertEqual(0, vp.run(path, set(), False, 5))
        self.assertEqual([None], Handler.seen_identity)

    def test_captured_values_feed_later_probes_and_unresolved_ones_are_skipped(self):
        path = self.probes([
            {"id": "first", "interaction": "read-items", "as": "web",
             "request": {"method": "GET", "path": "/items/known"}, "expect": [200], "capture": {"item": "id"}},
            {"id": "second", "interaction": "read-items", "as": "web",
             "request": {"method": "GET", "path": "/items/${item}"}, "expect": [200]},
            {"id": "third", "interaction": "read-items", "as": "web",
             "request": {"method": "GET", "path": "/items/${never_captured}"}, "expect": [200]},
        ])
        self.assertEqual(0, vp.run(path, set(), False, 5))

    def test_an_unreachable_provider_fails_the_probe(self):
        path = self.probes([{"id": "down", "interaction": "read-items", "as": "web",
                             "request": {"method": "GET", "path": "/items/known"}, "expect": [200]}])
        self.server.shutdown()
        self.server.server_close()
        self.assertEqual(1, vp.run(path, set(), False, 1))
        # restart so tearDown's shutdown() does not hang
        self.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()


class HelpersTest(unittest.TestCase):
    def test_substitute_replaces_known_variables_only(self):
        value = vp._substitute({"a": ["x-${one}", "${two}"]}, {"one": "1"})
        self.assertEqual({"a": ["x-1", "${two}"]}, value)

    def test_dig_follows_keys_and_indexes(self):
        self.assertEqual("b", vp._dig({"items": [{"id": "a"}, {"id": "b"}]}, "items[1].id"))


if __name__ == "__main__":
    unittest.main()
