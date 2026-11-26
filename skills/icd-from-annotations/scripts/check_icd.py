#!/usr/bin/env python3
"""Checks an annotation-style ICD JSON (the output of this skill). Standard library only.

Usage:
  python3 check_icd.py OUTPUT.icd.json                       check the rules
  python3 check_icd.py OUTPUT.icd.json --expected EXPECTED   also compare with a known-good file

Prints "OK" or one line per problem ("ERROR <where>: <what>"), and exits with 1 if there is an error.
Notes ("NOTE ...") are things to tell the user, not mistakes.
"""
import json
import sys

ROOT_KEYS = {"$schema", "format", "id", "name", "description", "byteOrder", "bitOrder", "header", "messages",
             "classes", "enums"}
HEADER_KEYS = {"class", "opcodeField", "lengthField", "lengthIncludesHeader", "lengthUnit", "maxMessageBytes"}
MESSAGE_KEYS = {"opcode", "class", "name", "description"}
CLASS_KEYS = {"description", "binaryData", "validated", "charset", "fields"}
FIELD_KEYS = {"name", "type", "ordinal", "description", "unsigned", "bits", "bit", "size", "enumerable", "fixedPoint",
              "validRange", "invalidityValue", "lengthEncoded", "lengthEncoder", "binaryInternal", "transient",
              "existenceEncoded", "custom", "encoded"}
ENUM_KEYS = {"description", "constants", "intValued"}
OBJECT_KEYS = {
    "bits": {"value", "bitfield"},
    "fixedPoint": {"lsb", "type"},
    "validRange": {"minValue", "maxValue"},
    "lengthEncoded": {"encodingField", "value", "unsigned"},
    "lengthEncoder": {"isLengthInBytes"},
    "existenceEncoded": {"method", "params", "isAbsolutePath"},
    "encoded": {"codec", "underlyingType"},
    "charset": {"value", "isReversed"},
}
BITFIELDS = {"BITS8": 8, "BITS16": 16, "BITS24": 24, "BITS32": 32}
SIZE_CLASSES = {"byte", "short", "int", "long"}
BUILT_IN = {"byte", "short", "int", "long", "float", "double", "boolean", "char", "String",
            "Byte", "Short", "Integer", "Long", "Float", "Double", "Boolean", "Character"}
READ_BY_CODE = ("existenceEncoded", "custom", "encoded")


class Checker:
    def __init__(self, icd):
        self.icd = icd
        self.errors = []
        self.notes = []

    def error(self, where, what):
        self.errors.append(f"ERROR {where}: {what}")

    def note(self, where, what):
        self.notes.append(f"NOTE {where}: {what}")

    def only_keys(self, node, where, allowed):
        for key in node:
            if key not in allowed:
                self.error(f"{where}.{key}", f"unknown property (allowed: {', '.join(sorted(allowed))})")

    def run(self):
        icd = self.icd
        if not isinstance(icd, dict):
            self.error("$", "must be a JSON object")
            return
        self.only_keys(icd, "$", ROOT_KEYS)
        if icd.get("format") != "annotations":
            self.error("$.format", 'must be "annotations"')
        for key in ("id", "name"):
            if not isinstance(icd.get(key), str) or not icd.get(key):
                self.error(f"$.{key}", "is required (copy it from the config)")
        classes = icd.get("classes")
        if not isinstance(classes, dict) or not classes:
            self.error("$.classes", "is required: an object of class name -> class")
            classes = {}
        enums = icd.get("enums", {})
        if not isinstance(enums, dict):
            self.error("$.enums", "must be an object of enum name -> enum")
            enums = {}
        self.check_header(icd.get("header"), classes)
        self.check_messages(icd.get("messages"), classes)
        for name, cls in classes.items():
            self.check_class(name, cls, classes, enums)
        for name, enum in enums.items():
            self.check_enum(name, enum)

    def check_header(self, header, classes):
        if not isinstance(header, dict):
            self.error("$.header", "is required (copy it from the config)")
            return
        self.only_keys(header, "$.header", HEADER_KEYS)
        cls = header.get("class")
        if cls not in classes:
            self.error("$.header.class", f"class '{cls}' is not under $.classes")
            return
        names = {f.get("name") for f in classes[cls].get("fields", []) if isinstance(f, dict)}
        for key in ("opcodeField", "lengthField"):
            if header.get(key) not in names:
                self.error(f"$.header.{key}", f"'{header.get(key)}' is not a field of {cls}")

    def check_messages(self, messages, classes):
        if not isinstance(messages, list) or not messages:
            self.error("$.messages", "is required: [{\"opcode\": ..., \"class\": ...}] (copy it from the config)")
            return
        opcodes = set()
        for i, message in enumerate(messages):
            where = f"$.messages[{i}]"
            if not isinstance(message, dict):
                self.error(where, "must be an object")
                continue
            self.only_keys(message, where, MESSAGE_KEYS)
            opcode = str(message.get("opcode"))
            if opcode in opcodes:
                self.error(f"{where}.opcode", f"duplicate opcode {opcode}")
            opcodes.add(opcode)
            if message.get("class") not in classes:
                self.error(f"{where}.class", f"class '{message.get('class')}' is not under $.classes")

    def check_class(self, name, cls, classes, enums):
        where = f"$.classes.{name}"
        if not isinstance(cls, dict):
            self.error(where, "must be an object")
            return
        self.only_keys(cls, where, CLASS_KEYS)
        if "charset" in cls:
            self.check_object(cls, "charset", where)
        fields = cls.get("fields")
        if not isinstance(fields, list):
            self.error(f"{where}.fields", "is required: the fields in source order")
            return
        ordinals = {}
        for i, field in enumerate(fields):
            fw = f"{where}.fields[{i}]"
            if not isinstance(field, dict):
                self.error(fw, "must be an object")
                continue
            self.check_field(field, fw, classes, enums)
            ordinal = field.get("ordinal")
            if isinstance(ordinal, int):
                if ordinal in ordinals:
                    self.error(f"{fw}.ordinal", f"duplicate ordinal {ordinal} (also {ordinals[ordinal]})")
                ordinals[ordinal] = fw
        self.check_bit_groups(fields, where)
        self.check_length_fields(fields, where)

    def check_field(self, field, where, classes, enums):
        self.only_keys(field, where, FIELD_KEYS)
        if field.get("transient"):
            self.error(f"{where}.transient", "leave transient fields out of the JSON")
        for key in ("name", "type"):
            if not isinstance(field.get(key), str) or not field.get(key):
                self.error(f"{where}.{key}", "is required")
        if not isinstance(field.get("ordinal"), int):
            self.error(f"{where}.ordinal", "is required: the number in @Ordinal(...)")
        for key in OBJECT_KEYS:
            if key in field:
                self.check_object(field, key, where)
        for key in READ_BY_CODE:
            if key in field:
                self.note(where, f"'{field.get('name')}' is read by code ({key}): the message is decoded only up to it")
                return  # PCAP Player does not decode it, so its other properties need no checks
        java_type = str(field.get("type", ""))
        element = java_type[:-2] if java_type.endswith("[]") else java_type
        in_bits = "bits" in field or "bit" in field
        if element not in BUILT_IN and element not in classes and element not in enums:
            self.error(f"{where}.type", f"'{element}' is not a Java type, nor a class under $.classes or an enum under $.enums")
        if element == "String" and not java_type.endswith("[]") and "size" not in field:
            self.error(f"{where}.size", "a String needs its @Size")
        if java_type.endswith("[]") and ("size" in field) == ("lengthEncoded" in field):
            self.error(where, "an array needs exactly one of size (@Size) or lengthEncoded (@LengthEncoded)")
        if element in enums and not in_bits and "enumerable" not in field:
            self.error(f"{where}.enumerable", "an enum field needs @Enumerable(<size>.class), e.g. \"byte\"")
        if "enumerable" in field and field["enumerable"] not in SIZE_CLASSES:
            self.error(f"{where}.enumerable", f"must be one of {sorted(SIZE_CLASSES)} (write byte, not byte.class)")
        fixed_point = field.get("fixedPoint")
        if isinstance(fixed_point, dict):
            if "lsb" not in fixed_point:
                self.error(f"{where}.fixedPoint.lsb", "is required")
            if fixed_point.get("type") not in SIZE_CLASSES:
                self.error(f"{where}.fixedPoint.type", f"must be one of {sorted(SIZE_CLASSES)} (write int, not int.class)")
        bits = field.get("bits")
        if isinstance(bits, dict):
            if bits.get("bitfield") not in BITFIELDS:
                self.error(f"{where}.bits.bitfield", f"must be one of {', '.join(BITFIELDS)} (write BITS8 even if the code says BIT8)")
            if not isinstance(bits.get("value"), int) or bits.get("value") < 1:
                self.error(f"{where}.bits.value", "must be the number of bits (1 or more)")

    def check_object(self, node, key, where):
        value = node[key]
        if not isinstance(value, dict):
            self.error(f"{where}.{key}", "must be an object")
            return
        self.only_keys(value, f"{where}.{key}", OBJECT_KEYS[key])

    def check_bit_groups(self, fields, where):
        """Consecutive bits fields (by ordinal) must fill their chunk exactly."""
        ordered = sorted((f for f in fields if isinstance(f, dict) and isinstance(f.get("ordinal"), int)),
                         key=lambda f: f["ordinal"])
        chunk, used, start = None, 0, None
        for field in ordered:
            if any(key in field for key in READ_BY_CODE):
                break
            bits = field.get("bits")
            if isinstance(bits, dict) and bits.get("bitfield") in BITFIELDS and isinstance(bits.get("value"), int):
                if chunk is None:
                    chunk, used, start = BITFIELDS[bits["bitfield"]], 0, field.get("name")
                used += bits["value"]
            elif field.get("bit") is True:
                if chunk is None:
                    self.error(where, f"'{field.get('name')}' has bit but no bits field before it opened a chunk")
                    continue
                used += 1
            else:
                if chunk is not None:
                    self.error(where, f"the bits from '{start}' add up to {used} of {chunk} bits")
                chunk = None
                continue
            if used == chunk:
                chunk = None
            elif used > chunk:
                self.error(where, f"the bits from '{start}' add up to {used}, more than their {chunk}-bit chunk")
                chunk = None
        if chunk is not None:
            self.error(where, f"the bits from '{start}' add up to {used} of {chunk} bits")

    def check_length_fields(self, fields, where):
        by_name = {f.get("name"): f for f in fields if isinstance(f, dict)}
        for field in fields:
            encoded = field.get("lengthEncoded") if isinstance(field, dict) else None
            if not isinstance(encoded, dict) or "encodingField" not in encoded:
                continue
            target = by_name.get(encoded["encodingField"])
            if target is None:
                self.error(where, f"'{field.get('name')}': encodingField '{encoded['encodingField']}' is not a field of this class")
            elif isinstance(target.get("ordinal"), int) and isinstance(field.get("ordinal"), int) \
                    and target["ordinal"] >= field["ordinal"]:
                self.error(where, f"'{field.get('name')}': its length field '{target['name']}' must have a smaller ordinal")

    def check_enum(self, name, enum):
        where = f"$.enums.{name}"
        if not isinstance(enum, dict):
            self.error(where, "must be an object")
            return
        self.only_keys(enum, where, ENUM_KEYS)
        if ("constants" in enum) == ("intValued" in enum):
            self.error(where, "needs exactly one of constants (names in order) or intValued (name -> number)")
        if "constants" in enum and not isinstance(enum["constants"], list):
            self.error(f"{where}.constants", "must be a list of names in declaration order")
        if "intValued" in enum and not isinstance(enum["intValued"], dict):
            self.error(f"{where}.intValued", "must be an object of name -> number")


def differences(actual, expected, where="$"):
    """Where two JSON values differ (object key order does not matter, list order does)."""
    if isinstance(actual, dict) and isinstance(expected, dict):
        found = []
        for key in sorted(set(actual) | set(expected)):
            if key not in actual:
                found.append(f"DIFF {where}.{key}: missing")
            elif key not in expected:
                found.append(f"DIFF {where}.{key}: not expected")
            else:
                found += differences(actual[key], expected[key], f"{where}.{key}")
        return found
    if isinstance(actual, list) and isinstance(expected, list):
        found = [] if len(actual) == len(expected) else [f"DIFF {where}: {len(actual)} items, expected {len(expected)}"]
        for i, (a, e) in enumerate(zip(actual, expected)):
            found += differences(a, e, f"{where}[{i}]")
        return found
    same = actual == expected and type(actual) is type(expected) or (
        isinstance(actual, (int, float)) and isinstance(expected, (int, float)) and not isinstance(actual, bool)
        and not isinstance(expected, bool) and actual == expected)
    return [] if same else [f"DIFF {where}: {json.dumps(actual, ensure_ascii=False)}, expected {json.dumps(expected, ensure_ascii=False)}"]


def check(icd, expected=None):
    """All problems, notes and differences, as (errors, notes)."""
    checker = Checker(icd)
    checker.run()
    errors = list(checker.errors)
    if expected is not None:
        errors += differences(icd, expected)
    return errors, checker.notes


def main(argv):
    if len(argv) not in (2, 4) or (len(argv) == 4 and argv[2] != "--expected"):
        print(__doc__)
        return 2
    try:
        with open(argv[1], encoding="utf-8") as file:
            icd = json.load(file)
    except (OSError, ValueError) as e:
        print(f"ERROR $: cannot read the JSON: {e}")
        return 1
    expected = None
    if len(argv) == 4:
        with open(argv[3], encoding="utf-8") as file:
            expected = json.load(file)
    errors, notes = check(icd, expected)
    for line in errors + notes:
        print(line)
    if not errors:
        print("OK")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
