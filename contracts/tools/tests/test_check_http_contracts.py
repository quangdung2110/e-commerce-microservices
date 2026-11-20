import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import check_http_contracts as checks  # noqa: E402


def spec(responses: dict, components: dict | None = None) -> dict:
    return {"paths": {"/x": {"get": {"responses": responses}}},
            "components": {"responses": components or {}}}


class RetryAfterTest(unittest.TestCase):
    def test_inline_response_without_header_is_reported(self):
        problems = checks.retry_after_violations(spec({"503": {"description": "down"}}))
        self.assertEqual(1, len(problems))
        self.assertIn("GET /x 503", problems[0])

    def test_inline_response_with_header_passes(self):
        doc = spec({"429": {"description": "slow", "headers": {"Retry-After": {"schema": {"type": "integer"}}}}})
        self.assertEqual([], checks.retry_after_violations(doc))

    def test_header_name_is_case_insensitive(self):
        doc = spec({"503": {"headers": {"retry-after": {}}}})
        self.assertEqual([], checks.retry_after_violations(doc))

    def test_referenced_response_is_resolved(self):
        doc = spec({"503": {"$ref": "#/components/responses/Down"}},
                   {"Down": {"headers": {"Retry-After": {}}}})
        self.assertEqual([], checks.retry_after_violations(doc))

    def test_referenced_response_without_header_is_reported(self):
        doc = spec({"503": {"$ref": "#/components/responses/Down"}}, {"Down": {"description": "d"}})
        self.assertEqual(1, len(checks.retry_after_violations(doc)))

    def test_other_status_codes_are_ignored(self):
        self.assertEqual([], checks.retry_after_violations(spec({"500": {}, "404": {}, "200": {}})))

    def test_numeric_and_string_codes_are_both_checked(self):
        self.assertEqual(1, len(checks.retry_after_violations(spec({503: {}}))))

    def test_the_repository_contracts_are_clean(self):
        root = Path(__file__).resolve().parents[2] / "http"
        problems = [p for s in sorted(root.rglob("openapi.yaml")) for p in checks.check_file(s)]
        self.assertEqual([], problems)


if __name__ == "__main__":
    unittest.main()
