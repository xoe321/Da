import hmac
import json
import re
import secrets
import socket
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


ERROR_RE = re.compile(r"(?i)\b(error|err|fatal|exception|traceback|failed|refused|denied)\b|[A-Za-z]+Error\b")
SERVER_OK_RE = re.compile(
    r"(?i)(application startup complete|uvicorn running on|server is running|listening on|ready to accept)"
)
TUNNEL_OK_RE = re.compile(r"(?i)(registered tunnel connection|quick tunnel has been created)")
DOMAIN_RE = re.compile(r"https://[a-z0-9-]+\.trycloudflare\.com")
SERVER_ID_RE = re.compile(r"[A-Za-z0-9_-]{1,80}")
DB_SUFFIXES = (".sqlite3", ".sqlite", ".db")
SECRET_VALUE_RE = re.compile(
    r"""(?i)(["']?(?:token|password|secret|api[_-]?key)["']?\s*[:=]\s*["']?)([^"'\s,;}]+)"""
)
_METRICS_LOCK = threading.Lock()
_METRICS_CACHE = {"sampled_at": 0.0, "data": None}
_TRAFFIC_SAMPLE = None
_LATENCY_SAMPLE_AT = 0.0
_LATENCY_SAMPLE_MS = None


def _redact(text, values=()):
    for value in values:
        value = str(value)
        if value:
            text = text.replace(value, "[REDACTED]")
    return SECRET_VALUE_RE.sub(lambda match: match.group(1) + "[REDACTED]", text)


def _read_text(path, limit=12000):
    try:
        with open(path, "rb") as stream:
            stream.seek(0, 2)
            size = stream.tell()
            stream.seek(max(0, size - limit))
            return stream.read(limit).decode("utf-8", errors="replace")
    except FileNotFoundError:
        return ""
    except OSError as exc:
        return "[Unable to read file: {}]".format(exc)


def _status(log_text, running, success_pattern):
    if not running:
        return "หยุดอยู่"
    lines = log_text.splitlines()
    error_index = max((i for i, line in enumerate(lines) if ERROR_RE.search(line)), default=-1)
    success_index = max((i for i, line in enumerate(lines) if success_pattern.search(line)), default=-1)
    if error_index > success_index:
        return "พบ Error"
    if success_index >= 0:
        return "ทำงานปกติ"
    return "กำลังเริ่ม"


def _device_metrics(context, root):
    global _TRAFFIC_SAMPLE, _LATENCY_SAMPLE_AT, _LATENCY_SAMPLE_MS

    now = time.monotonic()
    with _METRICS_LOCK:
        cached = _METRICS_CACHE["data"]
        if cached is not None and now - _METRICS_CACHE["sampled_at"] < 1:
            return dict(cached)

        errors = []
        metrics = {
            "wifiConnected": False,
            "wifiStatus": "ไม่พร้อมใช้งาน",
            "wifiRssiDbm": None,
            "wifiLinkSpeedMbps": None,
            "wifiRxBytesPerSecond": None,
            "wifiTxBytesPerSecond": None,
            "latencyMs": _LATENCY_SAMPLE_MS,
            "batteryPercent": None,
            "ramUsedBytes": None,
            "ramTotalBytes": None,
            "storageFreeBytes": None,
            "storageTotalBytes": None,
            "errors": errors,
        }

        interface = None
        try:
            from java import jclass

            connectivity = context.getSystemService("connectivity")
            network = connectivity.getActiveNetwork()
            capabilities = connectivity.getNetworkCapabilities(network) if network is not None else None
            network_capabilities = jclass("android.net.NetworkCapabilities")
            wifi_connected = bool(
                capabilities and capabilities.hasTransport(network_capabilities.TRANSPORT_WIFI)
            )
            metrics["wifiConnected"] = wifi_connected
            metrics["wifiStatus"] = "เชื่อมต่อแล้ว" if wifi_connected else "ไม่ได้เชื่อมต่อ Wi-Fi"
            if wifi_connected:
                wifi = context.getSystemService("wifi")
                info = wifi.getConnectionInfo()
                rssi = int(info.getRssi())
                link_speed = int(info.getLinkSpeed())
                metrics["wifiRssiDbm"] = rssi if -127 < rssi <= 0 else None
                metrics["wifiLinkSpeedMbps"] = link_speed if link_speed > 0 else None
                properties = connectivity.getLinkProperties(network)
                interface_name = properties.getInterfaceName() if properties is not None else None
                interface = str(interface_name) if interface_name is not None else None
        except Exception:
            errors.append("อ่านสถานะ Wi-Fi ไม่สำเร็จ")

        if interface:
            try:
                from java import jclass

                traffic = jclass("android.net.TrafficStats")
                received = int(traffic.getRxBytes(interface))
                sent = int(traffic.getTxBytes(interface))
                if received >= 0 and sent >= 0:
                    if _TRAFFIC_SAMPLE is not None and _TRAFFIC_SAMPLE[0] == interface:
                        elapsed = max(now - _TRAFFIC_SAMPLE[1], 0.001)
                        metrics["wifiRxBytesPerSecond"] = round(max(0, received - _TRAFFIC_SAMPLE[2]) / elapsed)
                        metrics["wifiTxBytesPerSecond"] = round(max(0, sent - _TRAFFIC_SAMPLE[3]) / elapsed)
                    _TRAFFIC_SAMPLE = (interface, now, received, sent)
                else:
                    errors.append("อุปกรณ์ไม่รายงานปริมาณรับ/ส่ง Wi-Fi")
            except Exception:
                errors.append("อ่านปริมาณรับ/ส่ง Wi-Fi ไม่สำเร็จ")
        elif metrics["wifiConnected"]:
            errors.append("ไม่พบ Network interface ของ Wi-Fi")

        if now - _LATENCY_SAMPLE_AT >= 10:
            _LATENCY_SAMPLE_AT = now
            try:
                started = time.monotonic()
                with socket.create_connection(("1.1.1.1", 443), timeout=1.5):
                    _LATENCY_SAMPLE_MS = round((time.monotonic() - started) * 1000)
                metrics["latencyMs"] = _LATENCY_SAMPLE_MS
            except OSError:
                _LATENCY_SAMPLE_MS = None
                metrics["latencyMs"] = None
                errors.append("วัด TCP latency ไป 1.1.1.1:443 ไม่สำเร็จ")

        try:
            from java import jclass

            intent = jclass("android.content.Intent")
            intent_filter = jclass("android.content.IntentFilter")(intent.ACTION_BATTERY_CHANGED)
            battery = context.registerReceiver(None, intent_filter)
            if battery is not None:
                level = int(battery.getIntExtra("level", -1))
                scale = int(battery.getIntExtra("scale", 100))
                if level >= 0 and scale > 0:
                    metrics["batteryPercent"] = round(level * 100 / scale)
            if metrics["batteryPercent"] is None:
                errors.append("อ่านระดับแบตเตอรี่ไม่สำเร็จ")
        except Exception:
            errors.append("อ่านระดับแบตเตอรี่ไม่สำเร็จ")

        try:
            from java import jclass

            activity_manager = context.getSystemService("activity")
            memory_info = jclass("android.app.ActivityManager$MemoryInfo")()
            activity_manager.getMemoryInfo(memory_info)
            metrics["ramTotalBytes"] = int(memory_info.totalMem)
            metrics["ramUsedBytes"] = max(0, int(memory_info.totalMem) - int(memory_info.availMem))
        except Exception:
            errors.append("อ่านข้อมูล RAM ไม่สำเร็จ")

        try:
            from java import jclass

            storage = jclass("android.os.StatFs")(str(root))
            metrics["storageFreeBytes"] = int(storage.getAvailableBytes())
            metrics["storageTotalBytes"] = int(storage.getTotalBytes())
        except Exception:
            errors.append("อ่านพื้นที่จัดเก็บไม่สำเร็จ")

        result = dict(metrics)
        _METRICS_CACHE["sampled_at"] = now
        _METRICS_CACHE["data"] = result
        return dict(result)


def _send_service_intent(context, action, **extras):
    from java import jclass

    if action == "dashboard_code":
        manager = context.getSystemService("notification")
        if not manager.areNotificationsEnabled():
            return False
        channel = manager.getNotificationChannel("dashboard-auth")
        if channel is not None and channel.getImportance() == 0:
            return False
    intent_class = jclass("android.content.Intent")
    service_class = jclass("com.example.serverrunner.ServerService")
    intent = intent_class(context, service_class).setAction(action)
    for key, value in extras.items():
        intent.putExtra(key, value)
    context.startService(intent)
    return True


class DashboardServer(ThreadingHTTPServer):
    allow_reuse_address = True
    daemon_threads = True
    request_queue_size = 8

    def __init__(self, address, root, ui, context):
        super().__init__(address, DashboardHandler)
        self.root = Path(root)
        self.ui = Path(ui)
        self.context = context
        self.auth_lock = threading.Lock()
        self.pending_code = None
        self.code_expires = 0
        self.code_attempts = 0
        self.code_cooldown = 0
        self.lock_until = 0
        self.sessions = {}
        self.request_slots = threading.BoundedSemaphore(8)

    def get_request(self):
        request, client_address = super().get_request()
        request.settimeout(5)
        return request, client_address

    def process_request(self, request, client_address):
        if not self.request_slots.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.request_slots.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.request_slots.release()

    def request_code(self):
        now = time.monotonic()
        with self.auth_lock:
            if now < self.lock_until:
                return "locked", int(self.lock_until - now + 0.99)
            if self.pending_code is not None and now < self.code_expires:
                if now < self.code_cooldown:
                    return "wait", int(self.code_cooldown - now + 0.99)
                self.code_cooldown = now + 30
                code = self.pending_code
            else:
                if now < self.code_cooldown:
                    return "wait", int(self.code_cooldown - now + 0.99)
                self.pending_code = "{:06d}".format(secrets.randbelow(1000000))
                self.code_expires = now + 300
                self.code_attempts = 0
                self.code_cooldown = now + 30
                code = self.pending_code
        try:
            sent = _send_service_intent(self.context, "dashboard_code", code=code)
        except Exception:
            with self.auth_lock:
                if self.pending_code == code:
                    self.pending_code = None
                    self.code_expires = 0
                    self.code_cooldown = 0
            return "unavailable", 0
        if not sent:
            with self.auth_lock:
                if self.pending_code == code:
                    self.pending_code = None
                    self.code_expires = 0
                    self.code_cooldown = 0
            return "unavailable", 0
        with self.auth_lock:
            retry_after = max(0, int(self.code_cooldown - time.monotonic() + 0.99))
        return "sent", retry_after

    def authenticate_code(self, candidate):
        now = time.monotonic()
        with self.auth_lock:
            if now < self.lock_until:
                return None, "locked", int(self.lock_until - now + 0.99)
            if self.pending_code is None or now >= self.code_expires:
                self.pending_code = None
                return None, "expired", 0
            self.code_attempts += 1
            if not hmac.compare_digest(str(candidate), self.pending_code):
                if self.code_attempts >= 5:
                    self.pending_code = None
                    self.lock_until = now + 300
                    return None, "locked", 300
                return None, "invalid", 0
            self.pending_code = None
            session = secrets.token_urlsafe(32)
            self.sessions[session] = now + 1800
            for key, expiry in list(self.sessions.items()):
                if expiry <= now:
                    self.sessions.pop(key, None)
            return session, "ok", 0

    def valid_session(self, cookie_header):
        session = ""
        for item in cookie_header.split(";"):
            key, separator, value = item.strip().partition("=")
            if separator and key == "sid":
                session = value
                break
        if not session:
            return False
        now = time.monotonic()
        with self.auth_lock:
            expires = self.sessions.get(session, 0)
            if expires <= now:
                self.sessions.pop(session, None)
                return False
            self.sessions[session] = now + 1800
            return True

    def logout(self, cookie_header):
        for item in cookie_header.split(";"):
            key, separator, value = item.strip().partition("=")
            if separator and key == "sid":
                with self.auth_lock:
                    self.sessions.pop(value, None)

    def state(self):
        try:
            servers = json.loads((self.root / "servers.json").read_text(encoding="utf-8"))
        except FileNotFoundError:
            servers = []
        except (OSError, ValueError) as exc:
            raise RuntimeError("Unable to read server list: {}".format(exc))
        try:
            running = set((self.root / "running.txt").read_text(encoding="utf-8").splitlines())
        except FileNotFoundError:
            running = set()

        dashboard_url = _read_text(self.root / "dashboard.url", 2048).strip()
        if not DOMAIN_RE.fullmatch(dashboard_url):
            dashboard_url = ""
        dashboard_enabled = _read_text(self.root / "dashboard.enabled", 16).strip() == "true"
        dashboard_server_log = _redact(_read_text(self.root / "dashboard-server.log"))
        dashboard_tunnel_log = _redact(_read_text(self.root / "dashboard-tunnel.log"))
        result = {
            "dashboardUrl": dashboard_url,
            "dashboardServerStatus": _status(dashboard_server_log, dashboard_enabled, SERVER_OK_RE),
            "dashboardTunnelStatus": _status(dashboard_tunnel_log, dashboard_enabled, TUNNEL_OK_RE),
            "dashboardServerLog": dashboard_server_log,
            "dashboardTunnelLog": dashboard_tunnel_log,
            "deviceMetrics": _device_metrics(self.context, self.root),
            "servers": [],
        }
        for server in servers:
            server_id = str(server.get("id", ""))
            if not SERVER_ID_RE.fullmatch(server_id):
                continue
            server_dir = self.root / "servers" / server_id
            server_log = _redact(_read_text(server_dir / "server.log"), (server.get("token", ""),))
            tunnel_log = _redact(_read_text(server_dir / "tunnel.log"), (server.get("token", ""),))
            database_files = []
            for base in (server_dir / "data", server_dir):
                if base.exists():
                    for path in base.rglob("*"):
                        if path.is_file() and path.name.lower().endswith(DB_SUFFIXES):
                            database_files.append(str(path.relative_to(server_dir)))
            database_files = sorted(set(database_files))
            server_file = server_dir / "server_file"
            result["servers"].append({
                "id": server_id,
                "name": str(server.get("name") or "Server"),
                "port": int(server.get("port", 8080)),
                "running": server_id in running,
                "serverStatus": _status(server_log, server_id in running, SERVER_OK_RE),
                "tunnelStatus": _status(tunnel_log, server_id in running, TUNNEL_OK_RE),
                "file": server_file.name if server_file.is_file() else "ยังไม่ได้เลือกไฟล์",
                "databases": database_files,
                "domain": (DOMAIN_RE.search(tunnel_log).group(0) if DOMAIN_RE.search(tunnel_log) else ""),
                "serverLog": server_log,
                "tunnelLog": tunnel_log,
            })
        return result


class DashboardHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        return

    def _respond(self, status, body, content_type="application/json; charset=utf-8", headers=None):
        payload = body if isinstance(body, bytes) else body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'self'; connect-src 'self'; img-src 'self' data:; "
            "style-src 'self'; script-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'none'",
        )
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

    def _json(self, status, value, headers=None):
        self._respond(status, json.dumps(value, ensure_ascii=False), headers=headers)

    def _authenticated(self):
        return self.server.valid_session(self.headers.get("Cookie", ""))

    def _static(self, path):
        filename = {"/": "index.html", "/index.html": "index.html",
                    "/app.js": "app.js", "/style.css": "style.css"}.get(path)
        if filename is None:
            self._json(404, {"error": "Not found"})
            return
        content_types = {
            "index.html": "text/html; charset=utf-8",
            "app.js": "text/javascript; charset=utf-8",
            "style.css": "text/css; charset=utf-8",
        }
        try:
            body = (self.server.ui / filename).read_bytes()
        except OSError:
            self._json(503, {"error": "Dashboard UI is not installed"})
            return
        self._respond(200, body, content_types[filename])

    def _read_json_body(self):
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            return None
        if length < 1 or length > 2048:
            return None
        try:
            return json.loads(self.rfile.read(length).decode("utf-8"))
        except (UnicodeDecodeError, ValueError):
            return None

    def do_GET(self):
        path = urlsplit(self.path).path
        if path.startswith("/api/"):
            if path == "/api/auth":
                if self._authenticated():
                    self._json(200, {"authenticated": True})
                    return
                state, retry = self.server.request_code()
                self._json(200, {"authenticated": False, "challenge": state, "retryAfter": retry})
                return
            if path == "/api/state":
                if not self._authenticated():
                    self._json(401, {"error": "Authentication required"})
                    return
                try:
                    self._json(200, self.server.state())
                except Exception as exc:
                    self._json(500, {"error": str(exc)})
                return
            self._json(404, {"error": "Not found"})
            return
        self._static(path)

    def do_POST(self):
        path = urlsplit(self.path).path
        if path == "/api/login":
            body = self._read_json_body()
            if not isinstance(body, dict):
                self._json(400, {"error": "Invalid request"})
                return
            session, result, retry = self.server.authenticate_code(body.get("code", ""))
            if result == "ok":
                self._json(200, {"authenticated": True}, {
                    "Set-Cookie": "sid={}; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=1800".format(session)
                })
            elif result == "locked":
                self._json(429, {"error": "Too many attempts. Try again later.", "retryAfter": retry})
            elif result == "expired":
                self._json(401, {"error": "Code expired. Request a new code."})
            else:
                self._json(401, {"error": "Incorrect code."})
            return
        if path == "/api/logout":
            if not self._authenticated():
                self._json(401, {"error": "Authentication required"})
                return
            self.server.logout(self.headers.get("Cookie", ""))
            self._json(200, {"authenticated": False}, {
                "Set-Cookie": "sid=; HttpOnly; Secure; SameSite=Strict; Path=/; Max-Age=0"
            })
            return
        self._json(404, {"error": "Not found"})


def run(context, root, ui):
    server = DashboardServer(("127.0.0.1", 0), root, ui, context)
    with open(Path(root) / "dashboard-server.log", "a", encoding="utf-8") as stream:
        stream.write("Dashboard listening on 127.0.0.1:{}\n".format(server.server_port))
    _send_service_intent(context, "dashboard_ready", port=server.server_port)
    server.serve_forever(poll_interval=0.5)
