#!/usr/bin/env python3
"""Measure completed CI runs from the GitHub Actions API, including previous attempts."""
import argparse
from datetime import datetime
import io
import json
import os
from pathlib import Path
import re
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse
from urllib.request import HTTPRedirectHandler, Request, build_opener, urlopen
import zipfile


SCOPES = {"docs-only", "single-service", "focused", "full-matrix"}
MAX_PLAN_BYTES = 256 * 1024


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def api(repository, path, token, archive=False):
    request = Request(f"https://api.github.com/repos/{repository}/{path}",
                      headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                               "User-Agent": "ecommerce-ci-measurements"})
    client = build_opener(NoRedirect)
    for retry in range(3):
        try:
            with client.open(request, timeout=20) as response:
                return json.load(response)
        except HTTPError as error:
            location = error.headers.get("Location", "")
            if archive and error.code == 302 and urlparse(location).scheme == "https":
                # The redirect is signed. Keep the API token on api.github.com.
                with urlopen(location, timeout=20) as response:
                    return response.read(MAX_PLAN_BYTES + 1)
            if retry == 2 or error.code not in (429, 500, 502, 503, 504):
                raise
            wait = error.headers.get("Retry-After", "")
            if wait.isdigit() and int(wait) > 15:
                raise
            time.sleep(int(wait) if wait.isdigit() else 2 ** retry)
        except URLError:
            if retry == 2:
                raise
            time.sleep(2 ** retry)


def pages(get, path, field):
    result = []
    for page in range(1, 101):
        items = get(f"{path}?per_page=100&page={page}")[field]
        result.extend(items)
        if len(items) < 100:
            return result
    raise ValueError(f"Too many paginated {field} for a single CI run")


def elapsed(start, end):
    if start and end:
        seconds = (datetime.fromisoformat(end) - datetime.fromisoformat(start)).total_seconds()
        if seconds >= 0:
            return int(seconds)
    return None


def job_metrics(job, run_created):
    skipped = job["conclusion"] == "skipped"
    failed = next((step for step in job.get("steps", []) if step["conclusion"] in ("failure", "timed_out")), None)
    failure_at = ((failed or {}).get("completed_at") or job["completed_at"]
                  if job["conclusion"] in ("failure", "timed_out") else None)
    image_step_seconds = {step["name"]: elapsed(step["started_at"], step["completed_at"])
                          for step in job.get("steps", [])
                          if job["name"].startswith("images / ") and step["conclusion"] != "skipped"}
    return {
        "id": job["id"], "job": job["name"], "url": job["html_url"], "result": job["conclusion"],
        "start_at": None if skipped else job["started_at"],
        "end_at": None if skipped else job["completed_at"],
        "queue_seconds": None if skipped else elapsed(job.get("created_at"), job["started_at"]),
        "runner_seconds": None if skipped else elapsed(job["started_at"], job["completed_at"]),
        "failure_seconds": elapsed(run_created, failure_at),
        "failed_step": failed["name"] if failed else None,
        "image_step_seconds": image_step_seconds,
    }


def report(run, attempts, scope):
    jobs = {}
    snapshots = []
    first_gate = None
    for metadata, attempt_jobs in attempts:
        started = metadata["run_started_at"]
        records = [job_metrics(job, run["created_at"]) for job in attempt_jobs]
        new_records = [job for job in records if job["id"] not in jobs and
                       (metadata["run_attempt"] == 1 or elapsed(started, job["start_at"]) is not None)]
        jobs.update({job["id"]: job for job in new_records})
        aggregate = next((job for job in records if job["job"] == "ci-required"), None)
        if aggregate and aggregate["end_at"] and aggregate["result"] == "success" and not first_gate:
            first_gate = aggregate["end_at"]
        images = [job for job in new_records if job["job"].startswith("images / ") and job["runner_seconds"] is not None]
        snapshots.append({
            "attempt": metadata["run_attempt"], "result": metadata["conclusion"],
            "attempt_started_at": started,
            "gate_result": aggregate["result"] if aggregate else None,
            "gate_since_run_created_seconds": elapsed(run["created_at"], aggregate["end_at"]) if aggregate else None,
            "gate_since_attempt_started_seconds": elapsed(started, aggregate["end_at"]) if aggregate else None,
            "runner_seconds": sum(job["runner_seconds"] or 0 for job in new_records),
            "image_matrix_seconds": elapsed(min(job["start_at"] for job in images), max(job["end_at"] for job in images)) if images else None,
            "jobs": new_records,
        })
    failures = [job for job in jobs.values() if job["failure_seconds"] is not None]
    first_failure = min(failures, key=lambda job: job["failure_seconds"]) if failures else None
    return {
        "id": run["id"], "url": run["html_url"], "event": run["event"], "result": run["conclusion"],
        "head_sha": run["head_sha"], "created_at": run["created_at"], "scope": scope,
        "reruns": len(attempts) - 1,
        "first_failure": first_failure,
        "first_passing_gate_seconds": elapsed(run["created_at"], first_gate),
        "runner_seconds_total": sum(attempt["runner_seconds"] for attempt in snapshots),
        "attempts": snapshots,
    }


def scope_from_plan(get, run_id, download):
    artifacts = pages(get, f"actions/runs/{run_id}/artifacts", "artifacts")
    artifact = next((item for item in artifacts if item["name"] == "ci-plan" and not item["expired"]), None)
    if not artifact or artifact["size_in_bytes"] > MAX_PLAN_BYTES:
        return "unavailable"
    try:
        with zipfile.ZipFile(io.BytesIO(download(artifact["id"]))) as archive:
            if archive.getinfo("plan.json").file_size > MAX_PLAN_BYTES:
                return "unavailable"
            scope = json.loads(archive.read("plan.json")).get("scope")
            return scope if isinstance(scope, str) and scope in SCOPES else "unavailable"
    except (KeyError, ValueError, AttributeError, zipfile.BadZipFile):
        return "unavailable"
    except HTTPError as error:
        if error.code not in (404, 410):
            raise
        return "unavailable"


def collect(repository, token, run_id):
    def get(path):
        return api(repository, path, token)

    run = get(f"actions/runs/{run_id}")
    if run["path"].split("@", 1)[0] != ".github/workflows/ci.yml":
        raise ValueError(f"Run {run_id} does not belong to the CI workflow")
    if run["status"] != "completed":
        raise ValueError(f"Run {run_id} is still {run['status']}")
    attempts = []
    for attempt in range(1, run["run_attempt"] + 1):
        path = f"actions/runs/{run_id}/attempts/{attempt}"
        attempts.append((get(path), pages(get, f"{path}/jobs", "jobs")))
    scope = scope_from_plan(get, run_id, lambda artifact: api(repository, f"actions/artifacts/{artifact}/zip", token, archive=True))
    return report(run, attempts, scope)


def write_summary(reports, output):
    print("### CI timings", file=output)
    print("\n| Run | Scope | Result | First failed step (s) | First passing gate (s) | Runner-minutes | Reruns |\n"
          "| --- | --- | --- | ---: | ---: | ---: | ---: |", file=output)
    for record in reports:
        failure = (record["first_failure"] or {}).get("failure_seconds")
        gate = record["first_passing_gate_seconds"]
        print(f"| [{record['id']}]({record['url']}) | {record['scope']} | {record['result']} | "
              f"{failure if failure is not None else '—'} | {gate if gate is not None else '—'} | "
              f"{record['runner_seconds_total'] / 60:.1f} | {record['reruns']} |", file=output)
    print("\nTimings begin at GitHub's CI run creation. The JSON includes each job's queue and runner time.", file=output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"))
    parser.add_argument("--run-id", nargs="+", required=True, type=int)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if not args.repo or not re.fullmatch(r"[\w.-]+/[\w.-]+", args.repo):
        parser.error("--repo must be owner/repository")
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        parser.error("set GH_TOKEN or GITHUB_TOKEN")
    reports = [collect(args.repo, token, run_id) for run_id in args.run_id]
    data = json.dumps(reports, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(data, encoding="utf-8")
    else:
        print(data, end="")
    if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
        with Path(summary).open("a", encoding="utf-8") as output:
            write_summary(reports, output)


if __name__ == "__main__":
    main()
