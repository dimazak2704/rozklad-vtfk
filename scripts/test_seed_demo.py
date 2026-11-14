"""Offline checks of the seed data: python -m unittest discover -s scripts -p "test_*.py"."""
from __future__ import annotations

import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
