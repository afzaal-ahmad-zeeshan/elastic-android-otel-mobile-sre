# Design: what the app does, what we track, what breaks

## The app: "Tasks"

A to-do app. The user's objective is **to capture tasks and get them done**. Everything we
track hangs off that.

Screens: **List** (home: add, complete, delete, search), **Stats** (completion summary), and a
background **Sync** with the backend. Backend: `backend/server.py` (`GET/POST /tasks`,
`/search`, `/sync`, `/stats`; see its docstring).

## Engagement model

Funnel (per user, per session):

```
app.launch -> screen.view(list) -> task.create -> task.complete   (core value)
                                \-> task.search / screen.view(stats) / sync   (secondary)
```

| Metric | Definition | Source events |
|---|---|---|
| Activation rate | sessions with >=1 `task.create` / sessions | `app.launch`, `task.create` |
| Core completion rate | sessions with >=1 `task.complete` / sessions | `task.complete` |
| Tasks completed per active user | count(`task.complete`) by `user.id` | `task.complete` |
| Feature reach | users who opened Stats or used Search | `screen.view`, `task.search` |
| Action latency p95 | p95 duration by action name | action spans |
| Sync success rate | `sync` with `outcome=success` / all | `sync` |
| Session abandonment | sessions that end right after a slow/failed action | `session.end` reason + last action |

All of these are computed from the same **action spans**, so they work in every telemetry mode.

> **Android update:** the Android `otel` and `edot` flavors no longer emit action/event spans. Their facade methods
> (`action`, `event`) pass through, so those two flavors show only what the library collects by itself (out-of-the-box
> comparison). The action/event model below still applies to `bare` on Android.

## Telemetry facade (identical shape on both platforms)

Every flavor/target implements `AppTelemetry`:

- `start()`; `callFactory(client)` / `session` (network instrumentation, as before)
- `action(name, attrs) { ... }` runs a block, records one span/doc with duration, `outcome`
  (`success` | `failure`) and the exception if it threw
- `event(name, attrs)` an instant occurrence (zero-duration span / doc)

Common attributes on every action/event (set by the facade, not by call sites):

| Attribute | Meaning |
|---|---|
| `user.id` | stable per simulated user (`u-<n>`, or `vu-<n>` for in-process virtual users) |
| `app.persona` | `planner`, `finisher`, `browser`, `impatient`, or `manual` |
| `app.fault_profile` | `none`, `mild`, `chaos` |
| `app.virtual` | `true` for in-process virtual users |
| `telemetry.mode` | `bare`, `otel`, `edot` |

Action names: `app.launch`, `screen.view` (`screen`=`list`/`stats`), `task.create`, `task.complete`,
`task.delete`, `task.search`, `sync`. Events: `app.foreground`, `app.background`, `session.end`
(`reason`=`completed`/`abandoned`), `app.hang` (`duration_ms`, emitted when the main thread recovers; a hang that ends in an ANR kill is reported by `app.anr_previous_run` instead), `app.anr_previous_run` (Android only).

## Where things break (and how we see it)

Faults are real bugs triggered by realistic conditions, not a "crash" button. The manual
buttons remain under a Debug section for sanity checks.

| # | Where | Failure | Trigger | What the user experiences | How we observe it |
|---|---|---|---|---|---|
| 1 | **Stats screen** compute | **ANR / hang**: completion summary is computed on the main thread; blocks 8 s | probability per Stats open: `anrRate` (mild 3 %, chaos 15 %) | UI frozen; Android shows "not responding" if they tap | `app.hang` event (watchdog thread, all modes); Android also `app.anr_previous_run` on next launch from `ApplicationExitInfo`; the enclosing `screen.view` span is long; the session often ends in `abandoned` |
| 2 | **Sync** response parsing | **Crash**: client assumes `due` is a string; backend release changes it to an object (`drift_pct` via `/admin/chaos`) | backend chaos, not the app | app dies mid-sync | EDOT: crash event on next launch with session id. bare/otel: no crash event. Detect indirectly: session with no `app.background`/`session.end`, `sync` action with no end |
| 3 | **Create task** helper | **Crash**: notification-preview helper truncates a title by UTF-16 / byte index and fails on certain emoji | probability per create that title has emoji: `emojiRate` (mild 2 %, chaos 10 %) | app dies right after tapping Add | same as #2 |
| 4 | **Backend** slow / errors | slow `sync`/`create`, 500s | `/admin/chaos` `slow_pct`, `error_pct` | spinners, failed actions | action latency p95, `outcome=failure`, network spans |

The comparison: with identical faults, what can each mode tell you?

- **bare**: HTTP failures it wrapped, the hand-rolled `app.hang`. Crashes show only as missing session ends.
- **otel**: adds trace context to the backend (see the slow request on both sides) but still no crash/session.
- **edot**: adds crash events, sessions, and disk buffering (events from a crashed run still arrive).

## Simulating many users

One app process cannot be many phones. We use three layers, each honest about what it is:

1. **Autopilot (per device).** Launch with `autopilot` args and the app drives itself through a
   persona script with human-ish think times. Real code path, real network, real telemetry.
2. **Virtual users (inside one process).** `--vusers N` starts N lightweight users doing
   network-level actions (create/complete/search/sync) concurrently, each with its own
   `user.id`. Scales request volume and backend load to hundreds. They share the process's
   session and cannot crash independently (a crash kills them all).
3. **Waves (across launches and devices).** `loadtest/wave.py` relaunches the app in a loop
   (each launch = a fresh user and session, with crashes/ANRs killing and restarting it),
   across as many emulators as you start. 4 devices x 1 launch/min ~ 240
   sessions/hour, each with a distinct `user.id`, with real crash/ANR behaviour.

Personas (autopilot):

| Persona | Behaviour |
|---|---|
| `planner` | creates many tasks, completes few, opens Stats |
| `finisher` | creates a few, completes most, syncs |
| `browser` | searches, opens Stats repeatedly |
| `impatient` | like finisher but quits (`session.end` `abandoned`) if any action takes > 3 s |

Launch arguments (Android intent extras `--es`):

`autopilot` (true/false), `user` (id), `persona`, `faults` (`none|mild|chaos`), `duration` (seconds,
then the app backgrounds itself and exits normally), `vusers` (int), `speed` (think-time divisor,
default 1), `backend` (override base URL).
