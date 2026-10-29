# Asset Credits

## Upstream

Chompass is forked from [Fud AI](https://github.com/apoorvdarshan/fud-ai) by Apoorv Darshan (MIT License).

## Barcode nutrition

Barcode product lookups are powered by the [Open Food Facts](https://world.openfoodfacts.org) database, queried live via its public API. Open Food Facts data is available under the [Open Database License (ODbL)](https://opendatacommons.org/licenses/odbl/1-0/). Chompass does not bundle the database: nutrition facts are fetched per scanned barcode. The Add Food sheet can also query Open Food Facts live by product/brand search string when that source is on (on by default; switch it off in Settings or on the search's databases heading; same ODbL terms, query only: never diary data). Product photos shown in the barcode review sheet are fetched per barcode from Open Food Facts' image CDN (`images.openfoodfacts.org`) and are CC BY-SA 3.0; Chompass displays them privately on your device with in-app attribution (the "Data & photo: Open Food Facts" line links to the product page) and never redistributes them.

## Food databases (offline search)

The Add Food sheet reads three bundled offline databases, each under its own license: results keep a provenance badge so the source is never ambiguous:

- **USDA FoodData Central** (Foundation + FNDDS subset, CC0 / public domain): `android/app/src/main/assets/usda/usda_foods.sqlite`, rebuilt from the pinned FoodData Central bulk CSV release by `scripts/build_usda_food_index.py`. Cite USDA when redistributing.
- **Swiss Food Composition Database**: `android/app/src/main/assets/swiss/swiss_foods.sqlite`, built by `scripts/build_swiss_food_index.py`. Source: Federal authorities of the Swiss Confederation, [naehrwertdaten.ch](https://naehrwertdaten.ch). The four localized CSV files (en/de/fr/it) are downloaded into `build/swiss-sfdc/` (gitignored) from the mirror bundle shipped by the [Food You](https://github.com/maksimowiczm/FoodYou) open-source project (GPL-3.0): the underlying data is the official federal dataset, not Food You code.
- **China Food Composition Tables** (《中国食物成分表标准版（第 6 版）》): `android/app/src/main/assets/cn/cn_foods.sqlite`, built by `scripts/build_cn_food_index.py` from the single-file CSV export of the [Sanotsu/china-food-composition-data](https://github.com/Sanotsu/china-food-composition-data) mirror (downloaded into `build/cn-cfcd/`, gitignored). Rows are per 100 g of edible portion, Chinese names only; the mirror is a community OCR transcription of the printed tables and does not carry an open-data license, so the values are book-faithful rather than lab-grade and the underlying book's copyright rests with its publisher. Revisit that redistribution question before any store listing that requires licensed data.

## Purine table (lookup, not a search source)

- **Purine values**: `android/app/src/main/assets/purine/purine_foods.sqlite`, built by `scripts/build_purine_index.py` from `scripts/data/purine-table.csv` — the maintainer's own 嘌呤表.xlsx (240 foods, mg of total purine per 100 g, grouped by 肉类 / 海鲜 / 豆制品 / 酒类 / 菜肴 / 调料酱类 / 咖啡饮品 / 蔬菜水果菌菇 / 其他). The spreadsheet is not a published dataset and carries no open-data license, so the table ships as an in-app reference aid: values are third-party measurements reproduced as given, not lab-verified by this project, and they back a low / medium / high badge rather than medical advice. Re-extract the CSV with `uv run python scripts/build_purine_index.py --xlsx <sheet>` after editing the spreadsheet; the build fails if a row's stated band disagrees with the <50 / 50–150 / >150 mg thresholds.

## App icon

Chompass launcher icons and splash logos are original artwork by fitguy, distinct from upstream Fud AI artwork (which was provided by the original project owner). The master source is the SVG brand mark `scripts/assets/chompass_icon_mark.svg` (compass ring + cardinal diamonds by fitguy; needle path from the CC0 fork below); regenerate the mark with `uv run python scripts/assets/build_icon_mark.py`, then themed and density variants with `uv run --with pillow python scripts/generate_icons.py` (via `resvg`). The icon script also writes a generated teal preview (`scripts/chompass_icon_master.png`); edit the SVG, not the PNG. Default brand (Android teal launcher, PWA install icons, website favicon, F-Droid listing) uses the teal gradient `#006B5E` → `#5CC48F`.

The compass-needle silhouette is adapted from [Fork SVG](https://www.svgrepo.com/svg/203809/fork) on SVG Repo (`scripts/assets/fork_needle_svgrepo.svg`), dedicated to the public domain under [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/).

On Android API 26+, home-screen icons use adaptive XML (`mipmap-anydpi-v26`) with a full-bleed theme background, a safe-zone foreground logo, and a monochrome layer so the system icon-shape mask and Material You themed icons apply. PWA and store listing icons remain pre-shaped squircles.

## Display numerals

Home, progress, and water numerals use [Doto](https://github.com/oliverlalan/Doto) by Óliver Lalan (The Doto Project Authors), licensed under the [SIL Open Font License 1.1](https://openfontlicense.org). The variable file is `android/app/src/main/res/font/doto.ttf`, taken from [google/fonts `ofl/doto`](https://github.com/google/fonts/tree/main/ofl/doto) (`Doto[ROND,wght].ttf`). Numerals pin wght 700 and ROND 0. Body text does not use this font.
