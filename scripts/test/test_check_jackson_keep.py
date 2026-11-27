"""Tests for the guard that keeps Jackson's reflective constructors alive under R8.

The bug compiled cleanly, passed every JVM test, and only showed up on a phone as a crash loop
at activity start. These tests check the guard actually detects a missing keep rule rather
than merely agreeing with today's files.
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

import check_jackson_keep as guard  # noqa: E402


def test_detects_every_deserialised_model():
    models = guard.deserialised_models()
    flat = {t for types in models.values() for t in types}
    # UserAuthData is the one that crashed; the other two use the same ObjectMapper path.
    assert "UserAuthData" in flat
    assert "UserMapCameraPosition" in flat
    assert "AnisetteServerSuggestionsGithub" in flat


def test_current_rules_are_clean():
    models, kept, missing = guard.check()
    assert not missing, missing


def test_detects_a_package_missing_init(tmp_path: Path):
    """A package with fields but no <init>(...) must be flagged."""
    original = guard.PROGUARD_FILE
    fake = tmp_path / "proguard-rules.pro"
    fake.write_text(
        "-keepclassmembers class dev.wander.android.opentagviewer.db.repo.model.** {\n"
        "    <fields>;\n"
        "    *** get*();\n"
        "}\n",
        encoding="utf-8",
    )
    guard.PROGUARD_FILE = fake
    try:
        _, _, missing = guard.check()
        pkg = "dev.wander.android.opentagviewer.db.repo.model"
        assert pkg in missing
        assert "init" in missing[pkg][1]
    finally:
        guard.PROGUARD_FILE = original


def test_detects_a_package_missing_fields(tmp_path: Path):
    original = guard.PROGUARD_FILE
    fake = tmp_path / "proguard-rules.pro"
    fake.write_text(
        "-keepclassmembers class dev.wander.android.opentagviewer.db.repo.model.** {\n"
        "    <init>(...);\n"
        "}\n",
        encoding="utf-8",
    )
    guard.PROGUARD_FILE = fake
    try:
        _, _, missing = guard.check()
        pkg = "dev.wander.android.opentagviewer.db.repo.model"
        assert pkg in missing
        assert "fields" in missing[pkg][1]
    finally:
        guard.PROGUARD_FILE = original


def test_accepts_a_whole_class_rule(tmp_path: Path):
    """`-keep class <pkg>.** { *; }` keeps the class, its members and its annotations."""
    original = guard.PROGUARD_FILE
    fake = tmp_path / "proguard-rules.pro"
    fake.write_text(
        "-keep class dev.wander.android.opentagviewer.db.repo.model.** { *; }\n"
        "-keep class dev.wander.android.opentagviewer.service.web.sidestore.** { *; }\n"
        "-keepclassmembers class dev.wander.android.opentagviewer.data.model.** {\n"
        "    <fields>;\n"
        "    <init>(...);\n"
        "}\n",
        encoding="utf-8",
    )
    guard.PROGUARD_FILE = fake
    try:
        _, _, missing = guard.check()
        assert not missing, missing
    finally:
        guard.PROGUARD_FILE = original


def test_flags_members_only_keep_for_an_annotated_model(tmp_path: Path):
    """1.2.9: fields and constructors survived, the class-level annotation did not."""
    original = guard.PROGUARD_FILE
    fake = tmp_path / "proguard-rules.pro"
    fake.write_text(
        "-keepclassmembers class dev.wander.android.opentagviewer.db.repo.model.** {\n"
        "    <fields>;\n"
        "    <init>(...);\n"
        "}\n"
        "-keepclassmembers class dev.wander.android.opentagviewer.service.web.sidestore.** {\n"
        "    <fields>;\n"
        "    <init>(...);\n"
        "}\n"
        "-keepclassmembers class dev.wander.android.opentagviewer.data.model.** {\n"
        "    <fields>;\n"
        "    <init>(...);\n"
        "}\n",
        encoding="utf-8",
    )
    guard.PROGUARD_FILE = fake
    try:
        _, _, missing = guard.check()
        pkg = "dev.wander.android.opentagviewer.db.repo.model"
        assert pkg in missing, missing
        assert "class" in missing[pkg][1]
        # data.model carries no Jackson annotation, so member rules remain enough there.
        assert "dev.wander.android.opentagviewer.data.model" not in missing

        assert guard.main([]) == 1
    finally:
        guard.PROGUARD_FILE = original


def test_fix_mode_prints_the_rule(tmp_path: Path):
    original = guard.PROGUARD_FILE
    fake = tmp_path / "proguard-rules.pro"
    fake.write_text("", encoding="utf-8")
    guard.PROGUARD_FILE = fake
    try:
        assert guard.main(["--fix"]) == 0
    finally:
        guard.PROGUARD_FILE = original
