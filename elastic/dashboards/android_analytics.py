#!/usr/bin/env python3
"""Build the "Android app analytics" dashboard for one SDK through the Kibana Dashboards API.

  FLAVOR=otel ELASTIC_API_KEY=... KIBANA_URL=https://<project>.kb.<region>.<provider>.elastic.cloud \
      python3 elastic/dashboards/android_analytics.py
  FLAVOR=edot ...

One dashboard per SDK, same six sections (Audience, Sessions, Quality, Performance, Engagement, Correlation),
laid out like the Play Console / Firebase reports: installs and sessions first, then what went wrong, then
what to compare. Everything is ES|QL over the OTLP data streams (`traces-*.otel-*`, `logs-*.otel-*`),
filtered by the `telemetry.flavor` resource attribute the app sets ("otel" | "edot"; edotFull reports "edot").
Panels fill only with what that SDK sends out of the box, so an empty panel is a finding, not a bug.
Idempotent: re-running updates the same dashboard id.
"""
import json
import os
import sys
import urllib.error
import urllib.request

KB = os.environ["KIBANA_URL"].rstrip("/")
KEY = os.environ["ELASTIC_API_KEY"]
FLAVOR = os.environ["FLAVOR"]
CFG = {
    "otel": {
        "title": "[OTel] Android app analytics",
        "intro": "## OpenTelemetry Android: app analytics\n"
                 "Upstream agent 1.7.0 set up as its docs show: one init call, no plugins, no manual spans. "
                 "Counts installs (`app.installation.id`) and sessions (`session.id`) the SDK reports by itself.",
    },
    "edot": {
        "title": "[EDOT] Android app analytics",
        "intro": "## EDOT Android: app analytics\n"
                 "EDOT 1.10.0 set up as its docs show: Gradle plugin plus `ElasticApmAgent` init. Automatic "
                 "instrumentation is optional and not bundled, so `mobile-demo-android-edot` is the agent alone and "
                 "`mobile-demo-android-edotfull` adds the documented OkHttp and crash plugins. Counts installs and "
                 "sessions the SDK reports by itself; an empty panel means EDOT sends nothing for it out of the box.",
    },
}[FLAVOR]
DASH_ID = f"mobile-analytics-{FLAVOR}"

SRC = "FROM traces-*.otel-*, logs-*.otel-*"
T = "@timestamp >= ?_tstart AND @timestamp < ?_tend"
BASE = f'{SRC} | WHERE {T} AND resource.attributes.telemetry.flavor == "{FLAVOR}"'
BUCKET = "BUCKET(@timestamp, 40, ?_tstart, ?_tend)"
INSTALL = "resource.attributes.app.installation.id"
SESSION = "attributes.session.id"
VERSION = "resource.attributes.service.version"
OSV = "resource.attributes.os.version"
DEVICE = "resource.attributes.device.model.identifier"
SERVICE = "resource.attributes.service.name"
SCREEN = "attributes.app.screen.name"
CRASH = 'event_name == "app.crash"'
ANR = 'event_name LIKE "*anr*"'
WITH_SESSION = f"{SESSION} IS NOT NULL"


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


def metric(x, y, w, h, title, query, column):
    vis(x, y, w, h, {"type": "metric", "title": title, "data_source": esql(query),
                     "metrics": [{"type": "primary", "column": column, "label": title}]})


def xy(x, y, w, h, title, query, kind, xcol, ycols, breakdown=None):
    layer = {"type": kind, "data_source": esql(query), "x": {"column": xcol}, "y": [{"column": c} for c in ycols]}
    if breakdown:
        layer["breakdown_by"] = {"column": breakdown}
    vis(x, y, w, h, {"type": "xy", "title": title, "layers": [layer]})


def table(x, y, w, h, title, query, rows, metrics):
    vis(x, y, w, h, {"type": "data_table", "title": title, "data_source": esql(query),
                     "rows": [{"column": c} for c in rows], "metrics": [{"column": c} for c in metrics]})


# ---- layout -------------------------------------------------------------------------------------
y = 0
md(y, 4, CFG["intro"])
y += 4

# 1. Audience: who is using the app
md(y, 2, "### Audience")
y += 2
kpis = [
    ("Installs", f"{BASE} | STATS installs = COUNT_DISTINCT({INSTALL})", "installs"),
    ("Sessions", f"{BASE} AND {WITH_SESSION} | STATS sessions = COUNT_DISTINCT({SESSION})", "sessions"),
    ("Sessions per install", f"{BASE} AND {WITH_SESSION} | STATS s = COUNT_DISTINCT({SESSION}), i = COUNT_DISTINCT({INSTALL}) | EVAL per_install = ROUND(1.0 * s / i, 2) | KEEP per_install", "per_install"),
    ("Crash-free sessions %", f"{BASE} AND {WITH_SESSION} | STATS sessions = COUNT_DISTINCT({SESSION}), crashed = COUNT_DISTINCT({SESSION}) WHERE {CRASH} | EVAL crash_free = ROUND(100.0 * (sessions - crashed) / sessions, 1) | KEEP crash_free", "crash_free"),
    ("Crashes", f"{BASE} | STATS crashes = COUNT(*) WHERE {CRASH}", "crashes"),
    ("ANRs", f"{BASE} | STATS anrs = COUNT(*) WHERE {ANR}", "anrs"),
]
for i, (t, q, c) in enumerate(kpis):
    metric(i * 8, y, 8, 6, t, q, c)
y += 6
xy(0, y, 20, 12, "Installs over time",
   f"{BASE} | STATS installs = COUNT_DISTINCT({INSTALL}) BY bucket = {BUCKET}", "bar", "bucket", ["installs"])
table(20, y, 28, 12, "Installs by service, app version, OS and device",
      f"{BASE} | STATS installs = COUNT_DISTINCT({INSTALL}), sessions = COUNT_DISTINCT({SESSION}) BY service = {SERVICE}, version = {VERSION}, os = {OSV}, device = {DEVICE} | SORT installs DESC",
      ["service", "version", "os", "device"], ["installs", "sessions"])
y += 12

# 2. Sessions
md(y, 2, "### Sessions")
y += 2
xy(0, y, 20, 12, "Sessions over time",
   f"{BASE} AND {WITH_SESSION} | STATS sessions = COUNT_DISTINCT({SESSION}) BY bucket = {BUCKET}", "bar", "bucket", ["sessions"])
table(20, y, 28, 12, "Session length and depth",
      f'{BASE} AND {WITH_SESSION} | STATS started = MIN(@timestamp), ended = MAX(@timestamp), signals = COUNT(*), screens = COUNT_DISTINCT({SCREEN}) BY {SESSION} | EVAL seconds = DATE_DIFF("seconds", started, ended) | STATS sessions = COUNT(*), p50_seconds = ROUND(PERCENTILE(seconds, 50), 0), p95_seconds = ROUND(PERCENTILE(seconds, 95), 0), avg_screens = ROUND(AVG(screens), 1), avg_signals = ROUND(AVG(signals), 1) | EVAL scope = "all sessions" | KEEP scope, sessions, p50_seconds, p95_seconds, avg_screens, avg_signals',
      ["scope"], ["sessions", "p50_seconds", "p95_seconds", "avg_screens", "avg_signals"])
y += 12

# 3. Quality: crashes and ANRs
md(y, 2, "### Quality: crashes and ANRs")
y += 2
xy(0, y, 20, 12, "Crashes and ANRs over time",
   f'{BASE} AND ({CRASH} OR {ANR}) | STATS events = COUNT(*) BY bucket = {BUCKET}, kind = event_name', "bar_stacked", "bucket", ["events"], "kind")
table(20, y, 28, 12, "Top crashes (by affected installs)",
      f"{BASE} AND {CRASH} | STATS crashes = COUNT(*), sessions = COUNT_DISTINCT({SESSION}), installs = COUNT_DISTINCT({INSTALL}) BY crash = attributes.exception.message | SORT installs DESC, crashes DESC | LIMIT 10",
      ["crash"], ["crashes", "sessions", "installs"])
y += 12

# 4. Performance
md(y, 2, "### Performance")
y += 2
table(0, y, 16, 12, "Cold start (ms)",
      f'{BASE} AND name == "AppStart" AND attributes.start.type == "cold" | STATS starts = COUNT(*), p50 = ROUND(PERCENTILE(duration, 50) / 1000000.0, 0), p95 = ROUND(PERCENTILE(duration, 95) / 1000000.0, 0) BY version = {VERSION}',
      ["version"], ["starts", "p50", "p95"])
xy(16, y, 16, 12, "Slow frames (jank) over time",
   f'{BASE} AND event_name == "app.jank" | STATS jank_events = COUNT(*) BY bucket = {BUCKET}', "bar", "bucket", ["jank_events"])
table(32, y, 16, 12, "HTTP calls from the app (ms)",
      f'{BASE} AND kind == "Client" | STATS calls = COUNT(*), p95 = ROUND(PERCENTILE(duration, 95) / 1000000.0, 0) BY call = name | SORT calls DESC | LIMIT 10',
      ["call"], ["calls", "p95"])
y += 12

# 5. Engagement
md(y, 2, "### Engagement")
y += 2
table(0, y, 24, 12, "Top screens",
      f"{BASE} AND {SCREEN} IS NOT NULL AND {SCREEN} != \"unknown\" | STATS signals = COUNT(*), sessions = COUNT_DISTINCT({SESSION}) BY screen = {SCREEN} | SORT signals DESC | LIMIT 10",
      ["screen"], ["signals", "sessions"])
xy(24, y, 24, 12, "What the SDK reports (events and spans by name)",
   f"{BASE} | STATS signals = COUNT(*) BY signal = COALESCE(event_name, name) | SORT signals DESC | LIMIT 15",
   "bar_horizontal", "signal", ["signals"])
y += 12

# 6. Correlation: sessions with a crash vs without, and where crashes cluster
md(y, 2, "### Correlation")
y += 2
table(0, y, 24, 12, "Sessions with a crash vs without",
      f'{BASE} AND {WITH_SESSION} | STATS crashed = COUNT(*) WHERE {CRASH}, jank = COUNT(*) WHERE event_name == "app.jank", signals = COUNT(*), started = MIN(@timestamp), ended = MAX(@timestamp) BY {SESSION} | EVAL had_crash = crashed > 0, seconds = DATE_DIFF("seconds", started, ended) | STATS sessions = COUNT(*), avg_seconds = ROUND(AVG(seconds), 1), avg_signals = ROUND(AVG(signals), 1), avg_jank = ROUND(AVG(jank), 2) BY had_crash',
      ["had_crash"], ["sessions", "avg_seconds", "avg_signals", "avg_jank"])
table(24, y, 24, 12, "Crash rate by app version, OS and device",
      f"{BASE} AND {WITH_SESSION} | STATS sessions = COUNT_DISTINCT({SESSION}), crashed = COUNT_DISTINCT({SESSION}) WHERE {CRASH} BY version = {VERSION}, os = {OSV}, device = {DEVICE} | EVAL crash_rate_pct = ROUND(100.0 * crashed / sessions, 1) | SORT crash_rate_pct DESC",
      ["version", "os", "device"], ["sessions", "crashed", "crash_rate_pct"])
y += 12

body = {
    "title": CFG["title"],
    "description": f"Play Console / Firebase style analytics for the {FLAVOR} Android SDK: audience, sessions, quality, performance, engagement, correlation.",
    "time_range": {"from": "now-6h", "to": "now"},
    "refresh_interval": {"pause": True, "value": 60000},
    "tags": ["mobile", FLAVOR, "analytics"],
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
