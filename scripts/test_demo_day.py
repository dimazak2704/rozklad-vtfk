"""Offline checks of the demo scenario: python -m unittest discover -s scripts -p "test_*.py"."""
from __future__ import annotations

import unittest

import demo_day

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


if __name__ == "__main__":
    unittest.main()
