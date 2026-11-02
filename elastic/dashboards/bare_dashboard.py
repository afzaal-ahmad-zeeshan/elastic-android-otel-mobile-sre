#!/usr/bin/env python3
"""Build the "[Bare HTTP] Mobile SRE overview" dashboard through the Kibana Dashboards API.

  ELASTIC_API_KEY=... KIBANA_URL=https://<project>.kb.<region>.<provider>.elastic.cloud \
      python3 elastic/dashboards/bare_dashboard.py

Everything is ES|QL over the `mobile-bare-telemetry` index, so what you see here is exactly what the bare
instrumentation records and nothing else. The SLO panels reference the four "[bare] ..." SLOs
(created by elastic/slos/create_bare_slos.py); they are looked up by name.
Idempotent: re-running updates the same dashboard id.
"""
import json
import os
import sys
import urllib.error
import urllib.request

KB = os.environ["KIBANA_URL"].rstrip("/")
KEY = os.environ["ELASTIC_API_KEY"]
IDX = "mobile-bare-telemetry"
DASH_ID = "mobile-sre-bare"

T = "@timestamp >= ?_tstart AND @timestamp < ?_tend"
ACTIONS = '"task.create","task.complete","task.delete","task.search","sync","screen.view"'
INTERACTIONS = '"task.create","task.complete","task.delete","task.search"'
BUCKET = "BUCKET(@timestamp, 40, ?_tstart, ?_tend)"


def api(method, path, body=None):
    req = urllib.request.Request(
        KB + path, method=method, data=json.dumps(body).encode() if body is not None else None,
        headers={"Authorization": "ApiKey " + KEY, "kbn-xsrf": "true", "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read() or b"{}")


# ---- panel helpers ------------------------------------------------------------------------------
_y = 0
panels = []


def grid(x, y, w, h):
    return {"x": x, "y": y, "w": w, "h": h}


def vis(x, y, w, h, config):
    panels.append({"type": "vis", "grid": grid(x, y, w, h), "config": config})


def md(y, h, content, x=0, w=48):
    panels.append({"type": "markdown", "grid": grid(x, y, w, h),
                   "config": {"content": content, "settings": {"open_links_in_new_tab": True}, "hide_title": True}})


def esql(q):
    return {"type": "esql", "query": q}


def metric(x, y, w, h, title, query, column, label=None):
    vis(x, y, w, h, {"type": "metric", "title": title, "data_source": esql(query),
                     "metrics": [{"type": "primary", "column": column, "label": label or title}]})


def xy(x, y, w, h, title, query, kind, xcol, ycols, breakdown=None):
    layer = {"type": kind, "data_source": esql(query), "x": {"column": xcol}, "y": [{"column": c} for c in ycols]}
    if breakdown:
        layer["breakdown_by"] = {"column": breakdown}
    vis(x, y, w, h, {"type": "xy", "title": title, "layers": [layer]})


def table(x, y, w, h, title, query, rows, metrics):
    vis(x, y, w, h, {"type": "data_table", "title": title, "data_source": esql(query),
                     "rows": [{"column": c} for c in rows], "metrics": [{"column": c} for c in metrics]})


# ---- SLO ids ------------------------------------------------------------------------------------
code, slos = api("GET", "/api/observability/slos?perPage=100")
# SLOs are matched by the name before " <=" so changing a threshold in create_bare_slos.py does not break this.
by_key = {s["name"].split(" <=")[0]: s for s in slos.get("results", [])}
want_keys = ["[bare] List page load", "[bare] Stats page load", "[bare] Interaction latency", "[bare] Action success rate"]
missing = [k for k in want_keys if k not in by_key]
if missing:
    sys.exit(f"missing SLOs (run elastic/slos/create_bare_slos.py first): {missing}")
slo_id = {by_key[k]["name"]: by_key[k]["id"] for k in want_keys}
want = [by_key[k]["name"] for k in want_keys]
LIST_MS = by_key["[bare] List page load"]["name"].split("<= ")[1].split(" ")[0]

# ---- layout -------------------------------------------------------------------------------------
# Kept deliberately small: what the bare instrumentation collects (user actions with a duration and an
# outcome, screen views, hangs/ANRs, client-side HTTP calls) and the four questions an SRE asks of it:
# are we meeting the SLOs, is it fast, is it failing, is it the backend.
y = 0
md(y, 3, "## Bare HTTP: mobile SRE overview\n"
         f"Hand-rolled telemetry from the Tasks app (Android), one JSON doc per user action in `{IDX}`. "
         "Page load = `screen.view` duration. **Not collected, so not shown:** backend traces, session ids, crash events.")
y += 3

# 1. Are we meeting the SLOs?
for i, n in enumerate(want):
    panels.append({"type": "slo_overview", "grid": grid(i * 12, y, 12, 8),
                   "config": {"slo_id": slo_id[n], "overview_mode": "single", "hide_title": False,
                              "title": n.replace("[bare] ", "")}})
y += 8

# 2. Right now: five numbers
base = f"FROM {IDX} | WHERE {T}"
kpis = [
    ("User actions", f"{base} AND event.name IN ({ACTIONS}) | STATS actions = COUNT(*)", "actions"),
    ("Error rate %", f'{base} AND event.name IN ({ACTIONS}) | STATS f = COUNT(*) WHERE event.outcome == "failure", n = COUNT(*) | EVAL error_rate = ROUND(100.0 * f / n, 2)', "error_rate"),
    ("p95 page load (ms)", f'{base} AND event.name == "screen.view" | STATS p95 = ROUND(PERCENTILE(event.duration_ms, 95), 0)', "p95"),
    ("p95 interaction (ms)", f"{base} AND event.name IN ({INTERACTIONS}) | STATS p95 = ROUND(PERCENTILE(event.duration_ms, 95), 0)", "p95"),
    ("Hangs + ANRs", f'{base} AND event.name IN ("app.hang","app.anr_previous_run") | STATS hangs = COUNT(*)', "hangs"),
]
widths = [10, 10, 10, 10, 8]
x = 0
for (t, q, c), w in zip(kpis, widths):
    metric(x, y, w, 6, t, q, c)
    x += w
y += 6

# 3. Over time: traffic and failures, latency
xy(0, y, 24, 13, "User actions over time (success vs failure)",
   f'{base} AND event.name IN ({ACTIONS}) | STATS actions = COUNT(*) BY bucket = {BUCKET}, event.outcome',
   "bar_stacked", "bucket", ["actions"], "event.outcome")
xy(24, y, 24, 13, "p95 latency over time (ms)",
   f'{base} AND event.name IN ({ACTIONS}) | EVAL what = CASE(event.name == "screen.view", CONCAT("page load: ", screen), event.name) | STATS p95 = ROUND(PERCENTILE(event.duration_ms, 95), 1) BY bucket = {BUCKET}, what',
   "line", "bucket", ["p95"], "what")
y += 13

# 4. Where is it slow or failing? One table instead of two
table(0, y, 32, 12, f"Where it is slow or failing  |  SLO: list <= {LIST_MS} ms, stats <= 300 ms, interactions <= 150 ms",
      f'{base} AND event.name IN ({ACTIONS}) | EVAL what = CASE(event.name == "screen.view", CONCAT("page load: ", screen), event.name) | STATS count = COUNT(*), p50 = ROUND(PERCENTILE(event.duration_ms, 50), 0), p95 = ROUND(PERCENTILE(event.duration_ms, 95), 0), failed = COUNT(*) WHERE event.outcome == "failure" BY what | EVAL failed_pct = ROUND(100.0 * failed / count, 1) | KEEP what, count, p50, p95, failed_pct | SORT p95 DESC',
      ["what"], ["count", "p50", "p95", "failed_pct"])
table(32, y, 16, 12, "By platform",
      f'{base} AND event.name IN ({ACTIONS}) | STATS actions = COUNT(*), failed = COUNT(*) WHERE event.outcome == "failure", p95_ms = PERCENTILE(event.duration_ms, 95) BY os.name | EVAL error_pct = ROUND(100.0 * failed / actions, 2), p95_ms = ROUND(p95_ms, 0) | KEEP os.name, actions, error_pct, p95_ms',
      ["os.name"], ["actions", "error_pct", "p95_ms"])
y += 12

# 5. Is it the backend? What the app sees of each call
table(0, y, 24, 10, "Backend calls as the app sees them",
      f'{base} AND event.name == "http.client" | EVAL status = COALESCE(TO_STRING(http.response.status_code), "network error") | STATS calls = COUNT(*), p95_ms = ROUND(PERCENTILE(event.duration_ms, 95), 0) BY status | SORT calls DESC',
      ["status"], ["calls", "p95_ms"])
xy(24, y, 24, 10, "Hangs and ANRs over time",
   f'{base} AND event.name IN ("app.hang","app.anr_previous_run") | STATS events = COUNT(*) BY bucket = {BUCKET}, event.name',
   "bar_stacked", "bucket", ["events"], "event.name")
y += 10

body = {
    "title": "[Bare HTTP] Mobile SRE overview",
    "description": "Hand-rolled HTTP telemetry from the Tasks app (Android): SLOs, headline numbers, latency, failures, backend calls, hangs.",
    "time_range": {"from": "now-1h", "to": "now"},
    "refresh_interval": {"pause": False, "value": 30000},
    "tags": ["mobile", "bare", "sre"],
    "options": {"use_margins": True, "sync_cursor": True, "sync_tooltips": True},
    "panels": panels,
}

code, out = api("PUT", f"/api/dashboards/{DASH_ID}", body)
if code == 404:
    code, out = api("POST", "/api/dashboards", {**body, "id": DASH_ID})
print(code, json.dumps(out)[:600])
if code < 300:
    print(f"{KB}/app/dashboards#/view/{DASH_ID}")
else:
    sys.exit(1)
