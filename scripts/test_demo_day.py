"""Offline checks of the demo scenario: python -m unittest discover -s scripts -p "test_*.py"."""
from __future__ import annotations

import unittest
from datetime import date, timedelta

import demo_day

try:
    import openpyxl
except ImportError:  # the check of the academic calendar needs openpyxl; the rest runs without it
    openpyxl = None

SETTINGS = {"approvalMode": "NOTIFY", "confirmTimeoutMinutes": 120, "signatoryPosition": "Заступник директора",
            "signatoryName": "Микола ПОЛІЩУК", "collegeName": "ВТФК", "hoursPerPair": 2,
            "showTeacherFullNames": False, "activeBellProfileId": "b1", "version": 11}


class FullNamesUpdateTest(unittest.TestCase):

    def test_turns_the_setting_on_and_keeps_the_rest_and_the_version(self):
        body = demo_day.full_names_update(SETTINGS)
        self.assertEqual(body, {**SETTINGS, "showTeacherFullNames": True})
        self.assertFalse(SETTINGS["showTeacherFullNames"], "the GET result is not changed in place")

    def test_nothing_to_do_when_already_on(self):
        self.assertIsNone(demo_day.full_names_update({**SETTINGS, "showTeacherFullNames": True}))



def calendar_codes(path) -> dict[tuple[str, date], str]:
    """(group, Monday) -> code of the academic calendar grid: the «ГРУПА» column, week 1 = Monday 31.08.2026."""
    sheet = openpyxl.load_workbook(path, read_only=True, data_only=True).worksheets[0]
    rows = [list(row) for row in sheet.iter_rows(values_only=True)]
    header = next(i for i, row in enumerate(rows) if "ГРУПА" in row)
    group_column = rows[header].index("ГРУПА")
    week_row = next(row for row in rows if "№ тижня" in row)
    first = week_row.index(1)
    codes = {}
    for row in rows[header + 1:]:
        group = str(row[group_column] or "").replace("-", "").strip()
        if not group[:1].isdigit():
            continue
        for column in range(first, len(row)):
            code = str(row[column] or "").strip()
            if code:
                codes[(group, date(2026, 8, 31) + timedelta(weeks=column - first))] = code
    return codes


class ScenarioTest(unittest.TestCase):

    def test_every_decision_belongs_to_an_absence_of_its_date(self):
        absences = {(a.date, a.teacher) for a in demo_day.ABSENCES}
        for decision in demo_day.DECISIONS:
            if decision.absent:
                self.assertIn((decision.date, decision.absent), absences, decision)

    def test_friday_keeps_three_requests_to_krentsin_and_thursday_one(self):
        friday = [d.answer for d in demo_day.DECISIONS if d.teacher == demo_day.SUBSTITUTE and d.date == demo_day.FRIDAY]
        thursday = [d.answer for d in demo_day.DECISIONS
                    if d.teacher == demo_day.SUBSTITUTE and d.date == demo_day.THURSDAY]
        self.assertEqual([None, "decline", None, "pending"], friday)
        self.assertEqual(["ok"], thursday)

    def test_friday_requests_are_on_pairs_that_have_not_started_at_ten(self):
        # the stand clock is 10:00 (pair 2 in progress); a request for a started pair is refused (CHANGE_LESSON_OVER)
        for decision in demo_day.DECISIONS:
            if decision.date == demo_day.FRIDAY and decision.answer in ("pending", "decline"):
                self.assertGreaterEqual(decision.pair, 3, decision)

    @unittest.skipIf(openpyxl is None, "openpyxl is not installed")
    def test_no_decision_falls_on_a_group_the_academic_calendar_takes_off_pairs(self):
        # 4КІ1 is on national resistance 16.11–27.11: its lessons of 19–20.11 do not exist on the stand.
        # Only «Р» (reserve, INFO) leaves the pairs as they are; every other code suspends them or is a day off.
        codes = calendar_codes(demo_day.seed.ROOT / "seed" / "academic-calendar-2026-27.xlsx")
        self.assertEqual("НС", codes[("4КІ1", date(2026, 11, 16))], "the grid was read in the wrong place")
        for decision in demo_day.DECISIONS:
            day = date.fromisoformat(decision.date)
            code = codes.get((decision.group, day - timedelta(days=day.weekday())))
            self.assertIn(code, (None, "Р"), f"{decision.group} {decision.date}: {code}")


if __name__ == "__main__":
    unittest.main()
