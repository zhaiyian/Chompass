import json
import pathlib

p = pathlib.Path("testdata/parity/locales.json")
d = json.loads(p.read_text(encoding="utf-8"))
addition = (
    " Raised the cap from 500 to 525 for every pack except de and zh-CN on 2026-10-28 when the"
    " medication-reminder stage shipped (23 keys: two notification channels, the dose and refill"
    " notifications, and the reminders card): en, de and zh-CN carry the strings, the remaining"
    " packs fall back to EN and sit at 522-523."
)
if "medication-reminder stage" not in d["notes"]:
    d["notes"] = d["notes"].rstrip() + addition
for loc in d["locales"]:
    if loc["id"] in ("en", "de", "zh-CN"):
        continue
    loc["maxMissing"] = 525
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(d["notes"][-260:])
for loc in d["locales"]:
    print(loc["id"], loc.get("maxMissing"))
