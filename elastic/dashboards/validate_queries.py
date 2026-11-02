#!/usr/bin/env python3
"""Run every ES|QL query of a dashboard against Elasticsearch (with the ?_tstart/?_tend params Kibana supplies).

  ELASTIC_API_KEY=... KIBANA_URL=... ES_URL=... python3 elastic/dashboards/validate_queries.py mobile-sre-bare
"""
import datetime
import json
import os
import sys
import urllib.error
import urllib.request

KEY, KB, ES = os.environ["ELASTIC_API_KEY"], os.environ["KIBANA_URL"].rstrip("/"), os.environ["ES_URL"].rstrip("/")


def call(url, body=None):
    req = urllib.request.Request(url, method="POST" if body is not None else "GET", data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "ApiKey " + KEY, "kbn-xsrf": "true", "Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(req, timeout=90))
    except urllib.error.HTTPError as e:
        return {"error": e.read().decode()[:400]}


panels = call(f"{KB}/api/dashboards/{sys.argv[1]}")["data"]["panels"]
now = datetime.datetime.utcnow()
fmt = "%Y-%m-%dT%H:%M:%S.000Z"
params = [{"_tstart": (now - datetime.timedelta(hours=int(os.environ.get("WINDOW_HOURS", "1")))).strftime(fmt)}, {"_tend": (now + datetime.timedelta(minutes=1)).strftime(fmt)}]
bad = 0
for p in panels:
    c = p["config"]
    qs = ([c["data_source"]["query"]] if "data_source" in c else []) + [l["data_source"]["query"] for l in c.get("layers", [])]
    for q in qs:
        out = call(ES + "/_query", {"query": q, "params": params})
        title = c.get("title", "?")
        if "error" in out:
            bad += 1
            print("ERR", title, "->", out["error"][:300])
        else:
            first = out["values"][0] if out["values"] else None
            print("ok ", title[:52].ljust(52), "rows=%-3d" % len(out["values"]), "first=", first)
print("panels:", len(panels), "query errors:", bad)
