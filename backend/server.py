#!/usr/bin/env python3
"""Task API for the mobile telemetry demo, plus a local telemetry sink.

Stdlib only (Python 3.9+). In-memory state, resets on restart.

  python3 server.py [--port 8080] [--host 127.0.0.1]

App API
  GET    /tasks?user=U              list
  POST   /tasks                     {user,title,due?}
  POST   /tasks/<id>/complete?user=U
  DELETE /tasks/<id>?user=U
  GET    /search?user=U&q=Q
  GET    /sync?user=U               full task list in sync format (can drift, see chaos)
  GET    /stats?user=U

Chaos (change server behaviour while the apps run)
  GET  /admin/chaos
  POST /admin/chaos  {"latency_ms":60,"jitter_ms":40,"error_pct":0,"slow_pct":0,"slow_ms":3000,"drift_pct":0}
    error_pct  % of requests answered 500
    slow_pct   % of requests delayed by slow_ms (a slow dependency)
    drift_pct  % of /sync tasks whose `due` changes from string to object, simulating a
               backend release that breaks older clients

Local sink (so you can verify what the apps send without a cluster)
  POST /sink/<index>/_bulk   Elasticsearch bulk, counts docs per service.name
  POST /sink/v1/{traces,logs,metrics}   OTLP/HTTP, counts requests and bytes
  GET  /admin/sink           counters
  GET  /admin/docs?event=app.hang&limit=20   recent bulk docs (bare mode), newest first
  GET  /admin/stats          API request counters, incl. how many carried a W3C traceparent

Tracing (optional; needs `pip install opentelemetry-sdk opentelemetry-exporter-otlp-proto-http`, see .venv)
  python3 server.py --otlp http://localhost:14318      or   OTEL_EXPORTER_OTLP_ENDPOINT=...
  One SERVER span per API request ("GET /sync"), continuing the caller's W3C traceparent, with child spans:
    chaos.delay    the injected latency (latency_ms + jitter, plus slow_ms when slow_pct fires)
    store.<op>     the in-memory work (list, create, complete, delete, search, sync, stats)
  Without the flag the server stays stdlib-only and emits nothing.
"""
import argparse
import collections
import contextlib
import json
import os
import random
import threading
import time
import uuid
from collections import Counter, defaultdict
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

LOCK = threading.Lock()
TASKS = defaultdict(dict)  # user -> id -> task
CHAOS = {"latency_ms": 60, "jitter_ms": 40, "error_pct": 0, "slow_pct": 0, "slow_ms": 3000, "drift_pct": 0}
API = Counter()
RECENT = collections.deque(maxlen=5000)
SINK = {"bulk_docs": Counter(), "bulk_requests": 0, "otlp_requests": Counter(), "otlp_bytes": Counter()}


TRACER = None  # set by setup_tracing()


def setup_tracing(endpoint):
    """Export spans over OTLP/HTTP to `endpoint` (a collector base URL, e.g. http://localhost:14318)."""
    global TRACER
    from opentelemetry import trace
    from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
    from opentelemetry.sdk.resources import Resource
    from opentelemetry.sdk.trace import TracerProvider
    from opentelemetry.sdk.trace.export import BatchSpanProcessor

    provider = TracerProvider(resource=Resource.create({"service.name": "mobile-demo-backend", "service.version": "1.0"}))
    provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter(endpoint=endpoint.rstrip("/") + "/v1/traces")))
    trace.set_tracer_provider(provider)
    TRACER = trace.get_tracer("mobile-demo-backend")
    return provider


def span(name, **attrs):
    """Child span of the current request span; a no-op when tracing is off."""
    if TRACER is None:
        return contextlib.nullcontext()
    return TRACER.start_as_current_span(name, attributes=attrs)


def now():
    return int(time.time() * 1000)


def _route_name(method, parts):
    """Low-cardinality transaction name: 'POST /tasks/{id}/complete'."""
    if len(parts) >= 2 and parts[0] == "tasks":
        parts = ["tasks", "{id}"] + parts[2:]
    return f"{method} /{'/'.join(parts)}"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quiet; use /admin/stats
        pass

    # ---- helpers
    def _json(self, code, body):
        self._status = code
        data = json.dumps(body).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _chaos_gate(self):
        """Apply latency/errors. Returns True if the request was already answered."""
        c = CHAOS
        delay = c["latency_ms"] + random.uniform(0, c["jitter_ms"])
        slow = random.random() * 100 < c["slow_pct"]
        if slow:
            delay += c["slow_ms"]
        with span("chaos.delay", **{"chaos.delay_ms": round(delay, 1), "chaos.slow": slow}):
            time.sleep(delay / 1000)
        if random.random() * 100 < c["error_pct"]:
            self._json(500, {"error": "injected failure"})
            return True
        return False

    # ---- routing
    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_DELETE(self):
        self._route("DELETE")

    def _route(self, method):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        parts = [p for p in u.path.split("/") if p]

        if parts[:1] == ["sink"]:
            return self._sink(method, parts[1:])
        if parts[:1] == ["admin"]:
            return self._admin(method, parts[1:])
        if TRACER is None:
            return self._api(method, u, q, parts)

        from opentelemetry import propagate, trace
        ctx = propagate.extract({k.lower(): v for k, v in self.headers.items()})
        route = _route_name(method, parts)
        self._status = 0
        with TRACER.start_as_current_span(
            route, context=ctx, kind=trace.SpanKind.SERVER,
            attributes={"http.request.method": method, "url.path": u.path, "http.route": route.split(" ", 1)[1],
                        "user.id": q.get("user", "anon")},
        ) as sp:
            try:
                self._api(method, u, q, parts)
            finally:
                sp.set_attribute("http.response.status_code", self._status)
                if self._status >= 500:
                    sp.set_status(trace.Status(trace.StatusCode.ERROR))

    def _api(self, method, u, q, parts):
        with LOCK:
            API[f"{method} /{parts[0] if parts else ''}"] += 1
            if self.headers.get("traceparent"):
                API["with_traceparent"] += 1
            API["total"] += 1
        if self._chaos_gate():
            return

        user = q.get("user", "anon")
        try:
            if parts == ["tasks"] and method == "GET":
                with span("store.list"):
                    out = list(TASKS[user].values())
                return self._json(200, {"tasks": out})
            if parts == ["tasks"] and method == "POST":
                b = json.loads(self._body() or b"{}")
                user = b.get("user", user)
                t = {"id": uuid.uuid4().hex[:8], "title": b.get("title", ""), "done": False, "created": now(), "due": b.get("due")}
                with span("store.create"):
                    TASKS[user][t["id"]] = t
                return self._json(201, t)
            if len(parts) == 3 and parts[0] == "tasks" and parts[2] == "complete" and method == "POST":
                with span("store.complete"):
                    t = TASKS[user].get(parts[1])
                    if t:
                        t["done"] = True
                if not t:
                    return self._json(404, {"error": "no such task"})
                return self._json(200, t)
            if len(parts) == 2 and parts[0] == "tasks" and method == "DELETE":
                with span("store.delete"):
                    TASKS[user].pop(parts[1], None)
                return self._json(200, {"ok": True})
            if parts == ["search"] and method == "GET":
                s = q.get("q", "").lower()
                with span("store.search", **{"search.scanned": len(TASKS[user])}):
                    hits = [t for t in TASKS[user].values() if s in t["title"].lower()]
                return self._json(200, {"tasks": hits})
            if parts == ["sync"] and method == "GET":
                out = []
                with span("store.sync", **{"sync.tasks": len(TASKS[user])}):
                    for t in TASKS[user].values():
                        t = dict(t)
                        # Schema drift: `due` becomes {"at": <ms>, "tz": "UTC"} instead of a string/null.
                        if random.random() * 100 < CHAOS["drift_pct"]:
                            t["due"] = {"at": now(), "tz": "UTC"}
                        out.append(t)
                return self._json(200, {"tasks": out, "server_time": now()})
            if parts == ["stats"] and method == "GET":
                with span("store.stats"):
                    ts = list(TASKS[user].values())
                    summary = {"total": len(ts), "done": sum(t["done"] for t in ts)}
                return self._json(200, summary)
        except Exception as e:  # noqa: BLE001
            return self._json(400, {"error": str(e)})
        self._json(404, {"error": "not found"})

    def _admin(self, method, parts):
        if parts == ["chaos"]:
            if method == "POST":
                CHAOS.update(json.loads(self._body() or b"{}"))
            return self._json(200, CHAOS)
        if parts == ["sink"]:
            return self._json(200, {k: dict(v) if isinstance(v, Counter) else v for k, v in SINK.items()})
        if parts == ["docs"]:
            q = {k: v[0] for k, v in parse_qs(urlparse(self.path).query).items()}
            with LOCK:
                docs = [d for d in reversed(RECENT) if not q.get("event") or d.get("event.name") == q["event"]]
            return self._json(200, docs[: int(q.get("limit", 20))])
        if parts == ["stats"]:
            with LOCK:
                return self._json(200, {"api": dict(API), "users": len(TASKS), "tasks": sum(len(v) for v in TASKS.values())})
        if parts == ["reset"] and method == "POST":
            with LOCK:
                TASKS.clear(); API.clear()
                SINK["bulk_docs"].clear(); SINK["otlp_requests"].clear(); SINK["otlp_bytes"].clear(); SINK["bulk_requests"] = 0; RECENT.clear()
            return self._json(200, {"ok": True})
        self._json(404, {"error": "not found"})

    def _sink(self, method, parts):
        body = self._body()
        with LOCK:
            if parts and parts[-1] == "_bulk":
                SINK["bulk_requests"] += 1
                lines = body.decode("utf-8", "replace").splitlines()
                for line in lines[1::2]:
                    try:
                        d = json.loads(line)
                        RECENT.append(d)
                        SINK["bulk_docs"][d.get("service.name", "?")] += 1
                    except ValueError:
                        SINK["bulk_docs"]["?unparseable"] += 1
                return self._json(200, {"errors": False, "items": []})
            if len(parts) == 2 and parts[0] == "v1":
                SINK["otlp_requests"][parts[1]] += 1
                SINK["otlp_bytes"][parts[1]] += len(body)
                return self._json(200, {})
        self._json(404, {"error": "unknown sink path"})


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--host", default="127.0.0.1", help="bind address; an emulator reaches 127.0.0.1 via 10.0.2.2. Use 0.0.0.0 only on a trusted network (no authentication)")
    ap.add_argument("--otlp", default=os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT"), help="OTLP/HTTP base URL; enables tracing")
    args = ap.parse_args()
    provider = setup_tracing(args.otlp) if args.otlp else None
    srv = ThreadingHTTPServer((args.host, args.port), Handler)
    srv.daemon_threads = True
    print(f"task API + sink on {args.host}:{args.port}")
    try:
        srv.serve_forever()
    finally:
        if provider:
            provider.shutdown()  # flush pending spans
