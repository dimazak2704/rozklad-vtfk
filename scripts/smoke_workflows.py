#!/usr/bin/env python3
"""Exercises the main workflows against the running stack with real Keycloak tokens (docs/RUNNING.md).

Run after `scripts/dev-stack.sh seed`. It CREATES data (an absence, changes and a published order on
SMOKE_DATE, a generated draft version, optionally load items from the curriculum), so for a clean demo
database run `scripts/dev-stack.sh reset-data --yes && scripts/dev-stack.sh up --no-build && scripts/dev-stack.sh seed`
afterwards.

  python scripts/smoke_workflows.py [public] [substitution] [generation] [ai]

`ai` makes one paid Anthropic call (curriculum scan, vision model of the importer) and is skipped unless
named explicitly. Every step fails loudly (SystemExit) on an unexpected answer.
"""
from __future__ import annotations

import json
import urllib.parse
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import seed_demo as seed  # noqa: E402  (shared HTTP client and constants)

SMOKE_DATE = "2026-11-12"  # четвер, майбутній навчальний день семестру I
WEEK = ("2026-11-09", "2026-11-13")


def check(condition: bool, message: str) -> None:
    if not condition:
        raise SystemExit(f"FAILED: {message}")
    print(f"  ok  {message}", flush=True)


class EventStream(threading.Thread):
    """Reads /api/events/stream (SSE) through the gateway and keeps the event names it saw."""

    def __init__(self, client: seed.Client):
        super().__init__(daemon=True)
        self.client = client
        self.events: list[tuple[str, dict | str]] = []
        self.connected = threading.Event()

    def run(self) -> None:
        request = urllib.request.Request(seed.API + "/api/events/stream", headers={
            "Authorization": f"Bearer {self.client._token()}", "Accept": "text/event-stream"})
        with urllib.request.urlopen(request, timeout=600) as response:
            self.connected.set()
            name, data = None, []
            for raw in response:
                line = raw.decode("utf-8").rstrip("\r\n")
                if line.startswith("event:"):
                    name = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].strip())
                elif line == "":
                    if name or data:
                        text = "\n".join(data)
                        try:
                            payload = json.loads(text)
                        except ValueError:
                            payload = text
                        self.events.append((name or "message", payload))
                    name, data = None, []

    def names(self) -> list[str]:
        return [name for name, _ in self.events]

    def wait_for(self, name: str, seconds: float = 30) -> None:
        deadline = time.time() + seconds
        while time.time() < deadline:
            if name in self.names():
                return
            time.sleep(0.5)
        raise SystemExit(f"FAILED: SSE event «{name}» did not arrive in {seconds}s (got {self.names()})")


def ids(client: seed.Client):
    groups = seed.group_ids(client)
    teachers = {t["shortName"]: t["id"] for t in client.get("/api/public/teachers")}
    rooms = {r["name"]: r["id"] for r in client.get("/api/public/rooms")}
    return groups, teachers, rooms


def step_public(admin: seed.Client, **_) -> None:
    anonymous = seed.API
    def public(path: str):
        with urllib.request.urlopen(anonymous + path, timeout=30) as response:
            return json.load(response)
    meta = public("/api/public/meta")
    check(meta["semester"]["name"] == seed.SEMESTER_I and meta["parity"] in ("NUMERATOR", "DENOMINATOR"),
          f"meta: {meta['semester']['name']}, week {meta['weekNumber']}, {meta['parity']}, today {meta['today']}")
    groups, teachers, rooms = ids(admin)
    found = public("/api/public/search?q=4ki1&limit=5")
    check(any(item["label"] == "4КІ1" for item in found), "search «4ki1» (Latin) finds the group 4КІ1")
    found = public("/api/public/search?q=" + urllib.parse.quote("кренц"))
    check(any(item["type"] == "TEACHER" for item in found), "search «кренц» finds the teacher Кренцін")
    week = f"from={WEEK[0]}&to={WEEK[1]}"
    group = public(f"/api/public/schedule/group/{groups['4КІ1']}?{week}")
    lessons = sum(len(day["lessons"]) for day in group["days"])
    check(len(group["days"]) == 5 and lessons > 0, f"4КІ1 week {WEEK[0]}..{WEEK[1]}: {lessons} lessons")
    teacher = public(f"/api/public/schedule/teacher/{teachers['Кренцін']}?{week}")
    check(sum(len(d["lessons"]) for d in teacher["days"]) > 0, "Кренцін has lessons that week")
    room = public(f"/api/public/schedule/room/{rooms['301']}?{week}")
    check(room["owner"]["label"].startswith("301") or room["owner"]["label"] == "301", "room 301 schedule")
    changes = public("/api/public/changes?date=2026-10-19")
    check(changes["order"]["number"] == 35 and len(changes["groups"]) > 5,
          f"public changes of 19.10.2026: order №{changes['order']['number']}, {len(changes['groups'])} groups")
    day = public(f"/api/public/schedule/group/{groups['4КІ1']}?from=2026-10-19&to=2026-10-19")["days"][0]
    replaced = [l for l in day["lessons"] if l["kind"] != "REGULAR"]
    check(any(l["discipline"]["shortName"] == "НДЕКС" for l in replaced),
          "4КІ1 on 19.10: pair 2 is НДЕКС Непийвода (applied by order №35)")
    bells = public("/api/public/bells?date=2026-11-13")
    check(len(bells["slots"]) == 6, "Friday bells: 6 pairs")
    with urllib.request.urlopen(f"{anonymous}/api/public/ics/group/{groups['4КІ1']}.ics", timeout=60) as response:
        check(response.read(15).startswith(b"BEGIN:VCALENDAR"), "iCalendar export of 4КІ1")


def step_substitution(admin: seed.Client, stream: EventStream, **_) -> None:
    credentials = seed.demo_credentials()
    krentsin = seed.Client(credentials["TEACHER_USERNAME"], credentials["TEACHER_PASSWORD"])
    groups, teachers, rooms = ids(admin)
    me = krentsin.get("/api/me")
    check(me["teacher"]["id"] == teachers["Кренцін"], "GET /api/me of krentsin → teacher Кренцін")
    binding = krentsin.get("/api/notify/bindings/me")
    check(binding["teacherId"] == teachers["Кренцін"], f"own Telegram binding status: bound={binding['bound']}")

    # відсутність викладача → уражені пари → ранжовані варіанти
    absent = teachers["Непийвода"]
    existing = seed.items(admin.get(f"/api/admin/absences?from={SMOKE_DATE}&to={SMOKE_DATE}&teacherId={absent}"))
    if existing:
        absence = existing[0]
    else:
        absence = admin.post("/api/admin/absences", {"teacherId": absent, "dateFrom": SMOKE_DATE,
                                                     "dateTo": SMOKE_DATE, "reasonCategory": "BUSINESS_TRIP"})
        stream.wait_for("absence.created")
    # повторний запуск: відкриті рішення попереднього прогону скасовуємо (DELETE = CANCELLED)
    for old in seed.items(admin.get(f"/api/admin/changes?date={SMOKE_DATE}")):
        if old["status"] not in ("CANCELLED", "APPLIED"):
            admin.call("DELETE", f"/api/admin/changes/{old['id']}", expect_json=False)
    affected = admin.get(f"/api/admin/absences/{absence['id']}/affected")
    check(len(affected) > 0, f"absence of Непийвода on {SMOKE_DATE}: {len(affected)} affected lessons")
    chosen = None
    for item in affected:
        options = admin.get(f"/api/admin/lessons/{item['lesson']['id']}/options")["options"]
        mine = [o for o in options if o["kind"] in ("REPLACE_TEACHER", "REPLACE_DISCIPLINE")
                and (o.get("teacher") or {}).get("id") == teachers["Кренцін"]]
        if mine:
            chosen, option = item["lesson"], mine[0]
            break
    check(chosen is not None, "Кренцін is among the ranked candidates of an affected lesson")
    print(f"      option: {option['kind']}, score {option['score']}, {option['reasons']}")
    request = {"kind": option["kind"], "date": SMOKE_DATE, "pairFrom": chosen["pair"],
               "groupId": chosen["group"]["id"], "subgroup": chosen["subgroup"], "originalLessonId": chosen["id"],
               "newTeacherId": teachers["Кренцін"], "reasonCode": "BUSINESS_TRIP", "absenceId": absence["id"]}
    if option.get("discipline"):
        request["newDisciplineId"] = option["discipline"]["id"]
    change = admin.post("/api/admin/changes", request)
    # NOTIFY (типовий): заміна діє одразу, викладачу повідомлено; CONFIRM: чекає його згоди
    notify = change["approvalMode"] == "NOTIFY"
    check(change["status"] == ("CONFIRMED" if notify else "PENDING_CONFIRMATION"),
          f"approval mode {change['approvalMode']}: status {change['status']}")

    # викладач відмовляється, диспетчер просить ще раз, викладач підтверджує
    mine = [c for c in seed.items(krentsin.get("/api/me/changes")) if c["id"] == change["id"]]
    check(len(mine) == 1, "the change is in «мої заміни» of krentsin")
    declined = krentsin.post(f"/api/me/changes/{change['id']}/decline", {"comment": "Маю іншу пару"})
    check(declined["status"] == "DECLINED", "krentsin declines")
    admin.post(f"/api/admin/changes/{change['id']}/request-confirmation")
    confirmed = krentsin.post(f"/api/me/changes/{change['id']}/confirm", {"comment": "Добре"})
    check(confirmed["status"] == "CONFIRMED", "asked again, krentsin confirms")
    stream.wait_for("change.updated")

    # інші уражені пари: «пари не буде» (підтверджується одразу)
    for item in affected:
        if item["lesson"]["id"] != chosen["id"] and not item.get("decision"):
            admin.post("/api/admin/changes", {
                "kind": "CANCEL", "date": SMOKE_DATE, "pairFrom": item["lesson"]["pair"],
                "groupId": item["lesson"]["group"]["id"], "subgroup": item["lesson"]["subgroup"],
                "originalLessonId": item["lesson"]["id"], "reasonCode": "BUSINESS_TRIP", "absenceId": absence["id"]})
    dashboard = admin.get(f"/api/admin/dashboard?date={SMOKE_DATE}")
    check(dashboard["absences"] >= 1 and dashboard["confirmed"] >= 1
          and any(alert["kind"] == "ORDER_NOT_PUBLISHED" for alert in dashboard["alerts"]),
          f"dashboard {SMOKE_DATE}: absences {dashboard['absences']}, confirmed {dashboard['confirmed']}, "
          f"alert ORDER_NOT_PUBLISHED")

    draft = admin.get(f"/api/admin/orders/draft?date={SMOKE_DATE}")
    order = admin.post(f"/api/admin/orders/{draft['id']}/publish")
    check(order["number"] == 36 and order["status"] == "PUBLISHED",
          f"order published as №{order['number']} with {len(order['items'])} rows")
    stream.wait_for("order.published")
    stream.wait_for("schedule.changed")
    mine = krentsin.get(f"/api/me/schedule?from={SMOKE_DATE}&to={SMOKE_DATE}")["days"][0]["lessons"]
    check(any(l["kind"] == "SUBSTITUTED" for l in mine), "the substitution is in krentsin's own schedule")
    # notification отримує розпорядження подією order.published, тож документ з'являється за мить
    for _ in range(30):
        try:
            with urllib.request.urlopen(f"{seed.API}/api/documents/orders/{order['id']}.pdf", timeout=120) as response:
                pdf = response.read(5)
            break
        except urllib.error.HTTPError as missing:
            if missing.code != 404:
                raise
            time.sleep(1)
    check(pdf == b"%PDF-", "order PDF rendered by notification (LibreOffice)")


def step_generation(admin: seed.Client, stream: EventStream, **_) -> None:
    semester = seed.semester_by_name(admin, seed.SEMESTER_I)
    groups = seed.group_ids(admin)
    job = admin.post("/api/admin/generation/jobs", {"semesterId": semester["id"], "timeLimitSeconds": 10,
                                                    "scope": {"groupIds": [groups["4КІ1"], groups["4КІ2"]]}})
    for _ in range(90):
        state = admin.get(f"/api/admin/generation/jobs/{job['jobId']}")
        if state["status"] not in ("QUEUED", "RUNNING"):
            break
        time.sleep(2)
    check(state["status"] == "SOLVED", f"generation job: {state['status']}, score {state.get('bestScore')}, "
                                       f"{len(state.get('progress') or [])} progress points, error {state.get('error')}")
    stream.wait_for("generation.completed")
    lessons = admin.get(f"/api/admin/timetable/versions/{state['resultVersionId']}/lessons?groupId={groups['4КІ1']}")
    conflicts = admin.get(f"/api/admin/timetable/versions/{state['resultVersionId']}/conflicts")
    check(len(lessons) > 0, f"result version: {len(lessons)} lessons of 4КІ1, {len(conflicts['hard'])} hard conflicts, "
                            f"metrics after {state.get('metricsAfter', {}).get('groupWindows') if state.get('metricsAfter') else None} group windows")


def step_ai(admin: seed.Client, **_) -> None:
    usage_before = admin.get("/api/import/ai/usage")
    job = admin.upload("/api/import/jobs", {"kind": "CURRICULUM", "options": json.dumps(
        {"semesterNumber": 7, "groupCodes": ["4КІ1", "4КІ2", "4КІ3"]})}, "file",
        seed.ROOT / "seed" / "curriculum-ki-2023.pdf", "application/pdf")
    job = seed.wait_job(admin, job["id"], ("UPLOADED", "PARSING"))
    check(job["status"] in ("NEEDS_REVIEW", "READY"), f"curriculum parsed by {job['ai']['model']}: {job['status']}, "
                                                     f"{job['ai']['inputTokens']}/{job['ai']['outputTokens']} tokens, "
                                                     f"${job['ai']['costUsd']}")
    rows = job["draft"].get("rows") or []
    for row in rows:
        print(f"      {row}")
    if job["status"] == "NEEDS_REVIEW":
        mappings = [{"type": r["type"], "raw": r["raw"], "createNew": {}, "addAlias": False}
                    for r in job["report"] if r["type"] in ("DISCIPLINE", "TEACHER", "GROUP", "ROOM")
                    and r["severity"] in ("WARN", "ERROR") and not r.get("matched")]
        job = admin.put(f"/api/import/jobs/{job['id']}/mappings", {"mappings": mappings})
    check(job["status"] == "READY", f"review done: {job['status']}")
    semester = seed.semester_by_name(admin, seed.SEMESTER_I)
    admin.call("POST", f"/api/import/jobs/{job['id']}/apply", {"semesterId": semester["id"]})
    job = seed.wait_job(admin, job["id"], ("APPLYING",))
    check(job["status"] == "APPLIED", f"applied to core: {json.dumps(job.get('result'), ensure_ascii=False)[:300]}")
    usage = admin.get("/api/import/ai/usage")
    check(usage["today"]["calls"] > usage_before["today"]["calls"],
          f"AI usage today: {usage['today']['calls']} calls, ${usage['today']['costUsd']}")


STEPS = {"public": step_public, "substitution": step_substitution, "generation": step_generation, "ai": step_ai}


def main(argv: list[str]) -> None:
    credentials = seed.demo_credentials()
    admin = seed.Client(credentials["ADMIN_USERNAME"], credentials["ADMIN_PASSWORD"])
    stream = EventStream(admin)
    stream.start()
    stream.connected.wait(30)
    for name in argv or ["public", "substitution", "generation"]:
        print(f"== {name}", flush=True)
        STEPS[name](admin, stream=stream)
    print(f"SSE events seen: {sorted(set(stream.names()))}")


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
