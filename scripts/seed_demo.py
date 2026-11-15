#!/usr/bin/env python3
"""Seeds the running local stack with the real college data through the system's own REST API.

Nothing is written to a database directly: every step is a call a dispatcher (or the importer) would
make, so the seed also exercises the APIs. The steps are idempotent; a second run skips what exists.

  1. structure: departments, specialties, cycle commissions (admin catalog CRUD)
  2. calendar:  semesters I/II 2026/2027 (week 1 = чисельник), bell profile check
  3. import:    seed/schedule-2025-26.xlsx -> importer job -> review -> apply -> draft version
  4. periods:   calendar periods from the notes of order №35
  5. academic:  seed/academic-calendar-2026-27.xlsx (the college's academic calendar) -> importer job -> check
                -> apply: periods of national resistance, practice, sessions, holidays per group
  6. publish:   the imported version, effective 2026-09-01; load items derived from it
  7. accounts:  demo accounts of the sign-in service linked to the teachers «Кренцін» and «Непийвода»
  8. order 35:  the changes of 19.10.2026 -> confirmations -> notes -> publication as №35
  9. names:     full names of the teachers from seed/teacher-names.csv (public college sources)

Periods and the academic calendar come before publication: it materializes the dated lessons around them (a group
in a period of national resistance has no pairs in it).

By name only (not in the default run): `recent` — absences and published orders for the study days after №35 up to
18.11.2026 (then scripts/demo_day.py takes Thursday 19.11 and Friday 20.11): a living stand.

Usage (stack running, see scripts/dev-stack.sh):  python scripts/seed_demo.py [step ...]
Demo credentials come from infra/dev/demo-users.env (test values only); tokens come from the sign-in
service through scripts/rozklad_auth.py (AUTH_URL overrides its address).
"""
from __future__ import annotations

import csv
import json
import os
import random
import re
import sys
import time
import urllib.error
import urllib.request
import uuid
from datetime import date, timedelta
from pathlib import Path

import rozklad_auth

ROOT = Path(__file__).resolve().parent.parent
API = os.environ.get("API_URL", "http://localhost:8080")
SEMESTER_I = "I семестр 2026/2027"
VERSION_NAME = "Розклад 2025/26 (імпорт з Excel)"
EFFECTIVE_FROM = "2026-09-01"
ORDER_DATE = "2026-10-19"
ORDER_NUMBER = 35


def demo_credentials() -> dict[str, str]:
    return rozklad_auth.demo_credentials()


class ApiError(RuntimeError):
    def __init__(self, method: str, url: str, status: int, body: str):
        super().__init__(f"{method} {url} -> {status}: {body[:2000]}")
        self.status = status
        self.body = body

    def problem(self) -> dict:
        try:
            return json.loads(self.body)
        except ValueError:
            return {}


class Client:
    def __init__(self, username: str, password: str):
        self.username = username
        # вхід через сторінку входу сервісу auth (код + PKCE, клієнт rozklad-cli); токен оновлюється сам
        self.tokens = rozklad_auth.TokenSource(username, password)

    def _token(self) -> str:
        return self.tokens.token()

    def call(self, method: str, path: str, body=None, *, raw: bytes | None = None, content_type: str | None = None,
             expect_json: bool = True):
        url = API + path
        headers = {"Authorization": f"Bearer {self._token()}", "Accept": "application/json"}
        data = raw
        if body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            content_type = "application/json"
        if content_type:
            headers["Content-Type"] = content_type
        request = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = response.read()
        except urllib.error.HTTPError as failed:
            raise ApiError(method, url, failed.code, failed.read().decode("utf-8", "replace")) from None
        if not expect_json:
            return payload
        return json.loads(payload) if payload else None

    def get(self, path: str):
        return self.call("GET", path)

    def post(self, path: str, body=None):
        return self.call("POST", path, body if body is not None else {})

    def put(self, path: str, body):
        return self.call("PUT", path, body)

    def upload(self, path: str, fields: dict[str, str], file_field: str, file_path: Path, mime: str):
        boundary = uuid.uuid4().hex
        parts = []
        for name, value in fields.items():
            parts.append(f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n{value}\r\n'.encode())
        parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="{file_field}"; '
                      f'filename="{file_path.name}"\r\nContent-Type: {mime}\r\n\r\n').encode())
        parts.append(file_path.read_bytes())
        parts.append(f"\r\n--{boundary}--\r\n".encode())
        return self.call("POST", path, raw=b"".join(parts), content_type=f"multipart/form-data; boundary={boundary}")


def items(page_or_list):
    return page_or_list["items"] if isinstance(page_or_list, dict) and "items" in page_or_list else page_or_list


def all_pages(client: Client, path: str) -> list:
    separator = "&" if "?" in path else "?"
    result, page = [], 0
    while True:
        answer = client.get(f"{path}{separator}page={page}&size=200")
        if not isinstance(answer, dict):
            return answer
        result.extend(answer["items"])
        if len(result) >= answer["total"] or not answer["items"]:
            return result
        page += 1


def log(message: str) -> None:
    print(message, flush=True)


# ---------------------------------------------------------------- 1. structure
DEPARTMENTS = [
    ("Комп’ютерне відділення", "Комп’ютерне"),
    ("Фінансово-економічне відділення", "Фінансово-економічне"),
    ("Радіотехнічне відділення", "Радіотехнічне"),
    ("Відділення електроенергетики та електромеханіки", "Електроенергетики та електромеханіки"),
    ("Відділення транспортних технологій", "Транспортних технологій"),
]
# Повна назва є лише для КІ (програми дисциплін); інші спеціальності мають назву = літери (DOMAIN §12)
SPECIALTIES = [
    ("123", "КІ", "Комп’ютерна інженерія", "Комп’ютерне відділення"),
    (None, "Ф", "Ф", "Фінансово-економічне відділення"),
    (None, "ОБ", "ОБ", "Фінансово-економічне відділення"),
    (None, "М", "М", "Фінансово-економічне відділення"),
    (None, "ЕП", "ЕП", "Фінансово-економічне відділення"),
    (None, "ОД", "ОД", "Фінансово-економічне відділення"),
    (None, "РТ", "РТ", "Радіотехнічне відділення"),
    (None, "ТК", "ТК", "Радіотехнічне відділення"),
    (None, "ЕА", "ЕА", "Відділення електроенергетики та електромеханіки"),
    (None, "ВЕ", "ВЕ", "Відділення електроенергетики та електромеханіки"),
    (None, "ОП", "ОП", "Відділення транспортних технологій"),
]
CYCLE_COMMISSIONS = ["Циклова комісія комп’ютерної інженерії", "Циклова комісія економіки і фінансів"]


def step_structure(admin: Client) -> None:
    departments = {d["name"]: d["id"] for d in all_pages(admin, "/api/admin/departments")}
    for name, short in DEPARTMENTS:
        if name not in departments:
            departments[name] = admin.post("/api/admin/departments", {"name": name, "shortName": short})["id"]
            log(f"  department created: {name}")
    specialties = {s["letters"] for s in all_pages(admin, "/api/admin/specialties")}
    for code, letters, name, department in SPECIALTIES:
        if letters not in specialties:
            admin.post("/api/admin/specialties", {"code": code, "letters": letters, "name": name,
                                                   "departmentId": departments[department]})
            log(f"  specialty created: {letters}")
    commissions = {c["name"] for c in all_pages(admin, "/api/admin/cycle-commissions")}
    for name in CYCLE_COMMISSIONS:
        if name not in commissions:
            admin.post("/api/admin/cycle-commissions", {"name": name})
            log(f"  cycle commission created: {name}")
    log("structure: ok")


# ---------------------------------------------------------------- 2. calendar
BELLS_60 = "2 зміни, 60 хв"


def bell_profile_id(admin: Client, name: str) -> str:
    for profile in admin.get("/api/admin/bell-profiles"):
        if profile["name"] == name:
            return profile["id"]
    raise SystemExit(f"bell profile «{name}» is missing (reference data V1_201)")


def check_bells(admin: Client) -> None:
    # Дзвінки 60 хв з аркуша «дзвінки (2 зм)» (DOMAIN §2): пн-чт 8 пар, пт 6 пар
    expected = {1: [("08:30", "09:30"), ("09:40", "10:40"), ("11:00", "12:00"), ("12:10", "13:10"),
                    ("13:30", "14:30"), ("14:40", "15:40"), ("16:00", "17:00"), ("17:10", "18:10")],
                5: [("08:30", "09:30"), ("09:40", "10:40"), ("11:00", "12:00"), ("12:20", "13:20"),
                    ("13:30", "14:30"), ("14:50", "15:50")]}
    profile = next(p for p in admin.get("/api/admin/bell-profiles") if p["name"] == BELLS_60)
    for weekday, slots in expected.items():
        actual = [(s["startsAt"], s["endsAt"]) for s in sorted(profile["slots"], key=lambda s: s["pair"])
                  if s["weekday"] == weekday]
        if actual != slots:
            raise SystemExit(f"bell profile «{BELLS_60}», weekday {weekday}: {actual} != {slots}")
    log(f"  bell profile «{BELLS_60}» matches the college bells")


def semester_by_name(admin: Client, name: str):
    return next((s for s in items(admin.get("/api/admin/semesters")) if s["name"] == name), None)


def step_calendar(admin: Client) -> None:
    check_bells(admin)
    bells = bell_profile_id(admin, BELLS_60)
    wanted = [
        {"academicYear": "2026/2027", "number": 1, "name": SEMESTER_I, "startsOn": "2026-09-01",
         "endsOn": "2026-12-30", "firstWeekParity": "NUMERATOR", "bellProfileId": bells},
        {"academicYear": "2026/2027", "number": 2, "name": "II семестр 2026/2027", "startsOn": "2027-01-19",
         "endsOn": "2027-06-30", "firstWeekParity": "NUMERATOR", "bellProfileId": bells},
    ]
    for request in wanted:
        if semester_by_name(admin, request["name"]) is None:
            admin.post("/api/admin/semesters", request)
            log(f"  semester created: {request['name']}")
    semester = semester_by_name(admin, SEMESTER_I)
    if not semester["active"]:
        admin.post(f"/api/admin/semesters/{semester['id']}/activate")
        log(f"  semester activated: {SEMESTER_I}")
    weeks = admin.get(f"/api/admin/semesters/{semester['id']}/weeks")
    week8 = next(w for w in weeks if w["weekStart"] == "2026-10-19")
    if week8["weekNumber"] != 8 or week8["parity"] != "DENOMINATOR":
        raise SystemExit(f"week of 19.10.2026 must be week 8, ЗНАМЕННИК: {week8}")
    log("calendar: ok (19.10.2026 = week 8, знаменник)")


# ---------------------------------------------------------------- 3. import
XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"


def wait_job(admin: Client, job_id: str, busy: tuple[str, ...]) -> dict:
    for _ in range(300):
        job = admin.get(f"/api/import/jobs/{job_id}")
        if job["status"] not in busy:
            return job
        time.sleep(2)
    raise SystemExit(f"import job {job_id} is still {job['status']}")


def imported_version(admin: Client, semester_id: str):
    versions = admin.get(f"/api/admin/timetable/versions?semesterId={semester_id}")
    return next((v for v in versions if v["source"] == "IMPORT" and v["status"] != "ARCHIVED"), None)


def step_import(admin: Client) -> None:
    semester = semester_by_name(admin, SEMESTER_I)
    if imported_version(admin, semester["id"]) is not None:
        log("import: the version already exists, skipped")
        return
    job = admin.upload("/api/import/jobs", {"kind": "TIMETABLE_XLSX", "semesterId": semester["id"],
                                            "options": json.dumps({"versionName": VERSION_NAME})},
                       "file", ROOT / "seed" / "schedule-2025-26.xlsx", XLSX_MIME)
    job = wait_job(admin, job["id"], ("UPLOADED", "PARSING"))
    log(f"  parsed: {job['status']}, summary {job['summary']}")
    if job["status"] == "NEEDS_REVIEW":
        # рев'ю: блокують лише неоднозначні збіги з каталогом; у порожній базі все нове — підтверджуємо
        blocking = [row for row in job["report"] if row["severity"] == "ERROR"
                    or (row["type"] in ("TEACHER", "DISCIPLINE", "GROUP", "ROOM") and row["suggestions"]
                        and not row.get("matched"))]
        mappings = [{"type": row["type"], "raw": row["raw"], "createNew": {}, "addAlias": False}
                    for row in blocking if row["type"] in ("TEACHER", "DISCIPLINE", "GROUP", "ROOM")]
        job = admin.put(f"/api/import/jobs/{job['id']}/mappings", {"mappings": mappings})
        log(f"  review: {len(mappings)} new entities confirmed -> {job['status']}")
    if job["status"] != "READY":
        errors = [row.get("message") for row in job["report"] if row["severity"] == "ERROR"]
        raise SystemExit(f"import job {job['id']} is {job['status']}: {errors[:5]}")
    warnings = sum(1 for row in job["report"] if row["severity"] == "WARN")
    log(f"  review: READY, {warnings} warnings (typos merged, curators, clashes in the file)")
    admin.post(f"/api/import/jobs/{job['id']}/apply")
    job = wait_job(admin, job["id"], ("APPLYING",))
    if job["status"] != "APPLIED":
        raise SystemExit(f"import job {job['id']} is {job['status']}: {job.get('error')}")
    catalog, timetable = job["result"]["catalog"], job["result"]["timetable"]
    # каталог, що вже є в базі (повторний seed після reset-версій), імпорт не створює: {"skipped": true}
    created = "nothing (already present)" if catalog.get("skipped") else catalog.get("created")
    log(f"  applied: catalog created {created}, version «{timetable['name']}» "
        f"with {timetable['lessonCount']} lessons, {timetable['hardConflicts']} hard conflicts")
    log("import: ok")


# ---------------------------------------------------------------- 4. periods (before publication: they shape it)
PERIODS = [
    {"kind": "STUDY_MODE", "title": "3 курс навчається дистанційно", "startsOn": "2026-09-01",
     "endsOn": "2026-10-31", "studyMode": "ONLINE", "scope": "COURSES", "courses": [3],
     "orderNote": "3 курс групи 3Ф,3ОД,3ЕП,3ОБ,3РТ,3ТК,3ЕА,3ВЕ,3ОП з 1.09.26р. по 31.10.26.– "
                  "Навчаються за розкладом дистанційно."},
    {"kind": "STUDY_MODE", "title": "4КІ навчаються дистанційно", "startsOn": "2026-09-01",
     "endsOn": "2026-10-31", "studyMode": "ONLINE", "scope": "GROUPS", "groups": ["4КІ1", "4КІ2", "4КІ3"],
     "orderNote": "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно."},
    {"kind": "STUDY_MODE", "title": "4ЕА, 4ВЕ, 4РТ, 4ОП навчаються очно", "startsOn": "2026-09-01",
     "endsOn": "2026-10-31", "studyMode": "OFFLINE", "scope": "GROUPS",
     "groups": ["4ЕА1", "4ЕА2", "4ВЕ", "4РТ1", "4РТ2", "4ОП1", "4ОП2"],
     "orderNote": "4ЕА,4ВЕ,4РТ,4ОП навчаються очно з 01.09.26 по 31.10.26 р."},
    # практику 2КІ3 19.10–30.10 дає графік освітнього процесу (крок academic, НП); ручний період поруч із нею
    # показав би два блоки «Практика: …» і дві примітки в розпорядженнях на ці дні
]


def group_ids(admin: Client) -> dict[str, str]:
    return {g["code"]: g["id"] for g in admin.get("/api/public/groups")}


def step_periods(admin: Client) -> None:
    semester = semester_by_name(admin, SEMESTER_I)
    existing = {p["title"] for p in items(admin.get(f"/api/admin/calendar-periods?semesterId={semester['id']}"))}
    groups = group_ids(admin)
    for period in PERIODS:
        if period["title"] in existing:
            continue
        request = {key: value for key, value in period.items() if key != "groups"}
        request["semesterId"] = semester["id"]
        if "groups" in period:
            missing = [code for code in period["groups"] if code not in groups]
            if missing:
                raise SystemExit(f"period «{period['title']}»: unknown groups {missing} (run the import first)")
            request["groupIds"] = [groups[code] for code in period["groups"]]
        admin.post("/api/admin/calendar-periods", request)
        log(f"  period created: {period['title']}")
    log("periods: ok")


# ---------------------------------------------------------------- 5. academic calendar (before publication too)
ACADEMIC_CALENDAR = ROOT / "seed" / "academic-calendar-2026-27.xlsx"
ACADEMIC_YEAR = "2026/2027"


def imported_periods(admin: Client, semester_id: str) -> list[dict]:
    periods = items(admin.get(f"/api/admin/calendar-periods?semesterId={semester_id}"))
    return [p for p in periods if p.get("source") == "IMPORT"]


def report_errors(job: dict) -> list[str]:
    """Blocking rows of an import report, as the review screen shows them."""
    return [row.get("message") or f"{row['type']} {row.get('raw')}" for row in job.get("report") or []
            if row["severity"] == "ERROR"]


def calendar_check_line(check: dict | None) -> str:
    if not check:
        return "core was not asked (the report has blocking rows)"
    return (f"create {check['created']}, update {check['updated']}, delete {check['deleted']}, "
            f"unchanged {check['unchanged']}, manual {check['skippedManual']}, "
            f"outside the semesters {len(check.get('outOfSemester') or [])}")


def step_academic(admin: Client) -> None:
    """The academic calendar through the importer, like the dispatcher on «Імпорт»: upload, read the report
    (unknown codes or groups and clashes with live changes block it), apply. Skipped when core already holds
    imported periods: the importer's upsert would change nothing, and after publication a new run re-checks the
    calendar against the changes of the dispatcher (a clash stops it with the conflicts listed)."""
    semester = semester_by_name(admin, SEMESTER_I)
    existing = imported_periods(admin, semester["id"])
    if existing:
        log(f"academic: {len(existing)} imported periods already in {SEMESTER_I}, skipped")
        return
    job = admin.upload("/api/import/jobs", {"kind": "ACADEMIC_CALENDAR_XLSX",
                                            "options": json.dumps({"academicYear": ACADEMIC_YEAR})},
                       "file", ACADEMIC_CALENDAR, XLSX_MIME)
    job = wait_job(admin, job["id"], ("UPLOADED", "PARSING"))
    draft = job.get("draft") or {}
    log(f"  parsed: {job['status']}, {len(draft.get('groups') or [])} groups, "
        f"codes {' '.join(c['code'] for c in draft.get('codes') or [])}")
    if job["status"] != "READY":
        raise SystemExit(f"academic calendar job {job['id']} is {job['status']}: {report_errors(job)[:5] or job.get('error')}")
    warnings = sum(1 for row in job["report"] if row["severity"] == "WARN")
    log(f"  check: {len(draft.get('periods') or [])} periods; {calendar_check_line(draft.get('check'))}; "
        f"{warnings} warnings")
    admin.post(f"/api/import/jobs/{job['id']}/apply")
    job = wait_job(admin, job["id"], ("APPLYING",))
    if job["status"] != "APPLIED":
        raise SystemExit(f"academic calendar job {job['id']} is {job['status']}: "
                         f"{report_errors(job)[:5] or job.get('error')}")
    calendar = (job.get("result") or {}).get("calendar") or {}
    log(f"  applied: created {calendar.get('created')}, updated {calendar.get('updated')}, "
        f"deleted {calendar.get('deleted')}")
    log("academic: ok")


# ---------------------------------------------------------------- 6. publish
def lesson_body(lesson: dict, **changes) -> dict:
    body = {"weekday": lesson["weekday"], "pair": lesson["pair"], "parity": lesson["parity"],
            "groupId": lesson["group"]["id"], "subgroup": lesson["subgroup"],
            "disciplineId": lesson["discipline"]["id"],
            "teacherId": (lesson["teacher"] or {}).get("id"), "roomId": (lesson["room"] or {}).get("id"),
            "lessonKind": lesson["lessonKind"], "loadItemId": lesson["loadItemId"], "pinned": lesson["pinned"],
            "online": lesson["online"], "streamId": lesson["streamId"]}
    body.update(changes)
    return body


def label(lesson: dict) -> str:
    return f"{lesson['group']['code']} {lesson['discipline']['shortName']}"


def resolve_conflicts(admin: Client, version_id: str) -> None:
    """Clashes of the real Excel, resolved the way a dispatcher would in the editor.

    * the same teacher with the same discipline in one slot = a stream (one lesson for several groups);
    * a double-booked room stays with one lesson, the others lose the room (to be set by the dispatcher,
      never a guessed room); the 3rd course gives way first: it studied online in the file's period
      (order notes), so its dated lessons of Sep-Oct are online anyway;
    * a teacher in two places with different disciplines keeps the lesson that runs every week.
    """
    base = f"/api/admin/timetable/versions/{version_id}"
    for _ in range(5):
        hard = admin.get(f"{base}/conflicts")["hard"]
        if not hard:
            return
        lessons = {lesson["id"]: lesson for lesson in admin.get(f"{base}/lessons")}
        touched: set[str] = set()
        for conflict in hard:
            clashing = [lessons[i] for i in conflict["lessonIds"] if i in lessons and i not in touched]
            if len(clashing) < 2:
                continue
            same_teacher = len({(l["teacher"] or {}).get("id") for l in clashing}) == 1 and clashing[0]["teacher"]
            same_discipline = len({l["discipline"]["id"] for l in clashing}) == 1
            if same_teacher and same_discipline:
                stream = str(uuid.uuid4())
                for lesson in clashing:
                    admin.put(f"{base}/lessons/{lesson['id']}", lesson_body(lesson, streamId=stream))
                    touched.add(lesson["id"])
                log(f"    stream: {', '.join(label(l) for l in clashing)}")
            elif conflict["kind"] == "TEACHER":
                ordered = sorted(clashing, key=lambda l: (l["parity"] != "BOTH", l["group"]["code"]))
                for lesson in ordered[1:]:
                    admin.put(f"{base}/lessons/{lesson['id']}", lesson_body(lesson, teacherId=None))
                    touched.add(lesson["id"])
                    log(f"    teacher clash: {label(lesson)} — teacher left unassigned")
            elif conflict["kind"] == "ROOM":
                ordered = sorted(clashing, key=lambda l: (l["group"]["course"] == 3, l["group"]["code"]))
                for lesson in ordered[1:]:
                    admin.put(f"{base}/lessons/{lesson['id']}", lesson_body(lesson, roomId=None))
                    touched.add(lesson["id"])
                    log(f"    room {lesson['room']['name']}: kept by {label(ordered[0])}, "
                        f"{label(lesson)} left without a room")
            else:
                raise SystemExit(f"unexpected hard conflict: {conflict['message']}")
    raise SystemExit("hard conflicts remain after resolution")


def step_publish(admin: Client) -> None:
    semester = semester_by_name(admin, SEMESTER_I)
    version = imported_version(admin, semester["id"])
    if version is None:
        raise SystemExit("no imported version: run the import step first")
    if version["status"] == "DRAFT":
        log(f"  version «{version['name']}»: {version['hardConflicts']} hard conflicts")
        resolve_conflicts(admin, version["id"])
        result = admin.post(f"/api/admin/timetable/versions/{version['id']}/publish",
                            {"effectiveFrom": EFFECTIVE_FROM})
        log(f"  published from {EFFECTIVE_FROM}: {result['materializedLessons']} dated lessons")
    load = admin.get(f"/api/admin/load?semesterId={semester['id']}")
    if not items(load):
        derived = admin.post(f"/api/admin/load/derive-from-version/{version['id']}")
        log(f"  load derived from the version: {derived['created']} items")
    log("publish: ok")


# ---------------------------------------------------------------- 7. demo accounts
DEMO_TEACHERS = {"TEACHER_USERNAME": "Кренцін", "TEACHER2_USERNAME": "Непийвода"}


def teacher_id(admin: Client, short_name: str) -> str:
    matches = [t for t in admin.get("/api/public/teachers") if t["shortName"] == short_name]
    if len(matches) != 1:
        raise SystemExit(f"teacher «{short_name}»: {len(matches)} matches in the catalog")
    return matches[0]["id"]


def step_accounts(admin: Client) -> None:
    credentials = demo_credentials()
    for key, short_name in DEMO_TEACHERS.items():
        teacher = teacher_id(admin, short_name)
        current = admin.get(f"/api/admin/teachers/{teacher}/account")
        if current["linked"] and current["username"] == credentials[key]:
            continue
        admin.put(f"/api/admin/teachers/{teacher}/account", {"username": credentials[key]})
        log(f"  {short_name} linked to the account «{credentials[key]}»")
    teacher = Client(credentials["TEACHER_USERNAME"], credentials["TEACHER_PASSWORD"])
    me = teacher.get("/api/me")
    if not me.get("teacher"):
        raise SystemExit(f"/api/me of {credentials['TEACHER_USERNAME']} has no teacher: {me}")
    log(f"accounts: ok ({me['username']} = {me['teacher']['shortName']})")


# ---------------------------------------------------------------- 8. order №35 of 19.10.2026
# Каталог з Excel не має кількох людей і дисциплін з розпорядження: їх додає диспетчер.
MISSING_TEACHERS = [{"lastName": "Ємельяненко", "shortName": "Ємельяненко", "displayName": "Ємельяненко"},
                    {"lastName": "Яцуба", "shortName": "Яцуба", "displayName": "Яцуба"}]
MISSING_DISCIPLINES = [{"shortName": "ЗУ", "name": "Захист України"}]

# (group, pairs, kind, original subgroup, new discipline, new teacher, room, reason): рядки оригіналу
ORDER_35 = [
    ("1ТК1", (1, 4), "BLOCK", 0, "ЗУ", "Ємельяненко", "204", "OTHER"),
    ("1ОП2", (4, 4), "ADDITIONAL", 0, "БЖ", "Погосян Т.В.", "403", "OTHER"),
    ("1ТК1", (1, 1), "CANCEL", 1, None, None, None, "ABSENT"),
    ("1КІ2", (2, 2), "REPLACE_TEACHER", 1, None, "Прокопчук", "110", "ABSENT"),
    ("1М", (3, 3), "REPLACE_TEACHER", 1, None, "Прокопчук", "110", "ABSENT"),
    ("1ЕА2", (3, 3), "REPLACE_DISCIPLINE", 0, "Культ.", "Шароварська С.П.", "405", "MUTUAL"),
    ("2ОД", (4, 4), "ADDITIONAL", 0, "ІКТ", "Вернигора", "309", "OTHER"),
    ("2ОД", (6, 6), "REPLACE_DISCIPLINE", 0, "ОД", "Яцуба", "308", "MUTUAL"),
    ("2ОБ", (7, 7), "REPLACE_DISCIPLINE", 0, "ІКТ", "Вернигора", "304", "MUTUAL"),
    ("2ОБ", (8, 8), "REPLACE_DISCIPLINE", 0, "ТБО", "Фабіянська", "409", "MUTUAL"),
    ("2М", (8, 8), "REPLACE_DISCIPLINE", 0, "Укр.літ.", "Кучинська О.Г.", "303", "MUTUAL"),
    ("2М", (5, 5), "REPLACE_DISCIPLINE", 0, "Вс.іст.", "Бадунь", "409", "MUTUAL"),
    ("2Ф", (7, 7), "REPLACE_DISCIPLINE", 0, "ЕіФБ", "Кукурудзяк", "408", "MUTUAL"),
    ("3Ф", (4, 4), "CANCEL", 1, None, None, None, "ABSENT"),
    ("4КІ1", (2, 2), "REPLACE_DISCIPLINE", 0, "НДЕКС", "Непийвода", "6", "MUTUAL"),
    ("4КІ2", (4, 4), "REPLACE_DISCIPLINE", 0, "ОПЦЗ", "Ганза А.І.", "6", "MUTUAL"),
]
ORDER_35_NOTES = [
    "2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р.",
    "4ОП2 - Основи національного спротиву з 05.10.26р. по 16.10.26р. 401- аудиторія.",
    "3 курс групи 3Ф,3ОД,3ЕП,3ОБ,3РТ,3ТК,3ЕА,3ВЕ,3ОП з 1.09.26р. по 31.10.26.– Навчаються за розкладом дистанційно.",
    "Групи 4КІ з 01.09.26р. по 31.10.26 навчаються – дистанційно.",
    "4ЕА,4ВЕ,4РТ,4ОП навчаються очно з 01.09.26 по 31.10.26 р.",
]
ABSENT_TEACHER = "Крисак Л.В."


def ensure_catalog_for_order(admin: Client) -> None:
    teachers = {t["shortName"] for t in admin.get("/api/public/teachers")}
    for teacher in MISSING_TEACHERS:
        if teacher["shortName"] not in teachers:
            admin.post("/api/admin/teachers", teacher)
            log(f"  teacher added: {teacher['shortName']}")
    disciplines = {d["shortName"] for d in all_pages(admin, "/api/admin/disciplines")}
    for discipline in MISSING_DISCIPLINES:
        if discipline["shortName"] not in disciplines:
            admin.post("/api/admin/disciplines", discipline)
            log(f"  discipline added: {discipline['shortName']}")


def step_order35(admin: Client) -> None:
    semester = semester_by_name(admin, SEMESTER_I)
    published = [o for o in items(admin.get(f"/api/admin/orders?from={ORDER_DATE}&to={ORDER_DATE}&status=PUBLISHED"))
                 if o["kind"] == "ORDER"]
    if published:
        log(f"order35: already published as №{published[0]['number']}, skipped")
        return
    ensure_catalog_for_order(admin)
    numbering = admin.get(f"/api/admin/orders/numbering?semesterId={semester['id']}")
    if numbering["lastNumber"] is None and numbering["firstNumber"] != ORDER_NUMBER:
        admin.put("/api/admin/orders/numbering", {"semesterId": semester["id"], "firstNumber": ORDER_NUMBER})
        log(f"  numbering: the first order of the semester is №{ORDER_NUMBER} (№1–34 were on paper)")

    groups = group_ids(admin)
    teachers = {t["shortName"]: t["id"] for t in admin.get("/api/public/teachers")}
    disciplines = {d["shortName"]: d["id"] for d in all_pages(admin, "/api/admin/disciplines")}
    rooms = {r["name"]: r["id"] for r in admin.get("/api/public/rooms")}

    # відсутність Крисак Л. на 19.10: уражені пари і ранжовані варіанти заміни
    absent = teachers[ABSENT_TEACHER]
    absences = items(admin.get(f"/api/admin/absences?from={ORDER_DATE}&to={ORDER_DATE}&teacherId={absent}"))
    absence = absences[0] if absences else admin.post("/api/admin/absences", {
        "teacherId": absent, "dateFrom": ORDER_DATE, "dateTo": ORDER_DATE, "reasonCategory": "OTHER"})
    affected = admin.get(f"/api/admin/absences/{absence['id']}/affected")
    log(f"  absence of {ABSENT_TEACHER}: {len(affected)} affected lessons")

    existing = {(c["group"]["code"], c["pairFrom"], c["kind"]) for c in
                items(admin.get(f"/api/admin/changes?date={ORDER_DATE}")) if c["status"] != "CANCELLED"}
    for code, (pair_from, pair_to), kind, subgroup, discipline, teacher, room, reason in ORDER_35:
        if (code, pair_from, kind) in existing:
            continue
        request = {"kind": kind, "date": ORDER_DATE, "pairFrom": pair_from, "groupId": groups[code],
                   "subgroup": subgroup, "reasonCode": reason}
        if pair_to != pair_from:
            request["pairTo"] = pair_to
        if kind not in ("ADDITIONAL", "BLOCK"):
            day = admin.get(f"/api/public/schedule/group/{groups[code]}?from={ORDER_DATE}&to={ORDER_DATE}")["days"][0]
            lesson = next(l for l in day["lessons"] if l["pair"] == pair_from and l["subgroup"] == subgroup
                          and l["kind"] == "REGULAR")
            request["originalLessonId"] = lesson["id"]
            if reason == "ABSENT":
                request["absenceId"] = absence["id"]
        if discipline:
            request["newDisciplineId"] = disciplines[discipline]
        if teacher:
            request["newTeacherId"] = teachers[teacher]
        if room:
            request["newRoomId"] = rooms[room]
        change = admin.post("/api/admin/changes", request)
        pairs = f"{pair_from}-{pair_to}" if pair_to != pair_from else str(pair_from)
        log(f"  change {kind:<18} {code} {pairs}: {change['status']}")

    # підтвердження: Непийвода сама в кабінеті; решту (без облікових записів) підтверджує диспетчер
    credentials = demo_credentials()
    nepyivoda = Client(credentials["TEACHER2_USERNAME"], credentials["TEACHER2_PASSWORD"])
    for change in items(nepyivoda.get("/api/me/changes?status=PENDING_CONFIRMATION")):
        if change["date"] == ORDER_DATE:
            nepyivoda.post(f"/api/me/changes/{change['id']}/confirm", {"comment": "Згодна"})
            log(f"  {change['group']['code']} {change['pairFrom']}: confirmed by the teacher (nepyivoda)")
    for change in items(admin.get(f"/api/admin/changes?date={ORDER_DATE}&status=PENDING_CONFIRMATION")):
        admin.post(f"/api/admin/changes/{change['id']}/confirm")
    draft = admin.get(f"/api/admin/orders/draft?date={ORDER_DATE}")
    if draft["notes"] != ORDER_35_NOTES:
        draft = admin.put(f"/api/admin/orders/{draft['id']}/notes", {"notes": ORDER_35_NOTES, "version": draft["version"]})
    order = admin.post(f"/api/admin/orders/{draft['id']}/publish")
    log(f"  published: {order['title']}, {order['weekdayLabel']} - {order['parityLabel']}, "
        f"{len(order['items'])} rows, {len(order['notes'])} notes")
    if order["number"] != ORDER_NUMBER:
        raise SystemExit(f"the order got №{order['number']}, expected №{ORDER_NUMBER}")
    log("order35: ok")


# ---------------------------------------------------------------- 9. full names of the teachers
# Excel і розпорядження дають лише «Прізвище І.Б.» або прізвище. Повні імена взято з публічних сторінок
# коледжу (циклові комісії) і з «Кадрового забезпечення» ОПП; джерело кожного рядка — у файлі.
TEACHER_NAMES = ROOT / "seed" / "teacher-names.csv"
INITIAL = re.compile(r"([А-ЯІЇЄҐA-Z][а-яіїєґa-z]?)\.")


def initials_match(short_name: str, last_name: str, first_name: str, middle_name: str) -> bool:
    """«Овчар Ін.М.» fits Інна Миколаївна, not Іван; a short name without initials fits any name. A patronymic
    the public sources do not give (empty) is not checked against its initial."""
    if not short_name.startswith(last_name):
        return False
    initials = INITIAL.findall(short_name[len(last_name):])
    if len(initials) > 2:
        return False
    return all(name.startswith(initial) if name else index == 1
               for index, (initial, name) in enumerate(zip(initials, (first_name, middle_name))))


def teacher_names(path: Path = TEACHER_NAMES) -> list[dict[str, str]]:
    with path.open(encoding="utf-8", newline="") as file:
        rows = list(csv.DictReader(file))
    seen = set()
    for row in rows:
        short = row["short_name"]
        if short in seen:
            raise SystemExit(f"{path.name}: «{short}» is listed twice")
        seen.add(short)
        # the patronymic may be unknown: some teachers appear in public sources by first name only
        if not (row["first_name"] and row["source"].startswith("https://")):
            raise SystemExit(f"{path.name}: «{short}» needs a first name and a source URL")
        if not initials_match(short, row["last_name"], row["first_name"], row["middle_name"]):
            raise SystemExit(f"{path.name}: «{row['first_name']} {row['middle_name']}» does not fit «{short}»")
    return rows


def teacher_request(teacher: dict, first_name: str, middle_name: str) -> dict:
    """PUT body that changes only the name parts: short and display names stay as they are."""
    return {"lastName": teacher["lastName"], "firstName": first_name, "middleName": middle_name or None,
            "shortName": teacher["shortName"], "displayName": teacher["displayName"],
            "cycleCommissionId": (teacher["cycleCommission"] or {}).get("id"),
            "employment": teacher["employment"], "email": teacher["email"], "active": teacher["active"],
            "needsReview": teacher["needsReview"], "version": teacher["version"]}


def step_names(admin: Client) -> None:
    teachers = {t["shortName"]: t for t in all_pages(admin, "/api/admin/teachers")}
    filled = 0
    for row in teacher_names():
        teacher = teachers.get(row["short_name"])
        if teacher is None:
            raise SystemExit(f"teacher «{row['short_name']}» is missing (run the import and order35 first)")
        current = (teacher["firstName"] or "", teacher["middleName"] or "")
        if current == (row["first_name"], row["middle_name"]):
            continue
        # a patronymic found later completes a first name the seed set before; anything else was set by hand
        if any(current) and current != (row["first_name"], ""):
            log(f"  {row['short_name']}: kept «{' '.join(filter(None, current))}» set by hand")
            continue
        admin.put(f"/api/admin/teachers/{teacher['id']}", teacher_request(teacher, row["first_name"], row["middle_name"]))
        filled += 1
    log(f"names: ok ({filled} filled, {len(teachers)} teachers in the catalog)")


# the run order matters: periods and the academic calendar shape the publication, `recent` (by name) comes after all
STEPS = {"structure": step_structure, "calendar": step_calendar, "import": step_import, "periods": step_periods,
         "academic": step_academic, "publish": step_publish, "accounts": step_accounts, "order35": step_order35,
         "names": step_names}


# ---------------------------------------------------------------- extra: a living stand (by name only)
# `seed recent` fills the study days after order №35 with absences and published orders, up to yesterday of the
# stand's clock but not into 19–20.11, which scripts/demo_day.py owns (every kind of decision, Friday's order left
# for the dispatcher). Run it before demo_day.py, so the order numbers follow the dates. Every change is the system's own proposal for the absence (GET
# /absences/{id}/plan, the college's rules: another discipline of the same group by its own teacher, merged
# subgroups, a cancelled pair when nothing fits), accepted as a dispatcher would. Not in the default run: the
# dates follow the stand's clock, and the e2e suites expect a stand with order №35 only. Run `recent` before the
# Telegram chats are bound (or with the bot off): publishing past orders would message the bound group chats.
# Groups in a suspending period of the academic calendar (4ОП2 19.10–30.10, 4ВЕ 02.11–13.11, 4КІ1 from 16.11 on
# national resistance) have no dated lessons on those days, so absences and plans simply do not reach them.
RECENT_FROM = date(2026, 10, 20)  # the day after order №35
RECENT_UNTIL = date(2026, 11, 18)  # the day before scripts/demo_day.py's Thursday
# (category, share, study days of the absence)
ABSENCE_KINDS = [("SICK_LEAVE", 0.5, (2, 4)), ("PERSONAL", 0.2, (1, 1)), ("BUSINESS_TRIP", 0.2, (1, 2)),
                 ("OTHER", 0.1, (1, 1))]
DEMO_TEACHER_NAMES = ("Кренцін", "Непийвода")


def study_days(start: date, end: date) -> list[date]:
    """Weekdays from start to end; days without lessons (holidays) simply get an empty plan and no order."""
    days, day = [], start
    while day <= end:
        if day.weekday() < 5:
            days.append(day)
        day += timedelta(days=1)
    return days


def stand_today(admin: Client) -> date:
    return date.fromisoformat(admin.get("/api/public/meta")["today"])


def set_approval_mode(admin: Client, mode: str) -> str:
    settings = admin.get("/api/admin/settings")
    previous = settings["approvalMode"]
    if previous != mode:
        settings["approvalMode"] = mode
        admin.put("/api/admin/settings", settings)
    return previous


def option_request(option: dict, lesson: dict, day: date, absence: dict) -> dict | None:
    """The change a dispatcher creates from a proposed option (web: components/admin-daily/model.ts)."""
    kind = option["kind"]
    reason = "MUTUAL" if kind == "REPLACE_DISCIPLINE" else "ABSENT" if kind == "CANCEL" else (
        "BUSINESS_TRIP" if absence["reasonCategory"] == "BUSINESS_TRIP" else "ABSENT")
    request = {"kind": kind, "date": day.isoformat(), "pairFrom": lesson["pair"], "groupId": lesson["group"]["id"],
               "subgroup": lesson["subgroup"], "originalLessonId": lesson["id"], "reasonCode": reason,
               "absenceId": absence["id"]}
    room = {"newRoomId": option["suggestedRoom"]["id"]} if option.get("suggestedRoom") else {}
    if kind == "REPLACE_DISCIPLINE" and option.get("teacher") and option.get("discipline"):
        return {**request, "newTeacherId": option["teacher"]["id"], "newDisciplineId": option["discipline"]["id"], **room}
    if kind == "MERGE_SUBGROUPS" and option.get("swapLesson"):
        return {**request, "swapLessonId": option["swapLesson"]["id"], **room}
    if kind == "SWAP" and option.get("swapLesson"):
        return {**request, "swapLessonId": option["swapLesson"]["id"]}
    if kind == "CANCEL":
        return request
    return None


def apply_plan(admin: Client, absence: dict, day: date, limit: int | None = None) -> int:
    """Accepts the proposed option of every undecided lesson (the next ones if the proposal now conflicts)."""
    plan = admin.get(f"/api/admin/absences/{absence['id']}/plan?date={day.isoformat()}")
    created = 0
    for entry in plan["lessons"]:
        if limit is not None and created >= limit:
            break
        options = entry["options"]
        order = list(range(len(options)))
        if entry.get("proposedIndex") is not None:
            order.remove(entry["proposedIndex"])
            order.insert(0, entry["proposedIndex"])
        for index in order:
            request = option_request(options[index], entry["lesson"], day, absence)
            if request is None:
                continue
            try:
                admin.post("/api/admin/changes", request)
            except ApiError as failed:
                if failed.status in (400, 409, 422):
                    continue
                raise
            created += 1
            break
    return created


def teachers_with_lessons(admin: Client, day: date, candidates: list[dict], wanted: int, rng: random.Random,
                          minimum: int = 2) -> list[dict]:
    """Random teachers who have at least `minimum` regular lessons on the day (staff token: any date)."""
    chosen = []
    for teacher in rng.sample(candidates, len(candidates)):
        if len(chosen) >= wanted:
            break
        week = admin.get(f"/api/public/schedule/teacher/{teacher['id']}?from={day}&to={day}")
        lessons = [lesson for d in week["days"] for lesson in d["lessons"] if lesson["kind"] == "REGULAR"]
        if len(lessons) >= minimum:
            chosen.append(teacher)
    return chosen


def absence_for(admin: Client, teacher: dict, first: date, days: list[date], rng: random.Random,
                category: str | None = None) -> tuple[dict, list[date]]:
    if category is None:
        roll, category, span = rng.random(), None, (1, 1)
        for name, share, length in ABSENCE_KINDS:
            roll -= share
            if roll <= 0:
                category, span = name, length
                break
        category = category or "OTHER"
    else:
        span = next(length for name, _, length in ABSENCE_KINDS if name == category)
    start = days.index(first)
    covered = days[start:start + rng.randint(*span)]
    absence = admin.post("/api/admin/absences", {"teacherId": teacher["id"], "dateFrom": covered[0].isoformat(),
                                                 "dateTo": covered[-1].isoformat(), "reasonCategory": category})
    return absence, covered


def publish_day(admin: Client, day: date) -> dict | None:
    draft = admin.get(f"/api/admin/orders/draft?date={day.isoformat()}")
    if not draft["items"]:
        admin.call("DELETE", f"/api/admin/orders/{draft['id']}", expect_json=False)
        return None
    return admin.post(f"/api/admin/orders/{draft['id']}/publish")


def plan_days(admin: Client, days: list[date], publish_until: date, seed: int) -> None:
    """New absences on each day (1–3 teachers), the plan accepted for every day an absence covers, the order
    published for every day up to publish_until."""
    teachers = [t for t in admin.get("/api/public/teachers") if t["shortName"] not in DEMO_TEACHER_NAMES]
    for day in days:
        if day > publish_until:
            break
        published = [o for o in items(admin.get(f"/api/admin/orders?from={day}&to={day}&status=PUBLISHED"))
                     if o["kind"] == "ORDER"]
        if published:
            log(f"  {day}: order №{published[0]['number']} is already published, skipped")
            continue
        rng = random.Random(f"{seed}-{day.isoformat()}")
        # absences that cover the day (some began on an earlier day); new ones start once per day, so a second
        # run after a failure goes on where it stopped
        active = [a for a in items(admin.get(f"/api/admin/absences?from={day}&to={day}")) if a["status"] == "ACTIVE"]
        if not any(a["dateFrom"] == day.isoformat() for a in active):
            busy = {a["teacher"]["id"] for a in active}
            free = [t for t in teachers if t["id"] not in busy]
            for teacher in teachers_with_lessons(admin, day, free, rng.choice((1, 1, 2, 2, 3)), rng):
                active.append(absence_for(admin, teacher, day, days, rng)[0])
        created = sum(apply_plan(admin, absence, day) for absence in active)
        order = publish_day(admin, day)
        if order:
            log(f"  {day}: {created} changes, order №{order['number']} ({len(order['items'])} rows)")
        else:
            log(f"  {day}: no lessons to change, no order")


def step_recent(admin: Client) -> None:
    last = min(stand_today(admin) - timedelta(days=1), RECENT_UNTIL)
    previous = set_approval_mode(admin, "AUTO")  # past days: nobody is asked any more
    try:
        plan_days(admin, study_days(RECENT_FROM, last), last, seed=7)
    finally:
        set_approval_mode(admin, previous)
    log(f"recent: ok (study days {RECENT_FROM} – {last})")


EXTRA_STEPS = {"recent": step_recent}


def main(argv: list[str]) -> None:
    credentials = demo_credentials()
    admin = Client(credentials["ADMIN_USERNAME"], credentials["ADMIN_PASSWORD"])
    for name in argv or list(STEPS):
        log(f"== {name}")
        (STEPS.get(name) or EXTRA_STEPS[name])(admin)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
