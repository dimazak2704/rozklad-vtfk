"""Offline checks of the seed data: python -m unittest discover -s scripts -p "test_*.py"."""
from __future__ import annotations

import tempfile
import unittest
from unittest import mock
from pathlib import Path

import seed_demo

HEADER = "short_name,last_name,first_name,middle_name,match,source,note\n"


def write_csv(body: str) -> Path:
    file = tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".csv", delete=False)
    with file:
        file.write(HEADER + body)
    return Path(file.name)


class InitialsMatchTest(unittest.TestCase):

    def test_two_initials_must_fit_first_name_and_patronymic(self):
        self.assertTrue(seed_demo.initials_match("Крисак Л.В.", "Крисак", "Лариса", "Василівна"))
        self.assertFalse(seed_demo.initials_match("Крисак Л.В.", "Крисак", "Андрій", "Олексійович"))
        self.assertFalse(seed_demo.initials_match("Крисак Л.В.", "Крисак", "Лариса", "Олексіївна"))

    def test_two_letter_initial_tells_namesakes_apart(self):
        self.assertTrue(seed_demo.initials_match("Овчар Ін.М.", "Овчар", "Інна", "Миколаївна"))
        self.assertFalse(seed_demo.initials_match("Овчар Ін.М.", "Овчар", "Іван", "Миколайович"))

    def test_one_initial_checks_the_first_name_only(self):
        self.assertTrue(seed_demo.initials_match("Поліщук М.", "Поліщук", "Микола", "Іванович"))
        self.assertFalse(seed_demo.initials_match("Поліщук М.", "Поліщук", "Олександр", "Васильович"))

    def test_an_unknown_patronymic_is_not_checked_but_the_first_name_is(self):
        self.assertTrue(seed_demo.initials_match("Заколесник О.В.", "Заколесник", "Олена", ""))
        self.assertFalse(seed_demo.initials_match("Заколесник О.В.", "Заколесник", "Марія", ""))
        self.assertFalse(seed_demo.initials_match("Заколесник О.В.", "Заколесник", "", ""))

    def test_surname_only_and_other_surname(self):
        self.assertTrue(seed_demo.initials_match("Кренцін", "Кренцін", "Михайло", "Дмитрович"))
        self.assertFalse(seed_demo.initials_match("Кренцін", "Непийвода", "Максим", "Васильович"))


class TeacherNamesFileTest(unittest.TestCase):

    def test_seed_file_is_consistent(self):
        rows = seed_demo.teacher_names()
        self.assertGreaterEqual(len(rows), 80)
        for row in rows:
            self.assertIn(row["match"], ("initials", "surname+discipline"), row["short_name"])
            self.assertNotIn("’", row["first_name"] + row["middle_name"], row["short_name"])

    def test_rejects_a_name_that_does_not_fit_the_initials(self):
        path = write_csv("Крисак Л.В.,Крисак,Андрій,Олексійович,initials,https://vtc.vn.ua/x,\n")
        self.addCleanup(path.unlink)
        with self.assertRaises(SystemExit):
            seed_demo.teacher_names(path)

    def test_rejects_a_row_without_a_source(self):
        path = write_csv("Кренцін,Кренцін,Михайло,Дмитрович,surname+discipline,,\n")
        self.addCleanup(path.unlink)
        with self.assertRaises(SystemExit):
            seed_demo.teacher_names(path)

    def test_accepts_a_first_name_without_a_patronymic(self):
        path = write_csv("Прокопчук,Прокопчук,Марія,,surname+discipline,https://vtc.vn.ua/x,\n")
        self.addCleanup(path.unlink)
        self.assertEqual("Марія", seed_demo.teacher_names(path)[0]["first_name"])

    def test_rejects_a_row_without_a_first_name(self):
        path = write_csv("Прокопчук,Прокопчук,,,surname+discipline,https://vtc.vn.ua/x,\n")
        self.addCleanup(path.unlink)
        with self.assertRaises(SystemExit):
            seed_demo.teacher_names(path)

    def test_rejects_a_duplicate_row(self):
        row = "Кренцін,Кренцін,Михайло,Дмитрович,surname+discipline,https://vtc.vn.ua/x,\n"
        path = write_csv(row + row)
        self.addCleanup(path.unlink)
        with self.assertRaises(SystemExit):
            seed_demo.teacher_names(path)


class TeacherRequestTest(unittest.TestCase):

    def test_changes_only_the_name_parts(self):
        teacher = {"id": "t1", "lastName": "Кренцін", "firstName": None, "middleName": None,
                   "shortName": "Кренцін", "displayName": "Кренцін", "cycleCommission": {"id": "c1", "name": "КІ"},
                   "employment": "STAFF", "email": None, "active": True, "needsReview": False, "version": 7}
        body = seed_demo.teacher_request(teacher, "Михайло", "Дмитрович")
        self.assertEqual(body, {"lastName": "Кренцін", "firstName": "Михайло", "middleName": "Дмитрович",
                                "shortName": "Кренцін", "displayName": "Кренцін", "cycleCommissionId": "c1",
                                "employment": "STAFF", "email": None, "active": True, "needsReview": False,
                                "version": 7})


LESSON = {"id": "l1", "pair": 3, "subgroup": 0, "group": {"id": "g1"}}
ABSENCE = {"id": "a1", "reasonCategory": "SICK_LEAVE"}


class RecentChangesTest(unittest.TestCase):
    """`seed recent` turns the system's proposals into changes the way the dispatcher's screen does."""

    def test_another_discipline_of_the_group_is_a_mutual_replacement_in_the_suggested_room(self):
        option = {"kind": "REPLACE_DISCIPLINE", "teacher": {"id": "t2"}, "discipline": {"id": "d2"},
                  "suggestedRoom": {"id": "r9"}}
        request = seed_demo.option_request(option, LESSON, seed_demo.date(2026, 11, 3), ABSENCE)
        self.assertEqual({"kind": "REPLACE_DISCIPLINE", "date": "2026-11-03", "pairFrom": 3, "groupId": "g1",
                          "subgroup": 0, "originalLessonId": "l1", "reasonCode": "MUTUAL", "absenceId": "a1",
                          "newTeacherId": "t2", "newDisciplineId": "d2", "newRoomId": "r9"}, request)

    def test_a_cancelled_pair_is_an_absence_and_a_business_trip_keeps_its_own_reason(self):
        day = seed_demo.date(2026, 11, 3)
        self.assertEqual("ABSENT", seed_demo.option_request({"kind": "CANCEL"}, LESSON, day, ABSENCE)["reasonCode"])
        trip = {**ABSENCE, "reasonCategory": "BUSINESS_TRIP"}
        swap = seed_demo.option_request({"kind": "SWAP", "swapLesson": {"id": "l7"}}, LESSON, day, trip)
        self.assertEqual(("BUSINESS_TRIP", "l7"), (swap["reasonCode"], swap["swapLessonId"]))

    def test_incomplete_or_manual_options_are_skipped(self):
        day = seed_demo.date(2026, 11, 3)
        self.assertIsNone(seed_demo.option_request({"kind": "MERGE_SUBGROUPS"}, LESSON, day, ABSENCE))
        self.assertIsNone(seed_demo.option_request({"kind": "ADDITIONAL"}, LESSON, day, ABSENCE))

    def test_study_days_are_the_weekdays(self):
        days = seed_demo.study_days(seed_demo.date(2026, 11, 13), seed_demo.date(2026, 11, 17))
        self.assertEqual(["2026-11-13", "2026-11-16", "2026-11-17"], [d.isoformat() for d in days])


class FakeAdmin:
    """Answers the calls of `step_academic` from canned data and records what was sent."""

    def __init__(self, periods: list[dict], parsed: dict, applied: dict | None = None):
        self.periods = periods
        self.jobs = [parsed, applied or parsed]
        self.uploads: list[tuple] = []
        self.posts: list[str] = []

    def get(self, path: str):
        if path == "/api/admin/semesters":
            return [{"id": "s1", "name": seed_demo.SEMESTER_I}]
        if path.startswith("/api/admin/calendar-periods?semesterId=s1"):
            return self.periods
        if path == "/api/import/jobs/j1":
            return self.jobs[0] if not self.posts else self.jobs[1]
        raise AssertionError(f"unexpected GET {path}")

    def upload(self, path, fields, file_field, file_path, mime):
        self.uploads.append((path, fields, file_field, file_path, mime))
        return {"id": "j1", "status": "UPLOADED"}

    def post(self, path, body=None):
        self.posts.append(path)
        return {"id": "j1", "status": "APPLYING"}


DRAFT = {"groups": [{"code": "4КІ1"}], "codes": [{"code": "НС"}, {"code": "К"}], "periods": [{}, {}],
         "check": {"created": 2, "updated": 0, "deleted": 0, "unchanged": 0, "skippedManual": 0,
                   "outOfSemester": [{}]}}
READY = {"id": "j1", "status": "READY", "draft": DRAFT,
         "report": [{"severity": "WARN", "type": "PERIOD", "raw": "НС", "message": "поза семестрами"}]}
APPLIED = {**READY, "status": "APPLIED", "result": {"calendar": {"created": 2, "updated": 0, "deleted": 0}}}


class AcademicCalendarStepTest(unittest.TestCase):
    """`seed academic`: the academic calendar through the importer, before the timetable is published."""

    def setUp(self):
        patcher = mock.patch.object(seed_demo, "log")
        patcher.start()
        self.addCleanup(patcher.stop)

    def test_runs_after_the_periods_and_before_publication_in_the_default_run(self):
        steps = list(seed_demo.STEPS)
        self.assertLess(steps.index("import"), steps.index("academic"), "groups and rooms come from the import")
        self.assertLess(steps.index("academic"), steps.index("publish"), "publication materializes around periods")
        self.assertLess(steps.index("academic"), steps.index("order35"))
        self.assertNotIn("recent", steps, "`recent` stays by name and runs after the default steps")
        self.assertTrue(seed_demo.ACADEMIC_CALENDAR.is_file(), seed_demo.ACADEMIC_CALENDAR)

    def test_uploads_checks_and_applies_the_file(self):
        admin = FakeAdmin([{"source": "MANUAL"}], READY, APPLIED)
        seed_demo.step_academic(admin)
        path, fields, file_field, file_path, mime = admin.uploads[0]
        self.assertEqual(("/api/import/jobs", "ACADEMIC_CALENDAR_XLSX", "file", seed_demo.ACADEMIC_CALENDAR),
                         (path, fields["kind"], file_field, file_path))
        self.assertNotIn("semesterId", fields, "the academic calendar spans both semesters")
        self.assertEqual('{"academicYear": "2026/2027"}', fields["options"])
        self.assertEqual(["/api/import/jobs/j1/apply"], admin.posts)

    def test_skips_when_core_already_has_imported_periods(self):
        admin = FakeAdmin([{"source": "MANUAL"}, {"source": "IMPORT"}], READY)
        seed_demo.step_academic(admin)
        self.assertEqual([], admin.uploads)
        self.assertEqual([], admin.posts)

    def test_a_blocking_report_stops_the_seed_with_its_rows_and_applies_nothing(self):
        review = {"id": "j1", "status": "NEEDS_REVIEW", "draft": {**DRAFT, "check": None}, "report": [
            {"severity": "ERROR", "type": "PERIOD_KIND", "raw": "Х",
             "message": "Невідомий код «Х» у груп 4КІ1: додайте вид у «Видах періодів» або пропустіть код."},
            {"severity": "INFO", "type": "PERIOD", "raw": "", "message": "Буде створено 1"}]}
        admin = FakeAdmin([], review)
        with self.assertRaises(SystemExit) as stopped:
            seed_demo.step_academic(admin)
        self.assertIn("Невідомий код «Х»", str(stopped.exception))
        self.assertNotIn("Буде створено", str(stopped.exception))
        self.assertEqual([], admin.posts)

    def test_a_conflict_on_apply_stops_the_seed(self):
        back = {**READY, "status": "NEEDS_REVIEW", "report": [
            {"severity": "ERROR", "type": "PERIOD", "raw": "4КІ1", "message": "Заміна в період (17.11.2026, пара 2)"}]}
        admin = FakeAdmin([], READY, back)
        with self.assertRaises(SystemExit) as stopped:
            seed_demo.step_academic(admin)
        self.assertIn("Заміна в період", str(stopped.exception))

    def test_check_line_without_a_core_answer(self):
        self.assertIn("not asked", seed_demo.calendar_check_line(None))
        self.assertIn("outside the semesters 1", seed_demo.calendar_check_line(DRAFT["check"]))


if __name__ == "__main__":
    unittest.main()
