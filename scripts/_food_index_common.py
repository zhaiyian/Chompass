"""Shared helpers for the offline food-index SQLite builders.

``build_usda_food_index.py``, ``build_swiss_food_index.py`` and
``build_cn_food_index.py`` each emit a compact read-only SQLite with a ``foods``
table (per-100g nutrient columns plus a ``tokens`` search column), a ``meta``
table, and an external-content FTS5 virtual table over ``foods``. This module
holds the parts the builders share: tokenization, the foods/meta/FTS5 schema
DDL, FTS rebuild, sha256, manifest writing, and meta rows.
"""

from __future__ import annotations

import hashlib
import json
import re
import sqlite3
from pathlib import Path

# CJK ideographs + compatibility ideographs. Each one is its own word for the
# indexers below: FTS5's unicode61 tokenizer would otherwise swallow a whole run.
_CJK = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff]")


def tokenize(text: str) -> str:
    return " ".join("".join(ch.lower() if ch.isalnum() else " " for ch in text).split())


def tokenize_cjk(text: str) -> str:
    """Tokenize for the bundled Chinese index: Latin words plus CJK n-grams.

    FTS5's unicode61 tokenizer treats a whole CJK run as one token, so a query
    for "鸡胸" would never match the indexed name "鸡胸肉（生）". Every glyph
    and every adjacent pair of a run is therefore indexed on its own:

      "鸡胸肉（生）" -> "鸡 胸 肉 生 鸡胸 胸肉 肉生"

    Single characters keep one-character queries reachable; bigrams keep
    partial words rankable. Latin words are indexed whole, as in [tokenize].
    """
    tokens: list[str] = []
    run: list[str] = []
    latin: list[str] = []

    def flush_run() -> None:
        if not run:
            return
        joined = "".join(run)
        tokens.extend(joined)  # unigrams
        tokens.extend(joined[i : i + 2] for i in range(len(joined) - 1))  # bigrams
        run.clear()

    def flush_latin() -> None:
        if latin:
            word = "".join(latin).lower()
            if len(word) >= 2:
                tokens.append(word)
            latin.clear()

    for ch in text:
        if _CJK.match(ch):
            flush_latin()
            run.append(ch)
        elif ch.isalnum():
            flush_run()
            latin.append(ch)
        else:
            flush_run()
            flush_latin()
    flush_run()
    flush_latin()
    return " ".join(dict.fromkeys(tokens))


def create_schema(
    conn: sqlite3.Connection,
    nutrient_columns: list[str],
    fixed_columns: list[str],
    fts_columns: list[str],
    fts_rowid: str,
    extra_statements: list[str] | None = None,
) -> None:
    """Create the shared ``meta`` + ``foods`` + external-content ``foods_fts`` schema.

    ``fixed_columns`` are the non-nutrient ``foods`` columns as ``"name TYPE"``
    strings (e.g. ``"fdc_id INTEGER PRIMARY KEY"``). ``fts_columns`` are the text
    columns indexed by FTS5 and ``fts_rowid`` the ``foods`` column used as the FTS
    rowid. ``extra_statements`` run after ``foods`` is created (secondary indexes,
    side tables); include any ``DROP`` statements for side tables there too.
    """
    nutrient_ddl = ",\n  ".join(f"{c} REAL" for c in nutrient_columns)
    fixed_ddl = ",\n  ".join(fixed_columns)
    fts_ddl = ",\n  ".join(fts_columns)
    extra = ";\n".join(extra_statements or ())
    conn.executescript(
        f"""
        DROP TABLE IF EXISTS foods_fts;
        DROP TABLE IF EXISTS foods;
        DROP TABLE IF EXISTS meta;
        CREATE TABLE meta (
          key TEXT PRIMARY KEY,
          value TEXT NOT NULL
        );
        CREATE TABLE foods (
          {fixed_ddl},
          {nutrient_ddl}
        );
        {extra}
        CREATE VIRTUAL TABLE foods_fts USING fts5(
          {fts_ddl},
          content='foods',
          content_rowid='{fts_rowid}'
        );
        """
    )


def rebuild_fts(conn: sqlite3.Connection) -> None:
    conn.execute("INSERT INTO foods_fts(foods_fts) VALUES('rebuild')")


def set_meta(conn: sqlite3.Connection, key: str, value: str) -> None:
    conn.execute("INSERT INTO meta(key,value) VALUES (?,?)", (key, value))


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as fh:
        for chunk in iter(lambda: fh.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def write_manifest(db_path: Path, manifest: dict) -> Path:
    """Write ``<db_stem>.manifest.json`` next to [db_path] with a fresh sha256."""
    full = {**manifest, "sha256": sha256(db_path)}
    path = db_path.with_name(f"{db_path.stem}.manifest.json")
    path.write_text(json.dumps(full, indent=2) + "\n", encoding="utf-8")
    return path
