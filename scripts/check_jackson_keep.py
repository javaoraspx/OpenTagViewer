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

A second thing goes missing with R8, and 1.2.9 shipped it: **class-level annotations**.
``@JsonIgnoreProperties(ignoreUnknown = true)`` on UserAuthData exists to say "this model
wants one key out of the JSON and nothing else". R8 keeps annotations only for classes it
keeps - ``-keepclassmembers`` keeps members of a class and explicitly does not keep the class -
so the annotation was gone at runtime and every launch died with ``Unrecognized field "type"``.
The same applied to ``@JsonProperty("account_name")``: snake_case JSON stopped mapping onto
camelCase fields and the account name silently read as null.

The guard derives both sides from source so they cannot drift:

* **deserialised** - every ``readValue(..., X.class)`` target in Java, mapped to its package.
* **kept** - packages with a keep rule for ``<pkg>.**``:
  ``-keepclassmembers ... { <fields>; <init>(...); }`` keeps the members,
  ``-keep ... { *; }`` keeps the class as well (and with it the annotations).

A deserialised model whose package lacks ``<fields>`` or ``<init>(...)`` fails the check, and
so does one that annotates its class while the package is only kept member-by-member.
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
# Two shapes, and they do not mean the same thing:
#   -keepclassmembers class <pkg>.** { ... }   members of classes that survive
#   -keep class <pkg>.** { *; }                the classes themselves, members and annotations
KEEP_RULE = re.compile(
    r"-keep(?P<kind>classmembers)?\s+class\s+(?P<pkg>[\w.]+)\.\*\*\s*\{(?P<body>[^}]*)\}",
    re.DOTALL,
)
# `*;` inside a keep body is "every field and every method", constructors included.
ALL_MEMBERS = re.compile(r"(?<![\w$.])\*;")
# An annotation written on the class rather than on one of its fields.
CLASS_ANNOTATION = re.compile(r"^\s*@Json\w+", re.MULTILINE)



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


def annotates_its_class(pkg, simple):
    """True when the model's own source annotates the class, not merely a field.

    ``@JsonIgnoreProperties`` and ``@JsonTypeInfo`` sit above the declaration; the
    ``@JsonProperty`` on a field comes after it, so only the lines above the declaration are
    searched. The whole line block is joined first: these annotations are often written over
    several lines.
    """
    source = JAVA_SRC / pkg.replace(".", "/") / (simple + ".java")
    try:
        lines = source.read_text(encoding="utf-8").splitlines()
    except OSError:
        return False

    for index, line in enumerate(lines):
        if re.search(r"\bclass\s+" + re.escape(simple) + r"\b", line):
            # Annotations, other modifiers and the odd javadoc line; 15 lines is enough for
            # every model here and short enough never to reach the previous declaration.
            return bool(CLASS_ANNOTATION.search("\n".join(lines[max(0, index - 15):index])))
    return False


def kept_packages():
    """Return {package: set(members)} for the keep rules in the proguard file."""
    text = PROGUARD_FILE.read_text(encoding="utf-8")
    kept = {}
    for rule in KEEP_RULE.finditer(text):
        # A package rule covers its own package; accumulate members if repeated.
        members = kept.setdefault(rule.group("pkg"), set())
        body = rule.group("body")
        if rule.group("kind") is None:
            # `-keep` keeps the class, and that is what preserves its class-level annotations.
            members.add("class")
        if ALL_MEMBERS.search(body) or "<fields>" in body:
            members.add("fields")
        if ALL_MEMBERS.search(body) or "<init>" in body:
            members.add("init")
    return kept


def check():
    models = deserialised_models()
    kept = kept_packages()
    missing = {}
    for pkg, types in sorted(models.items()):
        members = kept.get(pkg, set())
        lacks = [m for m in ("fields", "init") if m not in members]
        annotated = sorted(t for t in types if annotates_its_class(pkg, t))
        if annotated and "class" not in members:
            lacks.append("class")
        if lacks:
            missing[pkg] = (sorted(types), lacks, annotated)
    return models, kept, missing


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fix", action="store_true", help="print the rules to add")
    args = parser.parse_args(argv)

    models, kept, missing = check()
    if not missing:
        total = sum(len(t) for t in models.values())
        print(
            f"All {total} Jackson-deserialised models keep <fields>, <init>(...) and their "
            f"class annotations from R8 ({len(models)} packages)."
        )
        return 0

    if args.fix:
        for pkg, (types, lacks, annotated) in sorted(missing.items()):
            print(f"# {', '.join(sorted(types))}")
            if "class" in lacks:
                # Keeping the class is what keeps its annotations; `*;` covers the members.
                print(f"-keep class {pkg}.** {{ *; }}")
                continue
            print(f"-keepclassmembers class {pkg}.** {{")
            if "fields" in lacks:
                print("    <fields>;")
            if "init" in lacks:
                print("    <init>(...);")
            print("}")
        return 0

    for pkg, (types, lacks, annotated) in sorted(missing.items()):
        if "class" in lacks:
            print(
                f"Jackson deserialises {', '.join(annotated)} from package {pkg}, whose class "
                f"carries a Jackson annotation, but the R8 rule only keeps members "
                f"(-keepclassmembers). R8 keeps class annotations only for classes it keeps, "
                "so the annotation is gone at runtime: unknown JSON keys throw and renamed "
                "properties read as null. Keep the class itself: -keep class "
                f"{pkg}.** {{ *; }}",
                file=sys.stderr,
            )
            continue
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
