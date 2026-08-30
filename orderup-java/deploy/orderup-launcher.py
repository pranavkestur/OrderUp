#!/usr/bin/env python3
"""orderup-launcher: lifecycle control endpoints + reverse proxy to chartink app.

Runs under com.orderup.launcher (launchd). Exposes on port 8080 (the public
ngrok entry point):

    POST /lifecycle/{secret}/{app}/{start|stop|restart}
    GET  /lifecycle/{secret}/{app}/status
    GET  /health

and reverse-proxies every other path to the chartink Spring Boot app on
127.0.0.1:8090. Because the launcher lives outside the chartink JVM, the
lifecycle endpoints stay reachable when chartink is down -- which is the
whole point (you can bring the app back from anywhere).

Free ngrok tier gives only one static domain, so path routing on a single
tunnel is the workaround for a second control channel.
"""
import http.server
import http.client
import json
import os
import platform
import socket
import subprocess
from urllib.parse import urlsplit

SECRET        = os.environ.get("LAUNCHER_SECRET", "")
LISTEN_PORT   = int(os.environ.get("LAUNCHER_PORT", "8080"))
UPSTREAM_HOST = os.environ.get("UPSTREAM_HOST", "127.0.0.1")
UPSTREAM_PORT = int(os.environ.get("UPSTREAM_PORT", "8090"))
UID           = os.getuid()

# Platform detection — macOS uses launchctl + plists, Linux uses systemctl
# --user + .service unit names. Everything else in this file is portable.
IS_MAC = platform.system() == "Darwin"

# Guardrail: only these labels can be controlled, so a leaked secret cannot
# be used to poke arbitrary services. Value is (label, unit_or_plist_path):
# on macOS the second element is the .plist path (needed by launchctl bootstrap),
# on Linux it is the systemd unit name (redundant with the first field but kept
# for symmetry — systemctl commands only need the unit name).
if IS_MAC:
    _LA = os.path.expanduser("~/Library/LaunchAgents")
    ALLOWED_LABELS = {
        "chartink":      ("com.orderup.chartink", "%s/com.orderup.chartink.plist" % _LA),
        "trading":       ("com.orderup.trading",  "%s/com.orderup.trading.plist"  % _LA),
        "ngrok":         ("com.orderup.ngrok",    "%s/com.orderup.ngrok.plist"    % _LA),
        "launcher-self": ("com.orderup.launcher", "%s/com.orderup.launcher.plist" % _LA),
    }
else:
    ALLOWED_LABELS = {
        "chartink":      ("orderup-chartink.service", "orderup-chartink.service"),
        "trading":       ("orderup-trading.service",  "orderup-trading.service"),
        "ngrok":         ("orderup-ngrok.service",    "orderup-ngrok.service"),
        "launcher-self": ("orderup-launcher.service", "orderup-launcher.service"),
    }

# RFC 7230 hop-by-hop headers + Host + Content-Length: we manage these
# ourselves, never blindly forward.
HOP_BY_HOP = {"connection", "keep-alive", "proxy-authenticate",
              "proxy-authorization", "te", "trailers",
              "transfer-encoding", "upgrade", "host", "content-length"}


def _run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=10)
    return p.returncode, (p.stdout + p.stderr).strip()


def _target(label):
    # On macOS the "domain-specifier" form is gui/<uid>/<label>. On Linux we
    # just use the systemd unit name — systemctl --user takes it directly.
    return "gui/%d/%s" % (UID, label) if IS_MAC else label


def _status(label):
    if IS_MAC:
        rc, out = _run(["launchctl", "print", _target(label)])
        if rc != 0:
            return {"label": label, "loaded": False, "state": "stopped"}
        info = {"label": label, "loaded": True}
        for line in out.splitlines():
            line = line.strip()
            for key in ("pid =", "state =", "last exit code ="):
                if line.startswith(key):
                    k = key.rstrip(" =").replace(" ", "_")
                    info[k] = line.split("=", 1)[1].strip()
        return info

    # Linux: systemctl show gives machine-readable key=value pairs. `Result`
    # (start-limit-hit, exit-code, etc.) plus ActiveState + MainPID cover the
    # same three fields the Mac branch surfaces.
    rc, out = _run(["systemctl", "--user", "show", label,
                    "--property=ActiveState,SubState,MainPID,Result,ExecMainStatus"])
    if rc != 0:
        return {"label": label, "loaded": False, "state": "stopped"}
    kv = {}
    for line in out.splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            kv[k.strip()] = v.strip()
    active = kv.get("ActiveState", "unknown")
    return {
        "label":          label,
        "loaded":         active in ("active", "activating"),
        "state":          "active" if active == "active" else "stopped",
        "pid":            kv.get("MainPID", "0"),
        "last_exit_code": kv.get("ExecMainStatus", "0"),
        "sub_state":      kv.get("SubState", ""),
        "last_result":    kv.get("Result", ""),
    }


def _act(entry, action):
    label, unit_or_plist = entry
    tgt = _target(label)
    if IS_MAC:
        domain = "gui/%d" % UID
        if action == "start":
            _run(["launchctl", "bootstrap", domain, unit_or_plist])
            return _run(["launchctl", "kickstart", tgt])
        if action == "stop":
            # bootout removes the job from the domain entirely — KeepAlive
            # cannot respawn what is not loaded.
            return _run(["launchctl", "bootout", tgt])
        if action == "restart":
            _run(["launchctl", "bootstrap", domain, unit_or_plist])
            return _run(["launchctl", "kickstart", "-k", tgt])
        return 2, "unknown action"

    # Linux (systemd user manager).
    if action == "start":
        return _run(["systemctl", "--user", "start", tgt])
    if action == "stop":
        # `stop` is the systemd equivalent of bootout: sends SIGTERM, waits
        # for the process to exit, and does NOT respawn (Restart= directives
        # only apply to abnormal exit, not to explicit stop).
        return _run(["systemctl", "--user", "stop", tgt])
    if action == "restart":
        return _run(["systemctl", "--user", "restart", tgt])
    return 2, "unknown action"


class H(http.server.BaseHTTPRequestHandler):
    server_version = "orderup-launcher/1.0"
    protocol_version = "HTTP/1.1"

    def _json(self, code, payload):
        body = json.dumps(payload, indent=2).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)

    def _handle(self):
        path = urlsplit(self.path).path
        if path == "/health":
            return self._json(200, {"ok": True, "app": "orderup-launcher"})
        if path.startswith("/lifecycle/"):
            return self._lifecycle()
        return self._proxy()

    def do_GET(self):     self._handle()
    def do_POST(self):    self._handle()
    def do_PUT(self):     self._handle()
    def do_DELETE(self):  self._handle()
    def do_PATCH(self):   self._handle()
    def do_HEAD(self):    self._handle()
    def do_OPTIONS(self): self._handle()

    def _lifecycle(self):
        parts = [p for p in urlsplit(self.path).path.split("/") if p]
        if len(parts) < 4:
            return self._json(404, {"error": "not found",
                                    "hint": "/lifecycle/{secret}/{app}/{action}"})
        if not SECRET or parts[1] != SECRET:
            return self._json(401, {"error": "bad or missing launcher secret"})
        app, action = parts[2], parts[3]
        entry = ALLOWED_LABELS.get(app)
        if not entry:
            return self._json(400, {"error": "unknown app",
                                    "allowed": sorted(ALLOWED_LABELS.keys())})
        label = entry[0]
        if action == "status":
            return self._json(200, _status(label))
        if self.command != "POST":
            return self._json(405, {"error": "use POST for start/stop/restart"})
        if action not in ("start", "stop", "restart"):
            return self._json(400, {"error": "unknown action",
                                    "allowed": ["start", "stop", "restart", "status"]})
        rc, out = _act(entry, action)
        return self._json(200 if rc == 0 else 500, {
            "app": app, "action": action, "rc": rc, "out": out,
            "status": _status(label),
        })

    def _proxy(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length > 0 else None

        fwd = {}
        for h, v in self.headers.items():
            if h.lower() in HOP_BY_HOP:
                continue
            fwd[h] = v
        xff = self.headers.get("X-Forwarded-For")
        peer = self.client_address[0]
        fwd["X-Forwarded-For"] = ("%s, %s" % (xff, peer)) if xff else peer
        fwd["X-Forwarded-Proto"] = "https"

        try:
            conn = http.client.HTTPConnection(UPSTREAM_HOST, UPSTREAM_PORT,
                                              timeout=30)
            conn.request(self.command, self.path, body=body, headers=fwd)
            resp = conn.getresponse()
            payload = resp.read()
        except (ConnectionRefusedError, socket.timeout, OSError) as e:
            return self._json(502, {
                "error": "upstream unreachable",
                "upstream": "%s:%d" % (UPSTREAM_HOST, UPSTREAM_PORT),
                "detail": str(e),
                "hint": ("chartink-app may be down. POST "
                         "/lifecycle/<secret>/chartink/start to bring it up."),
            })

        self.send_response(resp.status)
        for h, v in resp.getheaders():
            if h.lower() in HOP_BY_HOP:
                continue
            self.send_header(h, v)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        conn.close()

    def log_message(self, fmt, *args):
        print("[%s] %s" % (self.log_date_time_string(), fmt % args), flush=True)


def main():
    if not SECRET:
        print("FATAL: LAUNCHER_SECRET env var is empty.", flush=True)
        raise SystemExit(2)
    print("orderup-launcher listening on 0.0.0.0:%d -> %s:%d (controls: %s)" %
          (LISTEN_PORT, UPSTREAM_HOST, UPSTREAM_PORT,
           sorted(ALLOWED_LABELS.keys())), flush=True)
    http.server.ThreadingHTTPServer(("", LISTEN_PORT), H).serve_forever()


if __name__ == "__main__":
    main()

