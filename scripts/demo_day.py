#!/usr/bin/env python3
"""Demo scenario for clicking through the daily workflow: Thursday 19.11.2026 and Friday 20.11.2026.

Everything goes through the REST API the way a dispatcher (and the teacher «krentsin» in his cabinet) would do it,
so the scenario also exercises the substitution workflow. Run it on the demo stack after `scripts/seed_demo.py`.

  Friday 20.11 (the stand clock may be moved to 2026-11-20T10:00, pair 2 in progress):
    absences of Непийвода, Ординський, Стахова, Голєва, Крисак Л.В., Марцинюк (pair 1 only) and Паламарчук (pair 3
    only) and a decision of every kind:
    REPLACE_DISCIPLINE (the group's own teacher of another discipline), REPLACE_TEACHER, SWAP, MERGE_SUBGROUPS,
    CANCEL, ROOM_CHANGE, ADDITIONAL, BLOCK, DISCIPLINE_FINISHED; for Кренцін two NOTIFY assignments, one request
    left PENDING_CONFIRMATION on pair 3 (he declined, the dispatcher asked again) and one he declined; four lessons stay
    «Потрібне рішення». The order of Friday is NOT published.
  Thursday 19.11: absences of Колесник, Можаровська (pair 1), Феліксов and Непийвода, decisions of most kinds,
    Кренцін answers «Добре», and with --publish the order (or the amendment of an already published order) of the
    date is published. Publication sends Telegram messages, so it also needs every bound chat to be allowed with
    --allow-chat (the masked chat id as GET /api/notify/bindings shows it); any other bound chat aborts it.

4КІ1 is on national resistance from 16.11 to 27.11 (seed step `academic`, the college's academic calendar): it has
no pairs on both days, so the scenario uses 4КІ2, 4КІ3 and other groups that study then.

Before the data the script turns on «Показувати повні імена викладачів» (setting showTeacherFullNames), so public
cards show «Прізвище Ім'я» where the first name is known; grids keep the surname. Turn it off in /admin/settings.

The script is idempotent: an absence of the teacher on the date, or a live (not cancelled) change of the same kind
for the same group, pair and subgroup is reused, never duplicated. It prints a summary of both days at the end.

  python scripts/demo_day.py                                   # create the data, publish nothing
  python scripts/demo_day.py --publish --allow-chat='•••1234' --allow-chat='-•••5678'   # '=': ids may start with -
  python scripts/demo_day.py --summary                         # only print what exists

API_URL overrides the gateway (default http://127.0.0.1:8080: on Windows `localhost` may resolve to ::1).
"""
from __future__ import annotations

import argparse
import os
import sys
from dataclasses import dataclass
from pathlib import Path

os.environ.setdefault("API_URL", "http://127.0.0.1:8080")
sys.path.insert(0, str(Path(__file__).resolve().parent))
import rozklad_auth  # noqa: E402
import seed_demo as seed  # noqa: E402  (shared HTTP client)

THURSDAY = "2026-11-19"
FRIDAY = "2026-11-20"
SUBSTITUTE = "Кренцін"  # the demo teacher «krentsin»: his Telegram receives the requests


@dataclass(frozen=True)
class Absence:
    date: str
    teacher: str
    reason: str
    pairs: tuple[int, ...] = ()
    comment: str | None = None


@dataclass(frozen=True)
class Decision:
    """One change. `answer` is what Кренцін does afterwards: decline, pending (decline + ask again) or ok."""
    date: str
    kind: str
    group: str
    pair: int
    subgroup: int = 0
    absent: str | None = None          # teacher whose absence the change belongs to
    discipline: str | None = None
    teacher: str | None = None
    rooms: tuple[str, ...] = ()        # candidates, the first one without a conflict is used
    swap_pair: int | None = None       # SWAP: the pair that moves in; MERGE: the same pair
    swap_subgroup: int = 0
    pair_to: int | None = None
    reason: str = "ABSENT"
    reason_text: str | None = None
    answer: str | None = None
    note: str = ""


ABSENCES = [
    Absence(FRIDAY, "Непийвода", "SICK_LEAVE", comment="Лікарняний"),
    Absence(FRIDAY, "Ординський", "PERSONAL"),
    Absence(FRIDAY, "Стахова О.А.", "BUSINESS_TRIP", comment="Олімпіада з математики"),
    Absence(FRIDAY, "Голєва Т.В.", "PERSONAL"),
    Absence(FRIDAY, "Крисак Л.В.", "SICK_LEAVE"),
    Absence(FRIDAY, "Марцинюк Т.Ю.", "OTHER", pairs=(1,), comment="Запізниться на першу пару"),
    Absence(FRIDAY, "Паламарчук", "PERSONAL", pairs=(3,)),
    Absence(THURSDAY, "Колесник А.", "VACATION"),
    Absence(THURSDAY, "Можаровська О.Е.", "PERSONAL", pairs=(1,)),
    Absence(THURSDAY, "Феліксов Ю.Л.", "SICK_LEAVE"),
    Absence(THURSDAY, "Непийвода", "BUSINESS_TRIP"),
]

DECISIONS = [
    # ---- Friday 20.11 (not published)
    Decision(FRIDAY, "REPLACE_DISCIPLINE", "4КІ2", 2, absent="Ординський", discipline="СТП", teacher=SUBSTITUTE,
             reason="MUTUAL", note="NOTIFY: Кренцін is informed in Telegram"),
    Decision(FRIDAY, "REPLACE_DISCIPLINE", "4КІ3", 3, absent="Ординський", discipline="СТП", teacher=SUBSTITUTE,
             reason="MUTUAL", answer="decline", note="Кренцін declines: the lesson needs a decision again"),
    Decision(FRIDAY, "REPLACE_DISCIPLINE", "4КІ2", 1, absent="Непийвода", discipline="СТП", teacher=SUBSTITUTE,
             reason="MUTUAL", note="NOTIFY: the pair is over by 10:00, so no request can be sent for it"),
    # a request can be sent only before the pair starts (CHANGE_LESSON_OVER): the pending one is on pair 3
    Decision(FRIDAY, "REPLACE_DISCIPLINE", "4КІ2", 3, absent="Паламарчук", discipline="СТП", teacher=SUBSTITUTE,
             reason="MUTUAL", answer="pending", note="declined, asked again: PENDING_CONFIRMATION"),
    Decision(FRIDAY, "ROOM_CHANGE", "4ЕА2", 1, rooms=("309", "305", "210"), reason="OTHER",
             reason_text="Ремонт в аудиторії"),
    Decision(FRIDAY, "REPLACE_DISCIPLINE", "1ВЕ", 1, absent="Стахова О.А.", discipline="ФіА", teacher="Грабенко О.А.",
             reason="MUTUAL"),
    Decision(FRIDAY, "ADDITIONAL", "1ВЕ", 3, discipline="ФК", teacher="Феліксов Ю.Л.", rooms=("с.з.", "с.п."),
             reason="OTHER", note="«Додати пару»"),
    Decision(FRIDAY, "CANCEL", "1ОБ", 3, absent="Стахова О.А.", reason="BUSINESS_TRIP"),
    Decision(FRIDAY, "SWAP", "4ВЕ", 1, absent="Голєва Т.В.", swap_pair=3, reason="ABSENT"),
    Decision(FRIDAY, "SWAP", "4ЕА1", 3, absent="Голєва Т.В.", swap_pair=4, reason="ABSENT"),
    Decision(FRIDAY, "MERGE_SUBGROUPS", "3Ф", 1, subgroup=1, absent="Крисак Л.В.", swap_pair=1, swap_subgroup=2),
    Decision(FRIDAY, "REPLACE_TEACHER", "3ВЕ", 1, subgroup=2, absent="Марцинюк Т.Ю.", teacher="Михальчук",
             note="manual «Інший викладач…»"),
    Decision(FRIDAY, "BLOCK", "4ОП1", 2, pair_to=3, discipline="ЗУ", teacher="Ємельяненко", rooms=("204", "205"),
             reason="OTHER"),
    Decision(FRIDAY, "DISCIPLINE_FINISHED", "2М", 4, reason="FINISHED"),
    # ---- Thursday 19.11 (published with --publish)
    Decision(THURSDAY, "REPLACE_DISCIPLINE", "4КІ2", 2, absent="Колесник А.", discipline="СТП", teacher=SUBSTITUTE,
             reason="MUTUAL", answer="ok", note="Кренцін answers «Добре»"),
    Decision(THURSDAY, "MERGE_SUBGROUPS", "1ОП2", 1, subgroup=1, absent="Можаровська О.Е.", swap_pair=1,
             swap_subgroup=2),
    Decision(THURSDAY, "SWAP", "4КІ3", 1, absent="Колесник А.", swap_pair=4),
    Decision(THURSDAY, "CANCEL", "4КІ3", 3, absent="Непийвода", reason="BUSINESS_TRIP"),
    # the moved ФіА must not stay in the sports hall of the cancelled ФК: the dispatcher gives it its own room
    Decision(THURSDAY, "SWAP", "1ВЕ", 1, absent="Феліксов Ю.Л.", swap_pair=2, rooms=("408",)),
    Decision(THURSDAY, "ROOM_CHANGE", "1ВЕ", 4, rooms=("412", "404", "410", "305"), reason="OTHER"),
    Decision(THURSDAY, "ADDITIONAL", "1ВЕ", 5, discipline="БіЕ", teacher="Кучер О.В.", rooms=("406", "404", "412"),
             reason="OTHER"),
    Decision(THURSDAY, "DISCIPLINE_FINISHED", "1КІ1", 2, discipline="Матем.", teacher="Овчар Ін.М.",
             reason="FINISHED"),
]

def full_names_update(settings: dict) -> dict | None:
    """PUT body of /api/admin/settings that turns on full teacher names, or None when they are already on."""
    if settings.get("showTeacherFullNames"):
        return None
    return {**settings, "showTeacherFullNames": True}


LIVE = ("DRAFT", "PENDING_CONFIRMATION", "CONFIRMED", "DECLINED", "APPLIED")


class Scenario:
    def __init__(self, admin: seed.Client, teacher: seed.Client):
        self.admin = admin
        self.teacher = teacher
        self.groups = seed.group_ids(admin)
        self.teachers = {t["shortName"]: t["id"] for t in admin.get("/api/public/teachers")}
        self.rooms = {r["name"]: r["id"] for r in admin.get("/api/public/rooms")}
        self.disciplines: dict[str, list[str]] = {}
        for d in seed.all_pages(admin, "/api/admin/disciplines"):
            self.disciplines.setdefault(d["shortName"], []).append(d["id"])
        self.absence_ids: dict[tuple[str, str], str] = {}

    # ---------------------------------------------------------------- lookups
    def teacher_id(self, name: str) -> str:
        if name not in self.teachers:
            raise SystemExit(f"unknown teacher «{name}»")
        return self.teachers[name]

    def discipline_id(self, group: str, date: str, name: str) -> str:
        # the group's own discipline first (the same short name may exist twice in the catalog)
        week = self.admin.get(f"/api/public/schedule/group/{self.groups[group]}?from=2026-11-16&to=2026-11-20")
        for day in week["days"]:
            for lesson in day["lessons"]:
                if lesson["discipline"]["shortName"] == name:
                    return lesson["discipline"]["id"]
        ids = self.disciplines.get(name, [])
        if len(ids) != 1:
            raise SystemExit(f"discipline «{name}» for {group}: {len(ids)} candidates in the catalog")
        return ids[0]

    def lesson(self, group: str, date: str, pair: int, subgroup: int) -> dict:
        day = self.admin.get(f"/api/public/schedule/group/{self.groups[group]}?from={date}&to={date}")["days"][0]
        for lesson in day["lessons"]:
            if lesson["pair"] == pair and lesson["subgroup"] == subgroup and lesson["kind"] == "REGULAR":
                return lesson
        raise LookupError(f"{group} {date}: no regular lesson at pair {pair}, subgroup {subgroup}")

    # ---------------------------------------------------------------- settings
    def ensure_full_names(self) -> None:
        body = full_names_update(self.admin.get("/api/admin/settings"))
        if body is None:
            print("  full teacher names: already on", flush=True)
            return
        self.admin.put("/api/admin/settings", body)
        print("  full teacher names: turned on", flush=True)

    # ---------------------------------------------------------------- absences
    def ensure_absences(self) -> None:
        for absence in ABSENCES:
            teacher = self.teacher_id(absence.teacher)
            found = seed.items(self.admin.get(
                f"/api/admin/absences?from={absence.date}&to={absence.date}&teacherId={teacher}"))
            if found:
                self.absence_ids[(absence.date, absence.teacher)] = found[0]["id"]
                continue
            body = {"teacherId": teacher, "dateFrom": absence.date, "dateTo": absence.date,
                    "reasonCategory": absence.reason, "comment": absence.comment}
            if absence.pairs:
                body["pairs"] = list(absence.pairs)
            created = self.admin.post("/api/admin/absences", body)
            self.absence_ids[(absence.date, absence.teacher)] = created["id"]
            pairs = f", пари {','.join(map(str, absence.pairs))}" if absence.pairs else ""
            print(f"  absence created: {absence.date} {absence.teacher} ({absence.reason}{pairs})", flush=True)

    # ---------------------------------------------------------------- changes
    def existing(self, decision: Decision) -> dict | None:
        for change in seed.items(self.admin.get(f"/api/admin/changes?date={decision.date}")):
            if (change["kind"] == decision.kind and change["group"]["code"] == decision.group
                    and change["pairFrom"] == decision.pair and change["subgroup"] == decision.subgroup
                    and change["status"] in LIVE):
                return change
        return None

    def request(self, decision: Decision) -> dict:
        body = {"kind": decision.kind, "date": decision.date, "pairFrom": decision.pair,
                "groupId": self.groups[decision.group], "subgroup": decision.subgroup,
                "reasonCode": decision.reason}
        if decision.reason_text:
            body["reasonText"] = decision.reason_text
        if decision.pair_to:
            body["pairTo"] = decision.pair_to
        if decision.kind not in ("ADDITIONAL", "BLOCK"):
            body["originalLessonId"] = self.lesson(decision.group, decision.date, decision.pair,
                                                   decision.subgroup)["id"]
        if decision.swap_pair:
            body["swapLessonId"] = self.lesson(decision.group, decision.date, decision.swap_pair,
                                               decision.swap_subgroup)["id"]
        if decision.absent:
            body["absenceId"] = self.absence_ids[(decision.date, decision.absent)]
        if decision.discipline:
            body["newDisciplineId"] = self.discipline_id(decision.group, decision.date, decision.discipline)
        if decision.teacher:
            body["newTeacherId"] = self.teacher_id(decision.teacher)
        return body

    def create(self, decision: Decision) -> dict | None:
        body = self.request(decision)
        candidates = decision.rooms or (None,)
        last = None
        for room in candidates:
            if room:
                body["newRoomId"] = self.rooms[room]
            try:
                return self.admin.post("/api/admin/changes", body)
            except seed.ApiError as failed:
                last = failed
                if failed.status != 409:
                    break
        problem = last.problem() if last else {}
        print(f"  !! {decision.date} {decision.kind} {decision.group} {decision.pair}: {last.status} "
              f"{problem.get('detail') or problem.get('title')} {problem.get('conflicts') or ''}", flush=True)
        return None

    def room_of_existing(self, decision: Decision, change: dict) -> dict:
        """An existing change of an earlier run without the room the scenario wants gets it (PUT = room only)."""
        if not decision.rooms or decision.kind in ("ADDITIONAL", "BLOCK", "ROOM_CHANGE"):
            return change
        room = ((change.get("new") or {}).get("room") or {}).get("name")
        if room in decision.rooms or change["status"] not in ("DRAFT", "PENDING_CONFIRMATION", "CONFIRMED"):
            return change
        return self.admin.put(f"/api/admin/changes/{change['id']}", {
            "newRoomId": self.rooms[decision.rooms[0]], "reasonCode": change["reasonCode"],
            "reasonText": change.get("reasonText"), "version": change["version"]})

    def follow_up(self, decision: Decision, change: dict) -> dict:
        status = change["status"]
        if decision.answer == "decline" and status in ("CONFIRMED", "PENDING_CONFIRMATION"):
            change = self.teacher.post(f"/api/me/changes/{change['id']}/decline",
                                       {"comment": "Маю консультацію з дипломниками"})
        elif decision.answer == "pending" and status != "PENDING_CONFIRMATION":
            if status == "CONFIRMED":
                change = self.teacher.post(f"/api/me/changes/{change['id']}/decline", {"comment": "Не встигаю"})
            if change["status"] == "DECLINED":
                change = self.admin.post(f"/api/admin/changes/{change['id']}/request-confirmation")
        elif decision.answer == "ok" and change.get("teacherAnswer") is None and status in (
                "CONFIRMED", "PENDING_CONFIRMATION"):
            change = self.teacher.post(f"/api/me/changes/{change['id']}/confirm", {"comment": "Добре"})
        elif decision.answer is None and status == "PENDING_CONFIRMATION" and decision.date == THURSDAY:
            # the stand may run in CONFIRM mode: the dispatcher confirms for the teacher so the order is complete
            change = self.admin.post(f"/api/admin/changes/{change['id']}/confirm")
        return change

    def ensure_decisions(self) -> None:
        for decision in DECISIONS:
            change = self.existing(decision)
            fresh = change is None
            if fresh:
                try:
                    change = self.create(decision)
                except LookupError as missing:
                    print(f"  !! {missing}", flush=True)
                    continue
                if change is None:
                    continue
            if change["status"] != "APPLIED":
                change = self.room_of_existing(decision, change)
                change = self.follow_up(decision, change)
            print(f"  {'created' if fresh else 'exists '} {decision.date} {decision.kind:<19} {decision.group} "
                  f"{decision.pair}: {change['status']}", flush=True)

    # ---------------------------------------------------------------- order of Thursday
    def publish_thursday(self, allowed_chats: set[str]) -> None:
        draft = self.admin.get(f"/api/admin/orders/draft?date={THURSDAY}")
        if draft["status"] != "DRAFT":
            print(f"  {THURSDAY}: nothing new to publish (latest: {draft['title']})", flush=True)
            return
        if not draft["items"]:
            print(f"  {THURSDAY}: the draft has no rows, nothing to publish", flush=True)
            return
        bindings = self.admin.get("/api/notify/bindings")
        foreign = [b for b in bindings if b["chatId"] not in allowed_chats]
        if foreign:
            listed = ", ".join(f"{b['type']} {b['chatId']} «{b['title']}»" for b in foreign)
            raise SystemExit(f"publication aborted: chats not allowed with --allow-chat are bound: {listed}")
        order = self.admin.post(f"/api/admin/orders/{draft['id']}/publish")
        print(f"  published: {order['title']} ({len(order['items'])} rows)", flush=True)

    # ---------------------------------------------------------------- summary
    def summary(self) -> None:
        for date in (THURSDAY, FRIDAY):
            print(f"\n== {date}")
            for change in sorted(seed.items(self.admin.get(f"/api/admin/changes?date={date}")),
                                 key=lambda c: (c["group"]["code"], c["pairFrom"], c["subgroup"])):
                if change["status"] == "CANCELLED":
                    continue
                pairs = (f"{change['pairFrom']}-{change['pairTo']}" if change["pairTo"] != change["pairFrom"]
                         else str(change["pairFrom"]))
                sub = f"/{change['subgroup']}" if change["subgroup"] else ""
                old, new = change.get("original") or {}, change.get("new") or {}
                before = " ".join(filter(None, [(old.get("discipline") or {}).get("shortName"),
                                                (old.get("teacher") or {}).get("shortName")]))
                after = " ".join(filter(None, [(new.get("discipline") or {}).get("shortName"),
                                               (new.get("teacher") or {}).get("shortName"),
                                               (new.get("room") or {}).get("name")]))
                answer = f", answer {change['teacherAnswer']}" if change.get("teacherAnswer") else ""
                print(f"  {change['group']['code']:<5} {pairs:<4}{sub:<3} {change['kind']:<19} "
                      f"{change['status']:<21} {before or '-'} -> {after or 'Пари не буде'}"
                      f" [{change['approvalMode']}{answer}]")
            undecided = []
            for (day, teacher), absence_id in self.absence_ids.items():
                if day != date:
                    continue
                for item in self.admin.get(f"/api/admin/absences/{absence_id}/affected"):
                    decision = item.get("decision")
                    if not decision or decision.get("status") == "DECLINED":
                        lesson = item["lesson"]
                        sub = f"/{lesson['subgroup']}" if lesson["subgroup"] else ""
                        state = " (declined)" if decision else ""
                        undecided.append(f"{lesson['group']['code']} {lesson['pair']}{sub} "
                                         f"{lesson['discipline']['shortName']} {teacher}{state}")
            print(f"  «Потрібне рішення»: {'; '.join(undecided) or 'none'}")
            orders = seed.items(self.admin.get(f"/api/admin/orders?from={date}&to={date}"))
            print("  orders: " + (", ".join(f"{o['kind']} {o['status']} №{o.get('number')}" for o in orders)
                                  or "none"))

    def load_absences(self) -> None:
        for absence in ABSENCES:
            found = seed.items(self.admin.get(f"/api/admin/absences?from={absence.date}&to={absence.date}"
                                              f"&teacherId={self.teacher_id(absence.teacher)}"))
            if found:
                self.absence_ids[(absence.date, absence.teacher)] = found[0]["id"]


def main(argv: list[str]) -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--publish", action="store_true", help="publish the order of Thursday 19.11")
    parser.add_argument("--allow-chat", action="append", default=[],
                        help="masked chat id (as /api/notify/bindings shows it) that may receive the order")
    parser.add_argument("--summary", action="store_true", help="only print the summary")
    args = parser.parse_args(argv)
    admin = seed.Client(*rozklad_auth.demo_user("admin"))
    teacher = seed.Client(*rozklad_auth.demo_user("krentsin"))
    scenario = Scenario(admin, teacher)
    if args.summary:
        scenario.load_absences()
    else:
        print("== settings")
        scenario.ensure_full_names()
        print("== absences")
        scenario.ensure_absences()
        print("== decisions")
        scenario.ensure_decisions()
        if args.publish:
            print("== publication")
            scenario.publish_thursday(set(args.allow_chat))
    scenario.summary()


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    main(sys.argv[1:])
