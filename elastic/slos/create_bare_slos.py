#!/usr/bin/env python3
"""Create (or update) the four "[bare] ..." SLOs on the mobile-bare-telemetry index.

  ELASTIC_API_KEY=... KIBANA_URL=https://<project>.kb.... python3 elastic/slos/create_bare_slos.py

Targets are tight on purpose so error budget visibly burns (the demo backend adds 60-100 ms of latency,
and the chaos presets add seconds). Loosen LIST_MS / STATS_MS / INTERACTION_MS or the targets for a calmer view.
"""
import json
import os
import urllib.error
import urllib.request

KB = os.environ["KIBANA_URL"].rstrip("/")
KEY = os.environ["ELASTIC_API_KEY"]
LIST_MS, STATS_MS, INTERACTION_MS = 120, 300, 150


def api(method, path, body=None):
    req = urllib.request.Request(KB + path, method=method, data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "ApiKey " + KEY, "kbn-xsrf": "true", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


def slo(name, desc, filt, good, target, tags):
    return {"name": name, "description": desc,
            "indicator": {"type": "sli.kql.custom", "params": {"index": "mobile-bare-telemetry", "filter": filt, "good": good, "total": "", "timestampField": "@timestamp"}},
            "timeWindow": {"duration": "7d", "type": "rolling"}, "budgetingMethod": "occurrences",
            "objective": {"target": target}, "tags": ["mobile", "bare", "tasks-app", *tags],
            "settings": {"syncDelay": "1m", "frequency": "1m"}}


SLOS = [
    slo(f"[bare] List page load <= {LIST_MS} ms", f"Time from opening the list screen until its data is ready. 99% of loads must take <= {LIST_MS} ms.",
        'event.name : "screen.view" and screen : "list"', f"event.duration_ms <= {LIST_MS}", 0.99, ["page-load"]),
    slo(f"[bare] Stats page load <= {STATS_MS} ms", "Time to compute and show the Stats screen. The 8 s main-thread freeze fault burns this budget fast.",
        'event.name : "screen.view" and screen : "stats"', f"event.duration_ms <= {STATS_MS}", 0.99, ["page-load"]),
    slo(f"[bare] Interaction latency <= {INTERACTION_MS} ms", f"Create/complete/delete/search taps that finish within {INTERACTION_MS} ms, including the backend call.",
        'event.name : ("task.create" or "task.complete" or "task.delete" or "task.search")', f"event.duration_ms <= {INTERACTION_MS}", 0.99, ["interaction"]),
    slo("[bare] Action success rate", "User-visible actions that complete without error.",
        'event.name : ("task.create" or "task.complete" or "task.delete" or "task.search" or "screen.view" or "sync")', 'event.outcome : "success"', 0.995, ["availability"]),
]

# Updating by name keeps dashboards that reference the SLO ids working.
_, existing = api("GET", "/api/observability/slos?perPage=100")
by_name = {s["name"].split(" <=")[0]: s for s in existing.get("results", [])}
for s in SLOS:
    key = s["name"].split(" <=")[0]
    if key in by_name:
        code, out = api("PUT", f"/api/observability/slos/{by_name[key]['id']}", s)
        print("updated", code, s["name"])
    else:
        code, out = api("POST", "/api/observability/slos", s)
        print("created", code, s["name"], out.get("id", out))
