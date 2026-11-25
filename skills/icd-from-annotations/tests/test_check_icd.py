"""Tests of the skill's checker: every example's expected output passes, and typical mistakes are caught.

Run: python3 -m unittest discover -s skills/icd-from-annotations/tests
"""
import copy
import json
import os
import sys
import unittest

SKILL = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(SKILL, "scripts"))
from check_icd import check  # noqa: E402

EXAMPLES = os.path.join(SKILL, "examples")


def example(name):
    with open(os.path.join(EXAMPLES, name, "expected.icd.json"), encoding="utf-8") as file:
        return json.load(file)


def errors_of(icd):
    return check(icd)[0]


class ExpectedOutputsPass(unittest.TestCase):
    def test_every_example_passes(self):
        for name in sorted(os.listdir(EXAMPLES)):
            with self.subTest(example=name):
                self.assertEqual([], errors_of(example(name)))

    def test_fields_read_by_code_are_notes_not_errors(self):
        errors, notes = check(example("04-read-by-code"))
        self.assertEqual([], errors)
        self.assertEqual(2, len(notes), notes)  # one per field, like the converter's warnings
        self.assertIn("'name' is read by code (existenceEncoded)", notes[0])
        self.assertIn("'tableName' is read by code (custom)", notes[1])


class MistakesAreCaught(unittest.TestCase):
    """Each test makes one mistake a model is likely to make, and expects the checker to say so."""

    def mistake(self, name, change):
        icd = copy.deepcopy(example(name))
        change(icd)
        return errors_of(icd)

    def assertCaught(self, errors, text):
        self.assertTrue(any(text in error for error in errors), f"'{text}' not in {errors}")

    def test_bit8_not_normalized(self):
        errors = self.mistake("03-enums-and-checks",
                              lambda icd: icd["classes"]["Status"]["fields"][5]["bits"].update(bitfield="BIT8"))
        self.assertCaught(errors, "write BITS8 even if the code says BIT8")

    def test_class_literal_kept(self):
        errors = self.mistake("03-enums-and-checks",
                              lambda icd: icd["classes"]["Status"]["fields"][0].update(enumerable="byte.class"))
        self.assertCaught(errors, "write byte, not byte.class")

    def test_transient_field_included(self):
        errors = self.mistake("03-enums-and-checks", lambda icd: icd["classes"]["Status"]["fields"].insert(
            3, {"name": "headValidity", "type": "boolean", "transient": True}))
        self.assertCaught(errors, "leave transient fields out")

    def test_missing_ordinal(self):
        errors = self.mistake("01-basic", lambda icd: icd["classes"]["Msg"]["fields"][2].pop("ordinal"))
        self.assertCaught(errors, "the number in @Ordinal")

    def test_duplicate_ordinal(self):
        errors = self.mistake("01-basic", lambda icd: icd["classes"]["Msg"]["fields"][2].update(ordinal=2))
        self.assertCaught(errors, "duplicate ordinal 2")

    def test_bits_do_not_fill_the_chunk(self):
        errors = self.mistake("01-basic", lambda icd: icd["classes"]["Msg"]["fields"][1]["bits"].update(value=2))
        self.assertCaught(errors, "add up to 7 of 8 bits")

    def test_annotation_name_used_as_key(self):
        errors = self.mistake("01-basic", lambda icd: icd["classes"]["Msg"]["fields"][2].update(Size=5))
        self.assertCaught(errors, "fields[2].Size: unknown property")

    def test_nested_class_not_converted(self):
        errors = self.mistake("02-arrays", lambda icd: icd["classes"].pop("Track"))
        self.assertCaught(errors, "'Track' is not a Java type")

    def test_enum_not_converted(self):
        errors = self.mistake("03-enums-and-checks", lambda icd: icd["enums"].pop("ELevel"))
        self.assertCaught(errors, "'ELevel' is not a Java type")

    def test_length_field_after_the_array(self):
        errors = self.mistake("02-arrays", lambda icd: icd["classes"]["TrackReport"]["fields"][1].update(ordinal=20))
        self.assertCaught(errors, "must have a smaller ordinal")

    def test_opcode_invented_twice(self):
        errors = self.mistake("01-basic", lambda icd: icd["messages"].append({"opcode": "0x01", "class": "Msg"}))
        self.assertCaught(errors, "duplicate opcode 0x01")


class ComparingWithExpected(unittest.TestCase):
    def test_same_output_has_no_differences(self):
        self.assertEqual([], check(example("02-arrays"), example("02-arrays"))[0])

    def test_key_order_does_not_matter_but_values_do(self):
        actual = example("02-arrays")
        actual["classes"]["Track"]["fields"][1]["fixedPoint"] = {"type": "int", "lsb": 0.1}
        errors = check(actual, example("02-arrays"))[0]
        self.assertEqual(["DIFF $.classes.Track.fields[1].fixedPoint.lsb: 0.1, expected 0.01"], errors)


class ScoringModelOutputs(unittest.TestCase):
    def test_the_expected_files_score_full_marks(self):
        import contextlib
        import io
        import shutil
        import tempfile
        from score_outputs import score

        with tempfile.TemporaryDirectory() as outputs:
            for name in os.listdir(EXAMPLES):
                shutil.copy(os.path.join(EXAMPLES, name, "expected.icd.json"), os.path.join(outputs, name + ".icd.json"))
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                result = score(outputs)
        self.assertEqual(0, result, printed.getvalue())
        self.assertIn("4/4 examples exactly right", printed.getvalue())


if __name__ == "__main__":
    unittest.main()
