"""Tests for the guard that keeps Chaquopy's reflective calls alive under R8.

The bug this guards against compiled cleanly and passed every JVM test: it only showed up on
a phone as ``AttributeError: ... has no attribute 'serial'`` and a failed login. So the
tests here check the guard can actually see a missing keep rule, rather than only that today's
files agree.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_python_bridge_keep as guard  # noqa: E402


# --- called_methods ----------------------------------------------------------------------

def test_finds_calls_from_both_python_receivers():
    called = guard.called_methods()
    # identity.py uses localAnisette; main.py uses self._bridge for otp/machine.
    assert "serial" in called
    assert "hardwareProfileJson" in called
    assert "deviceIdsJson" in called
    assert "otp" in called
    assert "machine" in called


def test_ignores_a_method_mentioned_only_in_a_comment(tmp_path: Path):
    original = guard.PYTHON_BRIDGE_FILES
    probe = tmp_path / "probe.py"
    probe.write_text(
        "# localAnisette.notARealCall()\nlocalAnisette.serial()\n", encoding="utf-8"
    )
    guard.PYTHON_BRIDGE_FILES = [probe]
    try:
        assert guard.called_methods() == {"serial"}
    finally:
        guard.PYTHON_BRIDGE_FILES = original


# --- kept_methods ------------------------------------------------------------------------

def test_finds_the_signatures_in_the_real_keep_rule():
    kept = guard.kept_methods()
    for name in ("ensureReady", "serial", "hardwareProfileJson", "deviceIdsJson", "otp"):
        assert name in kept, f"{name} must be kept from R8"


def test_missing_keep_rule_is_visible(tmp_path: Path):
    original = guard.PROGUARD_FILE
    empty = tmp_path / "empty.pro"
    empty.write_text("-keep class foo.** { *; }\n", encoding="utf-8")
    guard.PROGUARD_FILE = empty
    try:
        assert guard.kept_methods() == set()
    finally:
        guard.PROGUARD_FILE = original


# --- the shipped files are in sync -------------------------------------------------------

def test_every_python_call_is_kept_from_r8():
    """The real check: a new Python call with no keep rule fails the build."""
    missing = guard.called_methods() - guard.kept_methods()
    assert not missing, f"called from Python but not kept from R8: {sorted(missing)}"
