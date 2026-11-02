#!/usr/bin/env python3
"""Wave load driver: many short app sessions, in parallel, across every device you have running.

Each device loops: launch the app with autopilot args (a fresh user.id and persona each time),
wait for it to exit, record how it ended, repeat. Crashes and ANRs kill the process and the next
launch starts a new session, which is what real users do. Inside each launch `--vusers` adds
in-process virtual users for request volume.

  python3 loadtest/wave.py --mode edot --minutes 10 --faults mild --vusers 20

Devices: every Android emulator/device that `adb devices` lists.
Start more with:  emulator -avd tele-37 -read-only -port 5556
"""
import argparse
import json
import os
import shutil
import random
import subprocess
import threading
import time
from collections import Counter

ADB = shutil.which("adb") or os.path.join(os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk")), "platform-tools", "adb")
PKG = "dev.edotdemo.telemetry.{mode}"

stats = Counter()
lock = threading.Lock()


def sh(*cmd, timeout=None, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, timeout=timeout, **kw)


def android_devices():
    out = sh(ADB, "devices").stdout.splitlines()[1:]
    return [l.split()[0] for l in out if l.strip().endswith("device")]


def pick_persona(mix):
    names, weights = zip(*mix)
    return random.choices(names, weights)[0]


def run_android(serial, a, user, persona):
    pkg = PKG.format(mode=a.mode.lower())
    sh(ADB, "-s", serial, "shell", "am", "force-stop", pkg)
    if random.random() < a.new_install_prob:
        # Clearing app data is a fresh install to the SDKs (new app.installation.id), a new user in the audience panels.
        sh(ADB, "-s", serial, "shell", "pm", "clear", pkg)
    crash_at = random.uniform(5, a.session_seconds * 0.7) if random.random() < a.crash_prob else None
    t0 = time.time()
    sh(ADB, "-s", serial, "shell", "am", "start", "-n", f"{pkg}/dev.edotdemo.MainActivity",
       "--ez", "autopilot", "true", "--es", "user", user, "--es", "persona", persona,
       "--es", "faults", a.faults, "--ei", "duration", str(a.session_seconds),
       "--ei", "vusers", str(a.vusers), "--ef", "speed", str(a.speed),
       "--es", "backend", a.android_backend)
    time.sleep(2)
    while time.time() - t0 < a.session_seconds + 30:
        # Input events are what let Android declare an ANR when the main thread is blocked.
        sh(ADB, "-s", serial, "shell", "input", "tap", "540", "1200")
        if not sh(ADB, "-s", serial, "shell", "pidof", pkg).stdout.strip():
            break
        if crash_at and time.time() - t0 > crash_at:
            sh(ADB, "-s", serial, "shell", "am", "crash", pkg)  # uncaught exception in the app process
            crash_at = None
        time.sleep(1)
    sh(ADB, "-s", serial, "shell", "am", "force-stop", pkg)
    return time.time() - t0


def worker(platform, dev, idx, a, deadline):
    n = 0
    while time.time() < deadline:
        n += 1
        user = f"u-{a.wave}-{platform[0]}{idx}-{n}"
        persona = pick_persona(a.mix)
        took = run_android(dev, a, user, persona)
        # A clean autopilot run lasts ~session_seconds. Much shorter = crash, ANR kill or an impatient user abandoning.
        died = took < a.session_seconds * 0.8
        with lock:
            stats[f"{platform}.launches"] += 1
            stats[f"{platform}.ended_early" if died else f"{platform}.clean"] += 1
            stats[f"persona.{persona}"] += 1
        print(f"[{platform}:{dev[-6:]}] {user} {persona:9s} {took:5.1f}s {'ENDED EARLY' if died else 'ok'}", flush=True)
        time.sleep(random.uniform(0.5, 3))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--mode", choices=["bare", "otel", "edot", "edotFull", "edotFullTraced"], required=True)
    ap.add_argument("--minutes", type=float, default=5)
    ap.add_argument("--session-seconds", type=int, default=45)
    ap.add_argument("--vusers", type=int, default=10, help="in-process virtual users per launch")
    ap.add_argument("--faults", choices=["none", "mild", "chaos"], default="mild")
    ap.add_argument("--speed", type=float, default=2.0, help="think-time divisor")
    ap.add_argument("--mix", default="finisher:5,planner:2,browser:2,impatient:1")
    ap.add_argument("--new-install-prob", type=float, default=0.0,
                    help="Android: chance a session starts from cleared app data (a new install and user); the rest are returning users")
    ap.add_argument("--crash-prob", type=float, default=0.0, help="Android: chance a session is crashed part-way with `am crash`")
    ap.add_argument("--platforms", default="android")
    ap.add_argument("--android-backend", default="http://10.0.2.2:8080")
    ap.add_argument("--wave", default=time.strftime("%H%M"), help="prefix for user ids")
    a = ap.parse_args()
    a.mix = [(k, int(v)) for k, v in (x.split(":") for x in a.mix.split(","))]

    devs = []
    if "android" in a.platforms:
        devs += [("android", d) for d in android_devices()]
    if not devs:
        raise SystemExit("no devices: start an emulator first")
    print(f"mode={a.mode} faults={a.faults} devices={len(devs)} vusers/launch={a.vusers} for {a.minutes} min")

    deadline = time.time() + a.minutes * 60
    ts = [threading.Thread(target=worker, args=(p, d, i, a, deadline)) for i, (p, d) in enumerate(devs)]
    [t.start() for t in ts]
    [t.join() for t in ts]
    print("\nsummary:", json.dumps(dict(sorted(stats.items())), indent=2))


if __name__ == "__main__":
    main()
