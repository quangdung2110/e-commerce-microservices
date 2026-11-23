import importlib.util
import json
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

CI = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(CI))


def module(name):
    spec = importlib.util.spec_from_file_location(name, CI / (name + ".py"))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


planner = module("plan")
gate = module("required_gate")
reports = module("test_reports")
release = module("release_manifest")
metrics = module("metrics")
feedback = module("feedback")


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        for path in ("java/services/order/pom.xml", "java/services/order/Dockerfile",
                     "go/services/auth/go.mod", "go/services/auth/Dockerfile",
                     "go/services/cart/go.mod", "go/services/cart/Dockerfile",
                     "go/observability/go.mod", "frontend/Dockerfile",
                     "go/services/api-gateway/Dockerfile", "infra/minio/Dockerfile"):
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.touch()

    def selected(self, paths):
        return planner.plan(self.root, paths)

    def test_shared_module_tests_itself_and_consumers_and_rebuilds_go_images(self):
        plan = self.selected(["go/observability/telemetry.go"])
        self.assertEqual({x["name"] for x in plan["services"]["include"]}, {"auth", "cart", "observability"})
        self.assertEqual({x["name"] for x in plan["images"]["include"]}, {"auth", "cart", "api-gateway"})
        self.assertFalse(plan["frontend"])

    def test_service_build_context_includes_shared_module(self):
        plan = self.selected(["go/services/cart/main.go"])
        self.assertEqual([x["name"] for x in plan["services"]["include"]], ["cart"])
        self.assertEqual(plan["images"]["include"][0]["context"], "go")

    def test_contracts_cover_frontend_and_all_services(self):
        plan = self.selected(["contracts/http/cart/v1/openapi.yaml"])
        self.assertEqual(len(plan["services"]["include"]), 4)
        self.assertEqual(len(plan["images"]["include"]), 6)
        self.assertTrue(plan["frontend"] and plan["contracts"] and plan["mesh"])

    def test_docs_only_have_no_builds_but_mesh_docs_have_validation(self):
        self.assertEqual(self.selected(["docs/README.md"])["images"], {"include": []})
        self.assertTrue(self.selected(["docs/mesh/runtime-contract.md"])["mesh"])

    def test_cd_snapshot_skips_docs_but_keeps_a_full_runtime_release(self):
        docs = planner.plan(self.root, ["docs/mesh/runtime-contract.md"], release_snapshot=True)
        self.assertEqual(docs["scope"], "docs-only")
        self.assertFalse(docs["images"]["include"])
        self.assertTrue(docs["mesh"] and docs["kubernetes"])
        narrow = planner.plan(self.root, ["docs/README.md", "go/services/cart/main.go"], release_snapshot=True)
        self.assertEqual(narrow["scope"], "full-matrix")
        self.assertEqual(len(narrow["images"]["include"]), 6)
        self.assertFalse(planner.plan(self.root, [], release_snapshot=True)["images"]["include"])

    def test_order_public_contract_is_scoped_and_removal_or_shared_change_falls_back(self):
        for path in ("java/services/order-service/pom.xml", "java/services/order-service/Dockerfile",
                     "deploy/checks/Dockerfile", planner.ORDER_OPENAPI):
            target = self.root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.touch()
        paths = [planner.ORDER_OPENAPI, "contracts/CHANGELOG.md"]
        focused = self.selected(paths)
        self.assertEqual(focused["scope"], "focused")
        self.assertEqual([job["name"] for job in focused["services"]["include"]], ["order-service"])
        self.assertEqual({job["name"] for job in focused["images"]["include"]}, planner.ORDER_HTTP_COMPONENTS)
        self.assertTrue(all(focused[key] for key in ("contracts", "frontend", "mesh", "kubernetes")))
        self.assertEqual(self.selected([*paths, "contracts/shared/json-schema/problem.json"])["scope"], "full-matrix")
        (self.root / planner.ORDER_OPENAPI).unlink()
        self.assertEqual(self.selected(paths)["scope"], "full-matrix")

    def test_unknown_path_and_removed_service_select_full_verification(self):
        self.assertEqual(self.selected(["new-shared-lib/src/a.go"])["scope"], "full-matrix")
        (self.root / "go/services/cart/go.mod").unlink()
        self.assertEqual(self.selected(["go/services/cart/go.mod"])["scope"], "full-matrix")

    def test_summaries_escape_changed_paths_and_show_reproduction(self):
        output = io.StringIO()
        planner.write_summary(self.selected(["docs/<b>changed</b>.md"]), output)
        self.assertIn("&lt;b&gt;changed&lt;/b&gt;", output.getvalue())
        result = feedback.summary("go", "cart", "go/services/cart", "", "tests-cart",
                                  {"unit": {"outcome": "failure"}, "vet": {"outcome": "success"}})
        self.assertIn("cd go/services/cart &amp;&amp; go test -race", result)
        self.assertIn("tests-cart", result)
        self.assertNotIn("Go vet", result)

    def test_metrics_preserve_first_failure_and_deduplicate_reused_jobs(self):
        def ts(second):
            return f"2026-01-01T00:00:{second:02}Z"

        def job(number, name, result, start, end, steps=()):
            return {"id": number, "name": name, "conclusion": result, "created_at": ts(1),
                    "started_at": ts(start), "completed_at": ts(end), "steps": list(steps),
                    "html_url": f"https://github.com/example/jobs/{number}"}

        passing = job(1, "images / image-cart", "success", 3, 8)
        prior = [passing, job(2, "services / cart", "failure", 3, 10, [
            {"name": "Race tests", "conclusion": "failure", "completed_at": ts(9)}
        ]), job(3, "ci-required", "failure", 11, 12)]
        retry = [passing, job(4, "services / cart", "success", 27, 30), job(5, "ci-required", "success", 31, 33)]
        run = {"id": 55, "html_url": "https://github.com/example/runs/55", "head_sha": "head",
               "event": "pull_request", "conclusion": "success", "created_at": ts(0)}
        attempts = [({"run_attempt": 1, "run_started_at": ts(1), "conclusion": "failure"}, prior),
                    ({"run_attempt": 2, "run_started_at": ts(25), "conclusion": "success"}, retry)]
        result = metrics.report(run, attempts, "single-service")
        self.assertEqual(result["first_failure"]["failed_step"], "Race tests")
        self.assertEqual(result["first_failure"]["failure_seconds"], 9)
        self.assertEqual(result["first_passing_gate_seconds"], 33)
        self.assertEqual(result["attempts"][1]["gate_since_attempt_started_seconds"], 8)
        self.assertEqual(result["runner_seconds_total"], 18)
        self.assertEqual(result["reruns"], 1)

    def test_workflow_changes_cannot_bypass_gate(self):
        plan = self.selected([".github/workflows/ci.yml"])
        self.assertTrue(plan["frontend"] and plan["contracts"] and plan["mesh"] and plan["kubernetes"])

    def test_cd_tools_and_sql_changes_rebuild_the_correct_release_tool(self):
        for directory in ("deploy/migrations", "deploy/checks"):
            path = self.root / directory / "Dockerfile"
            path.parent.mkdir(parents=True)
            path.touch()
        migration = self.selected(["java/services/order/src/main/resources/db/migration/V2__expand.sql"])
        self.assertEqual({i["name"] for i in migration["images"]["include"]}, {"order", "database-migrations"})
        checker = self.selected(["scripts/cd/e2e.py"])
        self.assertEqual([i["name"] for i in checker["images"]["include"]], ["deployment-checks"])
        self.assertEqual(checker["images"]["include"][0]["context"], ".")
        self.assertEqual(len(planner.plan(self.root, ["docs/README.md"], force=True)["images"]["include"]), 8)

    def test_skip_allowed_only_for_unaffected_jobs(self):
        plan = self.selected(["docs/README.md"])
        needs = {name: {"result": "success"} for name in ("plan", "tooling", "security")}
        needs.update({name: {"result": "skipped"} for name in ("frontend", "contracts", "mesh", "kubernetes", "services", "images")})
        self.assertEqual(gate.check(plan, needs), [])
        self.assertTrue(gate.check(self.selected(["frontend/package.json"]), needs))
        needs["security"]["result"] = "cancelled"
        self.assertTrue(gate.check(plan, needs))

    def test_failed_or_missing_jobs_cannot_pass(self):
        self.assertTrue(gate.check(self.selected([]), {}))

    def test_release_rejects_missing_component_and_wrong_source(self):
        digest = "sha256:" + "a" * 64
        image = "ghcr.io/owner/repo/cart"
        record = {"name": "cart", "revision": "commit", "run_id": "12", "run_attempt": "1",
                  "image": image, "digest": digest, "reference": image + "@" + digest}
        result = release.aggregate([record], ["cart"], "Owner/Repo", "commit", "12", "2")
        self.assertEqual(result["images"], [record])
        for records, names, revision in (([], ["cart"], "commit"), ([record, record], ["cart"], "commit"),
                                         ([record], ["cart"], "other-commit")):
            with self.assertRaises(ValueError):
                release.aggregate(records, names, "Owner/Repo", revision, "12", "2")

    def test_unresolvable_git_base_fails_closed(self):
        subprocess.check_call(["git", "init", "-q", str(self.root)])
        subprocess.check_call(["git", "-C", str(self.root), "add", "."])
        subprocess.check_call(["git", "-C", str(self.root), "-c", "user.name=test", "-c",
                               "user.email=test@example.com", "commit", "-qm", "base"])
        result = subprocess.run([sys.executable, str(CI / "plan.py"), "--base", "missing-ref"],
                                cwd=self.root, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(result.stdout, b"")

    def test_java_report_counts_skips(self):
        path = self.root / "target/surefire-reports/TEST-example.xml"
        path.parent.mkdir(parents=True)
        path.write_text('<testsuite><testcase classname="Example" name="run"/><testcase name="optional"><skipped/></testcase></testsuite>')
        cases = reports.summarize_java(self.root)
        self.assertEqual(len(cases), 2)
        self.assertTrue(cases[1]["skipped"])

    def test_git_rename_includes_removed_component(self):
        def git(*args):
            return subprocess.check_output(["git", "-C", str(self.root), *args]).decode().strip()
        git("init", "-q")
        git("config", "user.name", "test")
        git("config", "user.email", "test@example.com")
        target = self.root / "go/services/cart/main.go"
        target.write_text("package cart\n")
        git("add", ".")
        git("commit", "-qm", "base")
        base = git("rev-parse", "HEAD")
        target.rename(self.root / "go/services/auth/moved.go")
        git("add", "-A")
        git("commit", "-qm", "rename")
        result = subprocess.check_output([sys.executable, str(CI / "plan.py"), "--base", base], cwd=self.root)
        self.assertEqual({s["name"] for s in json.loads(result)["services"]["include"]}, {"auth", "cart"})


if __name__ == "__main__":
    unittest.main()
