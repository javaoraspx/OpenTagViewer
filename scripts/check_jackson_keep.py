#!/usr/bin/env python3
"""Guard the R8 rules that keep Jackson's reflective deserialisation working.

``ObjectMapper.readValue(json, X.class)`` creates X through its no-arg constructor and then
sets fields by name. Neither is an explicit call in Java source, so R8 may delete/rename them.
When that happens the phone shows
``Cannot construct instance of X (no Creators, like default constructor, exist)`` and the
activity that reads the stored JSON crashes on start - a crash loop the JVM tests never see.

This happened to UserAuthData in 1.2.3..1.2.8: its package had a keep rule for fields and
getters/setters but no ``<init>(...)``, so Lombok's @NoArgsConstructor was removed. Sign-in
was broken for a different (Anisette) reason until 1.2.8, which is why this only surfaced once
login started working.

The guard derives both sides from source so they cannot drift:

* **deserialised** - every ``readValue(..., X.class)`` target in Java, mapped to its package.
* **kept** - packages with a ``-keepclassmembers class <pkg>.**`` rule that includes
  ``<init>(...)`` and ``<fields>``.

A deserialised model whose package lacks either fails the check.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
JAVA_SRC = REPO_ROOT / "app" / "src" / "main" / "java"
PROGUARD_FILE = REPO_ROOT / "app" / "proguard-rules.pro"

# readValue(json, Type.class) - capture the fully-qualified or imported type name.
READ_VALUE = re.compile(r"readValue\([^,]+,\s*([\w.]+)\.class\s*\)")
IMPORT = re.compile(r"^\s*import\s+([\w.]+);", re.MULTILINE)
KEEP_RULE = re.compile(
    r"-keepclassmembers\s+class\s+([\w.]+)\.\*\*\s*\{([^}]*)\}", re.DOTALL
)


def deserialised_models():
    """Return {package: set(types)} for every readValue target found in Java source."""
    found = {}
    for java in JAVA_SRC.rglob("*.java"):
        text = java.read_text(encoding="utf-8")
        imports = dict()
        for fq in IMPORT.findall(text):
            imports[fq.split(".")[-1]] = fq
        for name in READ_VALUE.findall(text):
            fq = name if "." in name else imports.get(name)
            if not fq:
                continue
            pkg, _, simple = fq.rpartition(".")
            found.setdefault(pkg, set()).add(simple)
    return found


def kept_packages():
    """Return {package: set(members)} for keepclassmembers rules in the proguard file."""
    text = PROGUARD_FILE.read_text(encoding="utf-8")
    kept = {}
    for pkg, body in KEEP_RULE.findall(text):
        # A package rule covers its own package; accumulate members if repeated.
        members = kept.setdefault(pkg, set())
        if "<fields>" in body:
            members.add("fields")
        if "<init>" in body:
            members.add("init")
    return kept


def check():
    models = deserialised_models()
    kept = kept_packages()
    missing = {}
    for pkg, types in sorted(models.items()):
        members = kept.get(pkg, set())
        lacks = [m for m in ("fields", "init") if m not in members]
        if lacks:
            missing[pkg] = (sorted(types), lacks)
    return models, kept, missing


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fix", action="store_true", help="print the rules to add")
    args = parser.parse_args(argv)

    models, kept, missing = check()
    if not missing:
        total = sum(len(t) for t in models.values())
        print(
            f"All {total} Jackson-deserialised models have <fields> and <init>(...) kept "
            f"from R8 ({len(models)} packages)."
        )
        return 0

    if args.fix:
        for pkg, (types, lacks) in sorted(missing.items()):
            print(f"# {', '.join(sorted(types))}")
            print(f"-keepclassmembers class {pkg}.** {{")
            if "fields" in lacks:
                print("    <fields>;")
            if "init" in lacks:
                print("    <init>(...);")
            print("}")
        return 0

    for pkg, (types, lacks) in sorted(missing.items()):
        print(
            f"Jackson deserialises {', '.join(sorted(types))} from package {pkg}, but the "
            f"R8 keep rule lacks {', '.join('<'+m+'>' if m=='fields' else '<init>(...)' for m in lacks)}. "
            "These classes will fail to construct at runtime with "
            "'no Creators, like default constructor, exist'.",
            file=sys.stderr,
        )
    return 1


if __name__ == "__main__":
    sys.exit(main())
