#!/usr/bin/env python3
"""Seeds the running local stack with the real college data through the system's own REST API.

Nothing is written to a database directly: every step is a call a dispatcher (or the importer) would
make, so the seed also exercises the APIs. The steps are idempotent; a second run skips what exists.

  1. structure: departments, specialties, cycle commissions (admin catalog CRUD)
  2. calendar:  semesters I/II 2026/2027 (week 1 = чисельник), bell profile check
  3. import:    seed/schedule-2025-26.xlsx -> importer job -> review -> apply -> draft version
  4. publish:   the imported version, effective 2026-09-01; load items derived from it
  5. periods:   calendar periods from the notes of order №35
  6. accounts:  demo Keycloak accounts linked to the teachers «Кренцін» and «Непийвода»
  7. order 35:  the changes of 19.10.2026 -> confirmations -> notes -> publication as №35

Usage (stack running, see scripts/dev-stack.sh):  python scripts/seed_demo.py [step ...]
Demo credentials come from infra/keycloak/demo-users.env (test values only).
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
API = os.environ.get("API_URL", "http://localhost:8080")
KEYCLOAK = os.environ.get("KEYCLOAK_URL", "http://localhost:8180")
REALM = "rozklad"
SEMESTER_I = "I семестр 2026/2027"
VERSION_NAME = "Розклад 2025/26 (імпорт з Excel)"
EFFECTIVE_FROM = "2026-09-01"
ORDER_DATE = "2026-10-19"
ORDER_NUMBER = 35


def demo_credentials() -> dict[str, str]:
    values = {}
    for line in (ROOT / "infra" / "keycloak" / "demo-users.env").read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            key, value = line.split("=", 1)
            values[key.strip()] = value.strip()
    return values


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
        self.password = password
        self.token = None
        self.expires = 0.0

    def _token(self) -> str:
        if self.token is None or time.time() > self.expires - 30:
            form = urllib.parse.urlencode({"client_id": "rozklad-dev-cli", "grant_type": "password",
                                           "username": self.username, "password": self.password}).encode()
            url = f"{KEYCLOAK}/realms/{REALM}/protocol/openid-connect/token"
            with urllib.request.urlopen(urllib.request.Request(url, data=form), timeout=30) as response:
                answer = json.load(response)
            self.token = answer["access_token"]
            self.expires = time.time() + answer["expires_in"]
        return self.token

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
    log(f"  applied: catalog created {catalog['created']}, version «{timetable['name']}» "
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
    {"kind": "PRACTICE", "title": "Електрорадіомонтажна практика 2КІ3", "startsOn": "2026-10-19",
     "endsOn": "2026-10-30", "scope": "GROUPS", "groups": ["2КІ3"],
     "orderNote": "2КІ3 – Електрорадіомонтажна практика з 19.10.26р. по 30.10.26 р."},
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


# ---------------------------------------------------------------- 5. publish
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


# ---------------------------------------------------------------- 6. demo accounts
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


# ---------------------------------------------------------------- 7. order №35 of 19.10.2026
# Каталог з Excel не має кількох людей і дисциплін з розпорядження: їх додає диспетчер.
MISSING_TEACHERS = [{"lastName": "Ємельяненко", "shortName": "Ємельяненко", "displayName": "Ємельяненко"},
                    {"lastName": "Ящуба", "shortName": "Ящуба", "displayName": "Ящуба"}]
MISSING_DISCIPLINES = [{"shortName": "ЗУ", "name": "Захист України"}]
# «Балунь» у розпорядженні є опискою: Вс.іст. за розкладом веде Бадунь
TEACHER_ALIASES = {"Бадунь": "Балунь"}

# (group, pairs, kind, original subgroup, new discipline, new teacher, room, reason): рядки оригіналу
ORDER_35 = [
    ("1ТК1", (1, 4), "BLOCK", 0, "ЗУ", "Ємельяненко", "204", "OTHER"),
    ("1ОП2", (4, 4), "ADDITIONAL", 0, "БЖ", "Погосян Т.В.", "403", "OTHER"),
    ("1ТК1", (1, 1), "CANCEL", 1, None, None, None, "ABSENT"),
    ("1КІ2", (2, 2), "REPLACE_TEACHER", 1, None, "Прокопчук", "110", "ABSENT"),
    ("1М", (3, 3), "REPLACE_TEACHER", 1, None, "Прокопчук", "110", "ABSENT"),
    ("1ЕА2", (3, 3), "REPLACE_DISCIPLINE", 0, "Культ.", "Шароварська С.П.", "405", "MUTUAL"),
    ("2ОД", (4, 4), "ADDITIONAL", 0, "ІКТ", "Вернигора", "309", "OTHER"),
    ("2ОД", (6, 6), "REPLACE_DISCIPLINE", 0, "ОД", "Ящуба", "308", "MUTUAL"),
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
    for short_name, alias in TEACHER_ALIASES.items():
        teacher = teacher_id(admin, short_name)
        if alias not in {a["alias"] for a in admin.get(f"/api/admin/teachers/{teacher}/aliases")}:
            admin.post(f"/api/admin/teachers/{teacher}/aliases", {"alias": alias})
            log(f"  alias «{alias}» -> {short_name}")


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
        draft = admin.put(f"/api/admin/orders/{draft['id']}/notes", {"notes": ORDER_35_NOTES})
    order = admin.post(f"/api/admin/orders/{draft['id']}/publish")
    log(f"  published: {order['title']}, {order['weekdayLabel']} - {order['parityLabel']}, "
        f"{len(order['items'])} rows, {len(order['notes'])} notes")
    if order["number"] != ORDER_NUMBER:
        raise SystemExit(f"the order got №{order['number']}, expected №{ORDER_NUMBER}")
    log("order35: ok")


STEPS = {"structure": step_structure, "calendar": step_calendar, "import": step_import,
         "periods": step_periods, "publish": step_publish, "accounts": step_accounts, "order35": step_order35}


def main(argv: list[str]) -> None:
    credentials = demo_credentials()
    admin = Client(credentials["ADMIN_USERNAME"], credentials["ADMIN_PASSWORD"])
    for name in argv or list(STEPS):
        log(f"== {name}")
        STEPS[name](admin)


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
