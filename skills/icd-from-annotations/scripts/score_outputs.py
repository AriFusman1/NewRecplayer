#!/usr/bin/env python3
"""Scores a model's outputs against the examples: how many it got exactly right.

Usage: python3 score_outputs.py OUTPUT_DIR

Give the model each example's input folder (examples/<name>/input) with SKILL.md, save its answer as
OUTPUT_DIR/<name>.icd.json, then run this. For each example it prints PASS, or the checker's errors
and the differences from examples/<name>/expected.icd.json.
"""
import json
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from check_icd import check  # noqa: E402

EXAMPLES = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "examples")


def score(output_dir):
    names = sorted(os.listdir(EXAMPLES))
    passed = 0
    for name in names:
        output = os.path.join(output_dir, name + ".icd.json")
        if not os.path.exists(output):
            print(f"MISSING {name}: no {output}")
            continue
        with open(output, encoding="utf-8") as file, \
                open(os.path.join(EXAMPLES, name, "expected.icd.json"), encoding="utf-8") as expected:
            try:
                errors, _ = check(json.load(file), json.load(expected))
            except ValueError as e:
                errors = [f"ERROR $: not valid JSON: {e}"]
        if errors:
            print(f"FAIL {name}")
            for error in errors:
                print("  " + error)
        else:
            print(f"PASS {name}")
            passed += 1
    print(f"{passed}/{len(names)} examples exactly right")
    return 0 if passed == len(names) else 1


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(score(sys.argv[1]))
