#!/usr/bin/env python3
"""Export replay cases of the real decisions of order No. 35 (19.10.2026) for OptionRankerReplayTest.

Read-only: every query runs through `docker exec ... psql` with default_transaction_read_only=on against
the running core database. Standard library only.

For every lesson the dispatcher decided about on 19.10.2026 (changes with an original lesson in the
published order), the script rebuilds "state A": the published timetable of the day before any decision
of the day, as the substitute ranking's input (RankingInput JSON), together with the real choice.

State A, as the gatherer would read it before the first decision:
  * lessons = TEMPLATE lessons of the day (whatever their status now), no open changes, no declines;
  * the teachers whose lessons the order replaced or cancelled count as absent the whole day (the
    dispatcher knew who was missing; only Крисак has an absence record), plus active absences;
  * accounting from TEMPLATE lessons of the semester (whatever their status now), "today" = 16.10.2026
    (the Friday before), so the day is in the future and nothing counts as past;
  * no cover history (order No. 35 holds the only real decisions in the data);
  * the usual room of a teacher for a discipline in a group = the most frequent room of its TEMPLATE lessons.

Usage: python scripts/eval/export_replay_cases.py [output.json]
"""
import csv
import io
import json
import subprocess
import sys
from collections import Counter, defaultdict
from datetime import date

DATE = "2026-10-19"
TODAY = "2026-10-16"
HOURS_PER_PAIR = 2
DEFAULT_OUT = "services/core/src/test/resources/replay/order35.json"


def q(sql):
    out = subprocess.run(
        ["docker", "exec", "-i", "-e", "PGOPTIONS=-c default_transaction_read_only=on",
         "rozklad-postgres-1", "psql", "-U", "postgres", "-d", "core", "--csv", "-v", "ON_ERROR_STOP=1"],
        input=sql.encode("utf-8"), capture_output=True, check=True).stdout.decode("utf-8")
    return list(csv.DictReader(io.StringIO(out)))


def none(value):
    return value if value not in ("", None) else None


def main(out_path):
    semester = q(f"select id from semester where starts_on <= '{DATE}' and ends_on >= '{DATE}'")[0]["id"]
    teachers = {r["id"]: r["short_name"] for r in q("select id, short_name from teacher")}
    disciplines = {r["id"]: r["short_name"] for r in q("select id, short_name from discipline")}
    groups = {r["id"]: r["code"] for r in q("select id, code from student_group")}
    rooms = {r["id"]: r["type"] for r in q("select id, type from room")}

    decisions = q(f"""
        select c.id, c.kind, c.pair_from::int pair, c.group_id, c.subgroup_number::int sg, c.reason_code,
               c.original_lesson_id, c.orig_teacher_id, c.orig_discipline_id, c.new_teacher_id, c.new_discipline_id
        from change c join order_document o on o.id = c.order_id
        where c.date = '{DATE}' and o.status = 'PUBLISHED' and c.status = 'APPLIED'
          and c.original_lesson_id is not null
        order by c.pair_from, c.group_id""")

    day = q(f"""
        select id, pair_number::int pair, group_id, subgroup_number::int sg, discipline_id, teacher_id, room_id,
               room_exclusive, online, stream_id
        from lesson where date = '{DATE}' and origin = 'TEMPLATE'""")
    for lesson in day:
        lesson["pair"] = int(lesson["pair"])
        lesson["sg"] = int(lesson["sg"])
    by_id = {lesson["id"]: lesson for lesson in day}

    load = q(f"""
        select li.id, li.group_id, li.discipline_id, li.split_subgroups, li.planned_hours::int ph,
               li.hours_per_week::float hpw, li.required_room_type, lt.subgroup_number::int sg, lt.teacher_id
        from load_item li join load_item_teacher lt on lt.load_item_id = li.id
        where li.semester_id = '{semester}'""")
    weekly = defaultdict(float)
    for row in load:
        weekly[row["teacher_id"]] += float(row["hpw"])

    semester_lessons = q(f"""
        select group_id, discipline_id, subgroup_number::int sg, date, teacher_id, room_id
        from lesson where semester_id = '{semester}' and origin = 'TEMPLATE'""")
    facts = defaultdict(list)
    usual = defaultdict(Counter)
    for row in semester_lessons:
        facts[(row["group_id"], row["discipline_id"])].append((int(row["sg"]), row["date"]))
        if row["room_id"] and row["teacher_id"]:
            usual[(row["group_id"], row["teacher_id"], row["discipline_id"])][row["room_id"]] += 1

    absent_all_day = {d["orig_teacher_id"] for d in decisions if d["orig_teacher_id"]}
    for row in q(f"""select teacher_id, pairs from absence
                     where status = 'ACTIVE' and date_from <= '{DATE}' and date_to >= '{DATE}'"""):
        if not row["pairs"]:
            absent_all_day.add(row["teacher_id"])
    absent_by_pair = {str(pair): sorted(absent_all_day) for pair in range(1, 9)}

    teacher_pairs = defaultdict(set)
    for lesson in day:
        if lesson["teacher_id"]:
            teacher_pairs[lesson["teacher_id"]].add(lesson["pair"])

    def progress(group_id, discipline_id, cohort):
        items = {r["id"]: r for r in load if r["group_id"] == group_id and r["discipline_id"] == discipline_id}
        if not items:
            return None
        plan = sum(int(r["ph"]) for r in items.values())
        hpw = sum(float(r["hpw"]) for r in items.values()
                  if cohort == 0 or r["split_subgroups"] == "f" or int(r["sg"]) == cohort)
        lessons = facts.get((group_id, discipline_id), [])
        subgroups = {sg for sg, _ in lessons if sg > 0}
        cohort_lessons = lessons
        if subgroups:
            cohort_lessons = max(([x for x in lessons if x[0] in (0, s)] for s in sorted(subgroups)), key=len)
        forecast = HOURS_PER_PAIR * len(cohort_lessons)
        fact = HOURS_PER_PAIR * sum(1 for _, d in cohort_lessons if d < TODAY)
        if fact >= plan:
            status = "FINISHED"
        elif forecast > plan + 2:
            status = "WILL_OVERRUN"
        elif forecast < plan - 2:
            status = "WILL_UNDERRUN"
        else:
            status = "ON_TRACK"
        return {"plan": plan, "fact": fact, "forecast": forecast, "status": status, "hoursPerWeek": hpw}

    def room_free(room_id, target):
        return not any(other["pair"] == target["pair"] and other["room_id"] == room_id
                       and other["room_exclusive"] == "t" and other["online"] == "f" and other["id"] != target["id"]
                       for other in day)

    cases = []
    for decision in decisions:
        target = by_id[decision["original_lesson_id"]]
        group_id, cohort, pair = target["group_id"], target["sg"], target["pair"]
        room = target["room_id"]
        stream = none(target["stream_id"])
        group_day = [{
            "lessonId": other["id"], "pair": other["pair"], "subgroup": other["sg"],
            "disciplineId": other["discipline_id"], "disciplineShortName": disciplines[other["discipline_id"]],
            "teacherId": none(other["teacher_id"]),
            "teacherShortName": teachers.get(other["teacher_id"]) if other["teacher_id"] else None,
            "roomId": none(other["room_id"]), "decided": False}
            for other in sorted(day, key=lambda l: (l["pair"], l["sg"]))
            if other["group_id"] == group_id and other["id"] != target["id"]]
        pool = {}
        for row in load:
            if row["group_id"] != group_id:
                continue
            split = row["split_subgroups"] == "t"
            if (cohort == 0 and (split or int(row["sg"]) != 0)) or (cohort > 0 and (not split or int(row["sg"]) != cohort)):
                continue
            key = (row["discipline_id"], row["teacher_id"])
            if key in pool:
                continue
            counts = usual.get((group_id, row["teacher_id"], row["discipline_id"]))
            own = None
            if counts:
                best = sorted(counts.items(), key=lambda kv: (-kv[1], kv[0]))[0][0]
                own = best if room_free(best, target) else None
            pool[key] = {
                "disciplineId": row["discipline_id"], "shortName": disciplines[row["discipline_id"]],
                "teacherId": row["teacher_id"], "teacherShortName": teachers[row["teacher_id"]],
                "progress": progress(group_id, row["discipline_id"], cohort),
                "requiredRoomType": none(row["required_room_type"]), "ownRoomId": own}
        pool_teachers = {item["teacherId"] for item in pool.values()}
        # the ranking only looks at the pool's teachers and the teachers of the group's other lessons
        relevant = pool_teachers | {lesson["teacherId"] for lesson in group_day if lesson["teacherId"]}
        busy = defaultdict(set)
        for other in day:
            if other["teacher_id"] in relevant and (stream is None or other["stream_id"] != stream):
                busy[str(other["pair"])].add(other["teacher_id"])
        input_json = {
            "target": {
                "lessonId": target["id"], "date": DATE, "today": TODAY, "pair": pair, "subgroup": cohort,
                "disciplineId": target["discipline_id"], "disciplineShortName": disciplines[target["discipline_id"]],
                "teacherId": none(target["teacher_id"]), "roomType": rooms.get(room) if room else None,
                "streamId": stream, "online": target["online"] == "t", "hoursPerPair": HOURS_PER_PAIR,
                "groupHasBlock": False,
                "progress": progress(group_id, target["discipline_id"], cohort)},
            "groupDay": group_day,
            "groupDisciplines": list(pool.values()),
            "teacherPairsThatDay": {t: sorted(p) for t, p in sorted(teacher_pairs.items()) if t in relevant},
            "busyByPair": {p: sorted(t) for p, t in sorted(busy.items())},
            "absentByPair": {p: [t for t in teachers_absent if t in relevant] for p, teachers_absent
                             in absent_by_pair.items()},
            "slotPreferences": {},
            "weeklyHours": {t: weekly.get(t, 0.0) for t in sorted(pool_teachers)},
            "coverBurden": {}, "coverCount": {}, "lastCover": {},
            "declinedThisLesson": [], "declinedToday": [], "planPenalty": {}}
        cases.append({
            "group": groups[group_id], "pair": pair, "subgroup": cohort,
            "lesson": f"{disciplines[target['discipline_id']]} {teachers.get(target['teacher_id'], '')}".strip(),
            "real": {"kind": decision["kind"], "reasonCode": none(decision["reason_code"]),
                     "teacherId": none(decision["new_teacher_id"]), "disciplineId": none(decision["new_discipline_id"]),
                     "teacher": teachers.get(decision["new_teacher_id"]) if decision["new_teacher_id"] else None,
                     "discipline": disciplines.get(decision["new_discipline_id"]) if decision["new_discipline_id"] else None},
            "input": input_json})

    with open(out_path, "w", encoding="utf-8", newline="\n") as out:
        json.dump({"source": f"order No. 35 of {DATE}, state before the decisions of the day",
                   "exportedOn": date.today().isoformat(), "teachers": {t: teachers[t] for t in sorted(
                       {tid for case in cases for tid in case["input"]["weeklyHours"]} | {
                           c["real"]["teacherId"] for c in cases if c["real"]["teacherId"]})},
                   "cases": cases}, out, ensure_ascii=False, indent=1)
        out.write("\n")
    print(f"{len(cases)} cases -> {out_path}")
    for case in cases:
        real = case["real"]
        print(f"  {case['group']} p{case['pair']} sg{case['subgroup']} {case['lesson']}: {real['kind']} "
              f"{real['discipline'] or ''} {real['teacher'] or ''} pool={len(case['input']['groupDisciplines'])}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else DEFAULT_OUT)
