#!/usr/bin/env python3
"""Build a compact China Food Composition Table SQLite index for Chompass.

Downloads the single-file CSV export of 《中国食物成分表标准版（第6版）》 —
"能量和食物一般营养成分" — from the pinned Sanotsu/china-food-composition-data
mirror (the ``_fixed_en`` release: the 68 hand-corrected 野生蔬菜类 kcal/kJ
columns, plus the English-name backfill), normalizes nutrients to Chompass units,
and writes a compact Chinese-language index:

  android/app/src/main/assets/cn/cn_foods.sqlite
  android/app/src/main/assets/cn/cn_foods.manifest.json

Every row is per 100 g of edible portion, as printed in the table. The source has
no sugar / saturated-fat / vitamin-D / B12 / K / folate columns, so those stay
NULL — the app must not invent them.

Text values keep the book's own notation: "—" means not measured (NULL here) and
"Tr"/"un" mean trace / unknown (0.0 / NULL). Values are OCR'd from the printed
tables, not machine-read from an authoritative feed, so treat them as
book-faithful rather than lab-grade.

Usage (from repo root):
  uv run python scripts/build_cn_food_index.py
  uv run python scripts/build_cn_food_index.py --csv-path path/to/food_composition_full.csv

Raw downloads land under build/cn-cfcd/ (gitignored). Source mirror:
https://github.com/Sanotsu/china-food-composition-data — see ASSET_CREDITS.md for
the provenance and the copyright caveat attached to the underlying book.
"""

from __future__ import annotations

import argparse
import csv
import io
import math
import re
import sqlite3
from pathlib import Path
from urllib.request import urlretrieve

from _food_index_common import (
    create_schema,
    rebuild_fts,
    set_meta,
    tokenize_cjk,
    write_manifest,
)

REPO = Path(__file__).resolve().parents[1]
OUT_DIR = REPO / "android" / "app" / "src" / "main" / "assets" / "cn"
CACHE_DIR = REPO / "build" / "cn-cfcd"

DATASET_VERSION = "cn-fct-6th-ed-20260825-fixed-en"
SOURCE_LABEL = "China Food Composition Tables, Standard Edition (6th ed.) — Sanotsu mirror"

# Pinned release directory. The CSV is the tabular export of the same rows as the
# 61 per-category JSON files, with the English names already backfilled.
RELEASE_DIR = "json_data_v3_20260825_qwen38max_kimi_k3_fixed_en"
CSV_URL = (
    "https://raw.githubusercontent.com/Sanotsu/china-food-composition-data/main/"
    f"{RELEASE_DIR}/food_composition_full.csv"
)
CSV_NAME = "food_composition_full.csv"

EXPECTED_HEADERS = {
    "category",
    "foodCode",
    "foodName",
    "edible",
    "energyKCal",
    "protein",
    "fat",
    "CHO",
    "dietaryFiber",
    "cholesterol",
    "vitaminA",
    "vitaminC",
    "vitaminETotal",
    "Ca",
    "K",
    "Na",
    "Mg",
    "Fe",
    "Zn",
}

# CSV column (per 100 g edible portion) -> Chompass column. The book already
# prints the Chompass units (macros g, cholesterol/minerals/vitamin C/E mg,
# vitamin A µg RE), so no conversion factors are applied.
NUTRIENT_MAP = {
    "calories": "energyKCal",
    "protein": "protein",
    "carbs": "CHO",
    "fat": "fat",
    "fiber": "dietaryFiber",
    "cholesterol": "cholesterol",
    "sodium": "Na",
    "potassium": "K",
    "calcium": "Ca",
    "iron": "Fe",
    "magnesium": "Mg",
    "zinc": "Zn",
    "vitamin_a": "vitaminA",
    "vitamin_c": "vitaminC",
    "vitamin_e": "vitaminETotal",
}

NUTRIENT_COLUMNS = list(NUTRIENT_MAP.keys())

# "—" = not measured; "Tr" = trace; "un" = unknown. Footnote asterisks ("899*")
# mark values the book itself flags, and are dropped rather than guessed at.
NOT_MEASURED = {"", "—", "–", "-", "…", "un"}
TRACE = {"tr", "(tr)", "tr."}
FOOTNOTE = re.compile(r"\*+\s*$")


def parse_value(raw: str | None) -> float | None:
    """One cell -> float, or None when the book has no number to give."""
    if raw is None:
        return None
    value = raw.strip()
    if value.lower() in NOT_MEASURED:
        return None
    if value.lower() in TRACE:
        return 0.0
    value = FOOTNOTE.sub("", value)
    try:
        return float(value)
    except ValueError:
        # Nothing else appears in the numeric columns of this release; fail loudly
        # rather than silently turning a misread cell into a blank nutrient.
        raise SystemExit(f"Unparseable cell value: {raw!r}") from None


def download(cache_dir: Path, csv_path: Path | None) -> Path:
    if csv_path is not None:
        return csv_path
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / CSV_NAME
    if not path.exists() or path.stat().st_size == 0:
        print(f"  downloading <- {CSV_URL}")
        urlretrieve(CSV_URL, path)  # noqa: S310 (pinned https mirror)
    return path


def load_rows(path: Path) -> list[dict[str, str]]:
    # utf-8-sig: the CSV export carries a BOM.
    text = path.read_text(encoding="utf-8-sig")
    reader = csv.DictReader(io.StringIO(text, newline=""))
    rows = list(reader)
    headers = set(reader.fieldnames or ())
    missing = EXPECTED_HEADERS - headers
    if missing:
        raise SystemExit(f"Unexpected CSV headers, missing {sorted(missing)}")
    return rows


def build(db_path: Path, csv_path: Path) -> tuple[int, int]:
    """Write [db_path]; return (rows written, rows skipped for missing energy)."""
    rows = load_rows(csv_path)
    db_path.parent.mkdir(parents=True, exist_ok=True)
    if db_path.exists():
        db_path.unlink()

    conn = sqlite3.connect(db_path)
    written = 0
    skipped = 0
    try:
        create_schema(
            conn,
            nutrient_columns=NUTRIENT_COLUMNS,
            fixed_columns=[
                "id INTEGER PRIMARY KEY",
                "food_code TEXT NOT NULL",
                "category TEXT NOT NULL",
                "name TEXT NOT NULL",
                "en_name TEXT",
                "edible_pct REAL",
                "tokens TEXT NOT NULL",
            ],
            fts_columns=["name", "tokens"],
            fts_rowid="id",
            extra_statements=[
                "CREATE INDEX foods_name_idx ON foods(name);",
                "CREATE UNIQUE INDEX foods_code_idx ON foods(food_code);",
            ],
        )
        for row in rows:
            name = (row.get("foodName") or "").strip()
            code = (row.get("foodCode") or "").strip()
            if not name or not code:
                continue
            calories = parse_value(row.get("energyKCal"))
            if calories is None:
                # Search filters rows without energy; a 0 kcal row would read as
                # a free food in the diary. Skip it instead (15 rows in this
                # release: 酒精 and similar "un" cells).
                skipped += 1
                continue
            cols = ["id", "food_code", "category", "name", "en_name", "edible_pct", "tokens"]
            en_name = (row.get("englishName") or "").strip()
            values: list = [
                written,
                code,
                (row.get("category") or "").strip(),
                name,
                en_name or None,
                parse_value(row.get("edible")),
                tokenize_cjk(name),
            ]
            for column, source in NUTRIENT_MAP.items():
                cols.append(column)
                values.append(parse_value(row.get(source)))
            placeholders = ",".join("?" for _ in cols)
            conn.execute(
                f"INSERT INTO foods ({','.join(cols)}) VALUES ({placeholders})",
                values,
            )
            written += 1
        rebuild_fts(conn)
        set_meta(conn, "dataset_version", DATASET_VERSION)
        set_meta(conn, "source", SOURCE_LABEL)
        set_meta(conn, "langs", "zh")
        conn.commit()
    finally:
        conn.close()
    return written, skipped


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out-dir", type=Path, default=OUT_DIR)
    parser.add_argument("--cache-dir", type=Path, default=CACHE_DIR)
    parser.add_argument("--csv-path", type=Path, help="Local food_composition_full.csv (skips download)")
    args = parser.parse_args()

    csv_path = download(args.cache_dir, args.csv_path)
    print(f"Building {DATASET_VERSION} from {csv_path} into {args.out_dir}")
    db_path = args.out_dir / "cn_foods.sqlite"
    count, skipped = build(db_path, csv_path)
    manifest = write_manifest(
        db_path,
        {
            "dataset_version": DATASET_VERSION,
            "food_count": count,
            "langs": ["zh"],
            "source": SOURCE_LABEL,
        },
    )
    size_kb = math.ceil(db_path.stat().st_size / 1024)
    print(f"  wrote {db_path} ({size_kb} KiB, {count} foods, {skipped} skipped without kcal)")
    print(f"  wrote {manifest}")


if __name__ == "__main__":
    main()
