#!/usr/bin/env python3
"""Build the purine lookup index for Chompass.

The purine table is the maintainer's own food list (嘌呤表.xlsx): Chinese food
names with total purine in mg per 100 g, grouped by category. The extracted,
diffable copy of that sheet lives in ``scripts/data/purine-table.csv``; this
script turns it into a tiny SQLite the app ships and queries by name:

  android/app/src/main/assets/purine/purine_foods.sqlite
  android/app/src/main/assets/purine/purine_foods.manifest.json

The app does not search this table — it *annotates* foods the user already sees
by matching their name (see ``PurineFoodIndex``), so each row carries a
``base_name`` with the preparation markers stripped ("鸡胸肉(熟)" -> "鸡胸肉").

Levels follow the table's own convention: < 50 mg/100 g low, 50–150 medium,
> 150 high. Every row is checked against those thresholds, so a hand edit that
disagrees with its level fails the build instead of shipping a wrong badge.

Usage (from repo root):
  uv run python scripts/build_purine_index.py
  uv run python scripts/build_purine_index.py --xlsx ~/Documents/嘌呤表.xlsx

``--xlsx`` re-extracts the CSV from a spreadsheet first (stdlib zipfile +
ElementTree, no openpyxl), which is how the sheet gets updated.
"""

from __future__ import annotations

import argparse
import csv
import re
import sqlite3
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

from _food_index_common import create_schema, set_meta, tokenize_cjk, write_manifest

REPO = Path(__file__).resolve().parents[1]
OUT_DIR = REPO / "android" / "app" / "src" / "main" / "assets" / "purine"
CSV_PATH = REPO / "scripts" / "data" / "purine-table.csv"

DATASET_VERSION = "purine-table-2026-10"
SOURCE_LABEL = "Maintainer purine table (嘌呤表.xlsx)"

# mg per 100 g. Boundaries are inclusive on the low side, matching the sheet.
LOW_MAX = 50.0
MEDIUM_MAX = 150.0
LEVEL_COLUMN = {"低嘌呤": "low", "中嘌呤": "medium", "高嘌呤": "high"}

# (…), （…）, […], ［…］, 【…】: preparation / brand asides, dropped for matching.
ASIDE = re.compile(r"[（(\[［【].*?[）)\]］】]")
NON_WORD = re.compile(r"[^0-9A-Za-z\u3400-\u4dbf\u4e00-\u9fff]+")

_NS = {"m": "http://schemas.openxmlformats.org/spreadsheetml/2006/main"}
_TEXT = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}t"


def level_of(mg: float) -> str:
    if mg < LOW_MAX:
        return "low"
    if mg <= MEDIUM_MAX:
        return "medium"
    return "high"


def base_name(name: str) -> str:
    return NON_WORD.sub("", ASIDE.sub("", name)).lower()


def read_xlsx(path: Path) -> list[list[str]]:
    """Rows of the first worksheet as (类别, 食物名称, 结果 mg/100g, 嘌呤等级)."""
    with zipfile.ZipFile(path) as z:
        shared: list[str] = []
        if "xl/sharedStrings.xml" in z.namelist():
            root = ET.fromstring(z.read("xl/sharedStrings.xml"))
            for si in root.findall("m:si", _NS):
                shared.append("".join(t.text or "" for t in si.iter(_TEXT)))

        sheet = ET.fromstring(z.read("xl/worksheets/sheet1.xml"))
        rows: list[list[str]] = []
        for row in sheet.findall("m:sheetData/m:row", _NS):
            cells: dict[str, str] = {}
            for c in row.findall("m:c", _NS):
                col = re.match(r"[A-Z]+", c.get("r", "")).group(0)
                value = c.find("m:v", _NS)
                inline = c.find("m:is", _NS)
                if c.get("t") == "s" and value is not None:
                    text = shared[int(value.text)]
                elif inline is not None:
                    text = "".join(t.text or "" for t in inline.iter(_TEXT))
                else:
                    text = value.text if value is not None else ""
                cells[col] = (text or "").strip()
            rows.append([cells.get("A", ""), cells.get("B", ""), cells.get("C", ""), cells.get("D", "")])

    out: list[list[str]] = []
    category = ""
    for cells in rows[1:]:
        if cells[0]:
            category = cells[0]
        name, value, level = cells[1], cells[2], cells[3]
        if not name or not value:
            continue  # blank spacer rows in the sheet
        out.append([category, name, value, level])
    return out


def write_csv(rows: list[list[str]], path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as fh:
        writer = csv.writer(fh)
        writer.writerow(["category", "name", "purine_mg_per_100g", "level"])
        writer.writerows(rows)


def load_csv(path: Path) -> list[tuple[str, str, float, str]]:
    with path.open("r", encoding="utf-8", newline="") as fh:
        reader = csv.DictReader(fh)
        expected = {"category", "name", "purine_mg_per_100g", "level"}
        if not expected.issubset(set(reader.fieldnames or ())):
            raise SystemExit(f"Unexpected CSV headers: {reader.fieldnames}")
        rows = []
        for raw in reader:
            name = (raw["name"] or "").strip()
            if not name:
                continue
            mg = float(raw["purine_mg_per_100g"])
            level = LEVEL_COLUMN.get((raw["level"] or "").strip())
            if level is None:
                raise SystemExit(f"Unknown level {raw['level']!r} for {name}")
            rows.append(((raw["category"] or "").strip(), name, mg, level))
    return rows


def build(db_path: Path, rows: list[tuple[str, str, float, str]]) -> int:
    db_path.parent.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()

    written = 0
    conn = sqlite3.connect(db_path)
    try:
        # The shared index shape, plus the FTS table the app never queries (it
        # loads all 240 rows once and matches names in memory). Keeping the
        # schema uniform is worth the few KB and keeps meta/manifest handling shared.
        create_schema(
            conn,
            nutrient_columns=["purine_mg"],
            fixed_columns=[
                "id INTEGER PRIMARY KEY",
                "category TEXT NOT NULL",
                "name TEXT NOT NULL",
                "base_name TEXT NOT NULL",
                "level TEXT NOT NULL",
                "tokens TEXT NOT NULL",
            ],
            fts_columns=["name", "tokens"],
            fts_rowid="id",
            extra_statements=[
                "CREATE INDEX foods_name_idx ON foods(name);",
                "CREATE INDEX foods_base_idx ON foods(base_name);",
            ],
        )
        # Several foods differ only by a preparation marker (羊腿肉(生) /
        # 羊腿肉(熟)), so they share a base name on purpose: the app matches on
        # it and prefers the higher value, because cooking almost always lowers
        # measured purine per 100 g and under-warning is the harmful direction.
        for category, name, mg, level in rows:
            base = base_name(name)
            if not base:
                raise SystemExit(f"Empty base name for {name}")
            expected = level_of(mg)
            if expected != level:
                raise SystemExit(
                    f"{name}: {mg} mg is '{expected}' but the sheet says '{level}'"
                )
            conn.execute(
                "INSERT INTO foods (id, category, name, base_name, level, tokens, purine_mg) "
                "VALUES (?,?,?,?,?,?,?)",
                (written, category, name, base, level, tokenize_cjk(name), mg),
            )
            written += 1
        conn.execute("INSERT INTO foods_fts(foods_fts) VALUES('rebuild')")
        set_meta(conn, "dataset_version", DATASET_VERSION)
        set_meta(conn, "source", SOURCE_LABEL)
        set_meta(conn, "low_max_mg", str(LOW_MAX))
        set_meta(conn, "medium_max_mg", str(MEDIUM_MAX))
        conn.commit()
    finally:
        conn.close()
    return written


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", type=Path, default=OUT_DIR)
    parser.add_argument("--csv", type=Path, default=CSV_PATH)
    parser.add_argument("--xlsx", type=Path, help="Re-extract the CSV from a spreadsheet first")
    args = parser.parse_args()

    if args.xlsx is not None:
        if not args.xlsx.is_file():
            raise SystemExit(f"No such spreadsheet: {args.xlsx}")
        extracted = read_xlsx(args.xlsx)
        write_csv(extracted, args.csv)
        print(f"Extracted {len(extracted)} rows -> {args.csv}")

    rows = load_csv(args.csv)
    print(f"Building {DATASET_VERSION} from {len(rows)} rows into {args.out_dir}")
    db_path = args.out_dir / "purine_foods.sqlite"
    count = build(db_path, rows)
    manifest = write_manifest(
        db_path,
        {
            "dataset_version": DATASET_VERSION,
            "food_count": count,
            "low_max_mg": LOW_MAX,
            "medium_max_mg": MEDIUM_MAX,
            "source": SOURCE_LABEL,
        },
    )
    print(f"  wrote {db_path} ({db_path.stat().st_size} bytes, {count} foods)")
    print(f"  wrote {manifest}")


if __name__ == "__main__":
    main()
