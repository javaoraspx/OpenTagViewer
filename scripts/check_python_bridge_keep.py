#!/usr/bin/env python3
"""Guard the R8 rules that keep Chaquopy's reflective calls alive.

Python calls Java methods **by name** through Chaquopy's ``JavaObject``. R8 cannot see those
calls: when it was first turned on, it renamed ``ensureReady``/``unavailableReason``/... and
deleted the uncalled ``serial``/``hardwareProfileJson``/``deviceIdsJson`` outright. The result
on a phone was ``AttributeError: 'v' object has no attribute 'serial'`` and a login that fell
back to a public Anisette server, which returned 500/502. The source compiled and every JVM
test passed, because Java never calls those methods itself.

So the set of methods Python calls and the set of methods proguard keeps have to stay in sync.
This script derives both from source:

* **called** - every ``<receiver>.<method>()`` in the Python bridge files whose receiver is the
  Java object handed over from Java. ``localAnisette`` and ``self._bridge`` are the two names
  used today; both are instances of ``AnisetteSource``.
* **kept** - the method signatures listed under the ``-keepclassmembers ... implements
  ...AnisetteSource`` rule in ``app/proguard-rules.pro``.

A method called from Python but not kept fails the check: that is the bug that breaks sign-in.
A method kept but not currently called from Python is only a warning - keeping an extra method
costs a few bytes and may still be reached indirectly (a default interface method such as
``router``, or a call from the Java side). Add the keep rule when adding a new Python call.

Pass ``--fix`` to print the signatures that need adding, rather than failing.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Java objects passed to Python as kwargs and then called by name. Both are AnisetteSource.
PYTHON_BRIDGE_FILES = [
    REPO_ROOT / "app" / "src" / "main" / "python" / "main.py",
    REPO_ROOT / "app" / "src" / "main" / "python" / "identity.py",
]
BRIDGE_RECEIVERS = ("localAnisette", "self._bridge")

PROGUARD_FILE = REPO_ROOT / "app" / "proguard-rules.pro"

CALL_PATTERN = re.compile(r"\b(?:localAnisette|self\._bridge)\.(\w+)\s*\(")

# The keep rule body lists Java signatures. Reduce them to method names for comparison.
SIGNATURE_PATTERN = re.compile(r"public\s+[\w.$<>\[\], ]+\s+(\w+)\s*\(")


def called_methods() -> set[str]:
    found: set[str] = set()
    for path in PYTHON_BRIDGE_FILES:
        for line in path.read_text(encoding="utf-8").splitlines():
            # Ignore comments; a method mentioned only there is not called.
            source = line.split("#", 1)[0]
            found.update(CALL_PATTERN.findall(source))
    return found


def kept_methods() -> set[str]:
    text = PROGUARD_FILE.read_text(encoding="utf-8")
    # Isolate just the -keepclassmembers block for AnisetteSource implementations, so a keep
    # rule written for some other interface cannot accidentally satisfy the comparison.
    match = re.search(
        r"-keepclassmembers\s+class\s+\*\s+implements\s+\S*AnisetteSource\s*\{(.*?)\}",
        text,
        re.DOTALL,
    )
    if not match:
        return set()
    return set(SIGNATURE_PATTERN.findall(match.group(1)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--fix", action="store_true", help="print the keep signatures that need adding"
    )
    args = parser.parse_args()

    called, kept = called_methods(), kept_methods()
    missing = called - kept
    stale = kept - called

    if args.fix:
        for method in sorted(missing):
            print(f"    # add to the AnisetteSource keep rule: {method}()")
        return 0

    if missing:
        print(
            "These methods are called from Python but NOT kept from R8; they will be renamed "
            "or removed, and sign-in will fail: " + ", ".join(sorted(missing)),
            file=sys.stderr,
        )
        return 1

    print(
        f"All {len(called)} Python-visible Anisette methods are kept from R8 "
        f"({', '.join(sorted(called))})."
    )
    if stale:
        print(
            "Note (not an error): kept but not directly called from Python - "
            + ", ".join(sorted(stale)),
            file=sys.stderr,
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
