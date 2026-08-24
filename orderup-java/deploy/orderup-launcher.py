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
import socket
import subprocess
from urllib.parse import urlsplit

SECRET        = os.environ.get("LAUNCHER_SECRET", "")
LISTEN_PORT   = int(os.environ.get("LAUNCHER_PORT", "8080"))
UPSTREAM_HOST = os.environ.get("UPSTREAM_HOST", "127.0.0.1")
UPSTREAM_PORT = int(os.environ.get("UPSTREAM_PORT", "8090"))
UID           = os.getuid()

# Guardrail: only these labels can be controlled, so a leaked secret cannot
# be used to poke arbitrary launchd services.
ALLOWED_LABELS = {
    "chartink":      "com.orderup.chartink",
    "trading":       "com.orderup.trading",
    "ngrok":         "com.orderup.ngrok",
    "launcher-self": "com.orderup.launcher",
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
    return "gui/%d/%s" % (UID, label)


def _status(label):
    rc, out = _run(["launchctl", "print", _target(label)])
    if rc != 0:
        return {"label": label, "loaded": False, "raw": out.splitlines()[:5]}
    info = {"label": label, "loaded": True}
    for line in out.splitlines():
        line = line.strip()
        for key in ("pid =", "state =", "last exit code ="):
            if line.startswith(key):
                k = key.rstrip(" =").replace(" ", "_")
                info[k] = line.split("=", 1)[1].strip()
    return info


def _act(label, action):
    if action == "start":
        return _run(["launchctl", "kickstart", _target(label)])
    if action == "stop":
        # SIGTERM leaves the job loaded so KeepAlive brings it back on the
        # next /start (no launchctl load surgery needed).
        return _run(["launchctl", "kill", "SIGTERM", _target(label)])
    if action == "restart":
        return _run(["launchctl", "kickstart", "-k", _target(label)])
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
        label = ALLOWED_LABELS.get(app)
        if not label:
            return self._json(400, {"error": "unknown app",
                                    "allowed": sorted(ALLOWED_LABELS.keys())})
        if action == "status":
            return self._json(200, _status(label))
        if self.command != "POST":
            return self._json(405, {"error": "use POST for start/stop/restart"})
        if action not in ("start", "stop", "restart"):
            return self._json(400, {"error": "unknown action",
                                    "allowed": ["start", "stop", "restart", "status"]})
        rc, out = _act(label, action)
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

