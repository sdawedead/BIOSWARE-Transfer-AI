import json
import ipaddress
import re
import threading
import time
import uuid
from datetime import datetime, timedelta
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse
from zoneinfo import ZoneInfo

BASE = Path(__file__).resolve().parent
DATA_FILE = BASE / "data.json"
BRIDGE_FILE = BASE / "bridge_config.json"
TZ = ZoneInfo("Europe/Bratislava")
PORT = 8066
LOCK = threading.RLock()


def empty_state():
    return {"rides": [], "closures": [], "inbox": []}


def load_state():
    if not DATA_FILE.exists():
        return empty_state()
    try:
        data = json.loads(DATA_FILE.read_text(encoding="utf-8"))
        for key in ("rides", "closures", "inbox"):
            data.setdefault(key, [])
        return data
    except Exception:
        return empty_state()

def save_state(state):
    tmp = DATA_FILE.with_suffix(".tmp")
    tmp.write_text(json.dumps(state, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(DATA_FILE)


def bridge_token():
    try:
        return json.loads(BRIDGE_FILE.read_text(encoding="utf-8")).get("token", "")
    except Exception:
        return ""


def sunday_start(dt):
    days = (dt.weekday() + 1) % 7
    start = dt - timedelta(days=days)
    return start.replace(hour=0, minute=0, second=0, microsecond=0)


def ride_dt(ride):
    date = ride.get("date") or "1970-01-01"
    clock = ride.get("time") or "00:00"
    try:
        return datetime.fromisoformat(f"{date}T{clock}:00").replace(tzinfo=TZ)
    except ValueError:
        return datetime(1970, 1, 1, tzinfo=TZ)

def _iso_date(day, month, year=None):
    now = datetime.now(TZ)
    y = int(year) if year else now.year
    if y < 100:
        y += 2000
    return f"{y:04d}-{int(month):02d}-{int(day):02d}"


def _clean_customer(name):
    value = re.sub(r"\s+", " ", name).strip(" ,.;")
    if value.lower().startswith("aliciu "):
        value = "Alicia " + value.split(" ", 1)[1]
    return value


def parse_candidates(text):
    candidates = []
    phone = ""
    pm = re.search(r"(?:\+|00)\d{2}[\s-]?(?:\d[\s-]?){7,12}", text)
    if pm:
        phone = re.sub(r"\s+", " ", pm.group(0)).strip()
    customer = ""
    cm = re.search(r"\bpo\s+([A-ZÁČĎÉÍĹĽŇÓÔŔŠŤÚÝŽ][^,.;\n]+?\s+[A-ZÁČĎÉÍĹĽŇÓÔŔŠŤÚÝŽ][^,.;\n ]+)", text)
    if cm:
        customer = _clean_customer(cm.group(1))
    flight = ""
    fm = re.search(r"\b([A-Z]{2})\s*(\d{2,4})\b", text, re.I)
    if fm:
        flight = (fm.group(1)+fm.group(2)).upper()

    first = re.search(r"(\d{1,2})\.(\d{1,2})\.?(?:\s|,|\.)+.*?prilet[^0-9]*(?:[A-Z]+\s+)?[A-Z]{2}\s*\d{2,4}\s+o\s+(\d{1,2}:\d{2}).*?(?:letiska\s+brat|letiska\s+brať)\s+o\s+(\d{1,2}:\d{2}).*?do\s+(Hotela?\s+pod\s+hradom)", text, re.I|re.S)
    if first:
        ride={
            "date": _iso_date(first.group(1), first.group(2)),
            "time": first.group(4),
            "customer": customer,
            "phone": phone,
            "pickup": "Vienna Airport",
            "destination": "Hotel pod hradom",
            "flight": flight,
            "note": text.strip(),
            "rideName": "Vienna Airport → Hotel pod hradom",
        }
        candidates.append(ride)

    second = re.search(r"Z\s+TN\s+do\s+Viedne.*?(?:TnUAD|TNUAD)[, ]+([^,\n]+),?\s*(\d{1,2})\.(\d{1,2})\.?(?:\s+o)?\s+(\d{1,2}:\d{2})", text, re.I|re.S)
    if second:
        street = second.group(1).strip()
        ride={
            "date": _iso_date(second.group(2), second.group(3)),
            "time": second.group(4),
            "customer": customer,
            "phone": phone,
            "pickup": f"TnUAD, {street}",
            "destination": "Viedeň",
            "note": text.strip(),
            "rideName": f"TnUAD, {street} → Viedeň",
        }
        candidates.append(ride)
    return candidates


def parse_message(text):
    multi = parse_candidates(text)
    if multi:
        out = dict(multi[0])
        out["candidates"] = multi
        return out
    out = {"note": text.strip()}
    dm = re.search(r"\b(\d{1,2})[.\/-](\d{1,2})(?:[.\/-](\d{2,4}))?\.?", text)
    masked = text
    if dm:
        out["date"] = _iso_date(dm.group(1), dm.group(2), dm.group(3))
        masked = text[:dm.start()] + (" " * (dm.end()-dm.start())) + text[dm.end():]
    tm = re.search(r"\b([01]?\d|2[0-3]):([0-5]\d)\b", masked)
    if tm:
        out["time"] = f"{int(tm.group(1)):02d}:{tm.group(2)}"
    patterns = {
        "customer": r"(?:meno|name|customer|zákazník)\s*[:\-]\s*([^\n,;]+)",
        "pickup": r"(?:pickup|vyzdvihnutie|odkiaľ|from)\s*[:\-]\s*([^\n,;]+)",
        "destination": r"(?:cieľ|destination|to)\s*[:\-]\s*([^\n,;]+)",
    }
    for key, pat in patterns.items():
        m = re.search(pat, text, re.I)
        if m:
            out[key] = m.group(1).strip()
    m = re.search(r"\b[A-Z]{2}\s?\d{2,4}\b", text, re.I)
    if m:
        out["flight"] = re.sub(r"\s+", "", m.group(0)).upper()
    m = re.search(r"(?:\+421|00421|0)\s?\d{3}[\s-]?\d{3}[\s-]?\d{3}", text)
    if m:
        out["phone"] = m.group(0)
    m = re.search(r"(\d+)\s*(?:osob|pax|people)", text, re.I)
    if m:
        out["people"] = m.group(1)
    m = re.search(r"(?:cena|price)\s*[:\-]?\s*(\d+(?:[.,]\d{1,2})?)\s*€?", text, re.I)
    if m:
        out["price"] = float(m.group(1).replace(",", "."))
    if out.get("pickup") or out.get("destination"):
        out["rideName"] = " → ".join(x for x in (out.get("pickup"), out.get("destination")) if x)
    return out


def close_due_weeks():
    now = datetime.now(TZ)
    current_start = sunday_start(now)
    with LOCK:
        state = load_state()
        due = [r for r in state["rides"] if ride_dt(r) < current_start]
        if not due:
            return False
        groups = {}
        for ride in due:
            start = sunday_start(ride_dt(ride))
            groups.setdefault(start.strftime("%Y-%m-%d"), {"start": start, "rides": []})["rides"].append(ride)
        existing = {c.get("id") for c in state["closures"]}
        for cid, group in sorted(groups.items()):
            if cid in existing:
                continue
            start = group["start"]
            end = start + timedelta(days=7)
            total = round(sum(float(r.get("price") or 0) for r in group["rides"]), 2)
            state["closures"].append({
                "id": cid,
                "weekStart": start.isoformat(),
                "weekEnd": end.isoformat(),
                "closedAt": now.isoformat(),
                "rideCount": len(group["rides"]),
                "total": total,
                "rides": group["rides"],
            })
        due_ids = {r.get("id") for r in due}
        state["rides"] = [r for r in state["rides"] if r.get("id") not in due_ids]
        state["closures"].sort(key=lambda c: c.get("weekStart", ""), reverse=True)
        save_state(state)
        return True


def scheduler_loop():
    while True:
        try:
            close_due_weeks()
        except Exception as exc:
            print(f"closure scheduler error: {exc}", flush=True)
        time.sleep(30)

class Handler(SimpleHTTPRequestHandler):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, directory=str(BASE), **kwargs)

    def send_json(self, payload, status=200):
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(raw)

    def read_json(self):
        length = int(self.headers.get("Content-Length", "0") or 0)
        if not length:
            return {}
        return json.loads(self.rfile.read(length).decode("utf-8"))

    def do_GET(self):
        path = urlparse(self.path).path
        if path == "/api/state":
            close_due_weeks()
            with LOCK:
                state = load_state()
            state["serverTime"] = datetime.now(TZ).isoformat()
            return self.send_json(state)
        return super().do_GET()

    def do_POST(self):
        path = urlparse(self.path).path
        if path == "/api/rides":
            ride = self.read_json()
            source_inbox_id = ride.pop("sourceInboxId", None)
            ride.setdefault("id", str(uuid.uuid4()))
            ride.setdefault("createdAt", datetime.now(TZ).isoformat())
            with LOCK:
                state = load_state()
                state["rides"].append(ride)
                if source_inbox_id:
                    state["inbox"] = [m for m in state["inbox"] if m.get("id") != source_inbox_id]
                save_state(state)
            return self.send_json({"ok": True, "ride": ride}, 201)

        if path == "/api/inbox":
            payload = self.read_json()
            sender = str(payload.get("sender") or "").strip()
            text = str(payload.get("text") or "").strip()
            if not sender or not text:
                return self.send_json({"ok": False, "error": "sender and text required"}, 400)
            try:
                ip = ipaddress.ip_address(self.client_address[0])
                allowed = ip.is_loopback or ip in ipaddress.ip_network("100.64.0.0/10")
            except ValueError:
                allowed = False
            if not allowed:
                return self.send_json({"ok": False, "error": "bridge requires Tailscale"}, 403)
            item = {
                "id": str(uuid.uuid4()),
                "source": str(payload.get("source") or "Messenger"),
                "sourceKey": str(payload.get("sourceKey") or ""),
                "sender": sender,
                "text": text,
                "receivedAt": datetime.now(TZ).isoformat(),
                "candidate": parse_message(text),
            }
            with LOCK:
                state = load_state()
                source_key = item["sourceKey"]
                if source_key and any(m.get("sourceKey") == source_key for m in state["inbox"]):
                    return self.send_json({"ok": True, "duplicate": True})
                state["inbox"].insert(0, item)
                state["inbox"] = state["inbox"][:100]
                save_state(state)
            return self.send_json({"ok": True, "message": item}, 201)

        if path == "/api/close-now":
            changed = close_due_weeks()
            return self.send_json({"ok": True, "changed": changed})
        return self.send_json({"ok": False, "error": "not found"}, 404)

    def do_DELETE(self):
        path = urlparse(self.path).path
        if path.startswith("/api/rides/"):
            rid = path.rsplit("/", 1)[-1]
            with LOCK:
                state = load_state()
                before = len(state["rides"])
                state["rides"] = [r for r in state["rides"] if r.get("id") != rid]
                save_state(state)
            return self.send_json({"ok": True, "deleted": before - len(state["rides"])})
        if path.startswith("/api/inbox/"):
            mid = path.rsplit("/", 1)[-1]
            with LOCK:
                state = load_state()
                before = len(state["inbox"])
                state["inbox"] = [m for m in state["inbox"] if m.get("id") != mid]
                save_state(state)
            return self.send_json({"ok": True, "deleted": before - len(state["inbox"])})
        if path == "/api/rides":
            with LOCK:
                state = load_state()
                state["rides"] = []
                save_state(state)
            return self.send_json({"ok": True})
        return self.send_json({"ok": False, "error": "not found"}, 404)

def main():
    with LOCK:
        if not DATA_FILE.exists():
            save_state(empty_state())
        else:
            state = load_state()
            save_state(state)
    close_due_weeks()
    threading.Thread(target=scheduler_loop, daemon=True).start()
    print(f"BIOSWARE Transfer AI listening on http://0.0.0.0:{PORT}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()


if __name__ == "__main__":
    main()
