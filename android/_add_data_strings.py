#!/usr/bin/env python3
"""One-shot: append the health-diary backup/settings strings to values / values-de / values-zh-rCN."""
from pathlib import Path

RES = Path(__file__).resolve().parent / "app" / "src" / "main" / "res"

S = {}


def add(key, en, de, zh):
    S[key] = (en, de, zh)


# --- CSV cell values ------------------------------------------------------
add("health_csv_yes", "Yes", "Ja", "是")
add("health_csv_no", "No", "Nein", "否")
add("health_dose_kind_scheduled", "Scheduled", "Geplant", "按时")
add("health_dose_kind_backfill", "Backfilled", "Nachgetragen", "补记")
add("health_dose_kind_prn", "As needed", "Nach Bedarf", "按需服用")
add("health_dose_kind_extra", "Extra dose", "Zusätzlich", "额外服用")

# --- readme.txt -----------------------------------------------------------
add(
    "health_export_readme_title",
    "Chompass · health diary export",
    "Chompass · Export des Gesundheitstagebuchs",
    "Chompass · 健康日记数据导出",
)
add(
    "health_export_readme_exported_at",
    "Exported %1$s",
    "Exportiert am %1$s",
    "导出时间 %1$s",
)
add(
    "health_export_readme_files",
    "Files: uric.csv (uric acid readings), pain.csv (flares), pain_update.csv (scores within a flare), dose_log.csv (doses).",
    "Dateien: uric.csv (Harnsäure), pain.csv (Schübe), pain_update.csv (Verlauf innerhalb eines Schubs), dose_log.csv (Einnahmen).",
    "文件：uric.csv（尿酸测量）、pain.csv（疼痛发作）、pain_update.csv（发作过程中的评分变化）、dose_log.csv（服药记录）。",
)
add("health_export_readme_conventions", "Conventions", "Konventionen", "字段约定")
add(
    "health_export_readme_codes",
    "A cell like Right [RIGHT] carries a readable label plus the stable code an import reads.",
    "Eine Zelle wie Right [RIGHT] enthält ein lesbares Etikett plus den stabilen Code, den der Import liest.",
    "形如「右侧 [RIGHT]」的单元格同时带有可读文案与导入时读取的稳定代码。",
)
add(
    "health_export_readme_units",
    "value_umol is always μmol/L; input_value and input_unit keep what was typed.",
    "value_umol ist immer μmol/L; input_value und input_unit bewahren die ursprüngliche Eingabe.",
    "value_umol 统一为 μmol/L；input_value 与 input_unit 保留当初的输入。",
)
add(
    "health_export_readme_deleted",
    "Deleted records and undone doses are not included.",
    "Gelöschte Einträge und widerrufene Einnahmen sind nicht enthalten.",
    "已删除的记录与已撤销的服药记录不在导出范围内。",
)
add(
    "health_export_readme_privacy",
    "Generated on this device. There is no cloud copy.",
    "Auf diesem Gerät erzeugt. Es gibt keine Cloud-Kopie.",
    "本文件由本机数据生成，不含任何云端副本。",
)
add(
    "health_export_readme_disclaimer",
    "Doses follow the prescription; this record supports the conversation with your doctor, it is not a diagnosis.",
    "Die Dosierung folgt der Verordnung; diese Aufzeichnung dient dem Gespräch mit der Ärztin oder dem Arzt, nicht der Diagnose.",
    "用药请遵医嘱；本记录仅供就诊时与医生讨论，不作为诊断依据。",
)

# --- settings & backup screen --------------------------------------------
add("health_data_title", "Settings & backup", "Einstellungen & Sicherung", "设置与备份")
add("health_data_personal", "Personal", "Persönlich", "个人设置")
add("health_data_uric_target", "Uric acid target", "Harnsäure-Zielwert", "尿酸目标值")
add(
    "health_data_uric_target_value",
    "%1$d μmol/L · decides the on-target badge and the remaining gap",
    "%1$d μmol/L · bestimmt Ziel-Marke und Restdifferenz",
    "%1$d μmol/L · 达标判定与「还差多少」都按它算",
)
add("health_data_change", "Change", "Ändern", "修改")
add(
    "health_data_uric_target_hint",
    "Common targets: 360 (raised uric acid), 300 (gout or tophi). Follow your doctor.",
    "Übliche Ziele: 360 (erhöhte Harnsäure), 300 (Gicht oder Tophi). Bitte nach ärztlicher Vorgabe.",
    "常见目标：360（一般高尿酸血症）· 300（合并痛风或痛风石）。具体请遵医嘱。",
)
add(
    "health_data_target_range",
    "The target must be between %1$d and %2$d μmol/L.",
    "Der Zielwert muss zwischen %1$d und %2$d μmol/L liegen.",
    "目标值需在 %1$d–%2$d μmol/L 之间。",
)
add(
    "health_data_save_failed",
    "Could not save. Please try again.",
    "Speichern fehlgeschlagen. Bitte erneut versuchen.",
    "保存失败，请重试。",
)
add("health_data_backup", "Backup", "Sicherung", "数据备份")
add("health_data_last_export", "Last export %1$s", "Letzter Export %1$s", "上次导出 %1$s")
add(
    "health_data_backup_body",
    "Four CSV tables in one zip: readable in Excel and worth handing over at a visit.",
    "Vier CSV-Tabellen in einer Zip-Datei: in Excel lesbar und für den Arztbesuch geeignet.",
    "四张 CSV 表打包成一个 zip，可用 Excel 打开，也适合就诊时直接给医生看。",
)
add(
    "health_data_counts",
    "%1$d records · uric %2$d · pain %3$d · doses %4$d",
    "%1$d Einträge · Harnsäure %2$d · Schmerz %3$d · Einnahmen %4$d",
    "共 %1$d 条记录（尿酸 %2$d · 疼痛 %3$d · 服药 %4$d）",
)
add("health_data_export", "Export", "Exportieren", "导出数据")
add("health_data_exporting", "Exporting…", "Wird exportiert…", "正在导出…")
add(
    "health_data_export_empty",
    "There is nothing to export yet — the file contains headers only.",
    "Noch keine Daten: die Datei enthält nur Kopfzeilen.",
    "还没有任何记录，导出的是空表。",
)
add(
    "health_data_export_failed",
    "Export failed. Please try again.",
    "Export fehlgeschlagen. Bitte erneut versuchen.",
    "导出失败，请重试。",
)
add(
    "health_data_export_overdue_never",
    "You have never exported a backup. A lost or replaced phone takes these records with it.",
    "Noch nie gesichert. Ein verlorenes oder neues Telefon nimmt diese Einträge mit.",
    "还没有导出过备份。换机、丢手机或误删应用后，这些记录会全部消失。",
)
add(
    "health_data_export_overdue_days",
    "Last export was %1$d days ago; once every 30 days is plenty.",
    "Letzter Export vor %1$d Tagen; alle 30 Tage genügt.",
    "距离上次导出已 %1$d 天，建议每 30 天导出一份。",
)
add(
    "health_data_import_title",
    "Restore from a backup",
    "Aus Sicherung wiederherstellen",
    "从备份恢复",
)
add(
    "health_data_import_body",
    "Pick the zip that the export produced",
    "Wähle die Zip-Datei aus dem Export",
    "选择之前「导出数据」生成的 zip",
)
add("health_data_import_action", "Choose file", "Datei wählen", "选择文件")
add("health_data_importing", "Importing…", "Wird importiert…", "正在导入…")
add(
    "health_data_import_merge_note",
    "Merge only: nothing on this phone is deleted. Duplicates are skipped, so importing the same backup twice is safe.",
    "Nur zusammenführen: nichts auf diesem Gerät wird gelöscht. Duplikate werden übersprungen, mehrfaches Importieren ist unbedenklich.",
    "只做合并，不会覆盖本机数据；重复的记录自动跳过，同一份备份导入多次也不会重复。",
)
add("health_data_app", "App", "App", "应用")
add("health_data_version", "Version", "App-Version", "版本号")
add("health_data_storage", "Storage", "Speicherung", "数据存储")
add(
    "health_data_storage_value",
    "On this device only · no account · no cloud",
    "Nur auf diesem Gerät · kein Konto · keine Cloud",
    "仅本机 · 不联网 · 无账号",
)
add(
    "health_data_import_done_title",
    "Import finished",
    "Import abgeschlossen",
    "导入完成",
)
add(
    "health_data_import_done",
    "Uric %1$d · pain %2$d (%3$d score marks) · doses %4$d",
    "Harnsäure %1$d · Schmerz %2$d (%3$d Verlaufspunkte) · Einnahmen %4$d",
    "尿酸 %1$d · 疼痛 %2$d（含 %3$d 个打点）· 服药 %4$d",
)
add(
    "health_data_import_dup",
    "%1$d duplicates skipped",
    "%1$d Duplikate übersprungen",
    "跳过重复 %1$d 条",
)
add(
    "health_data_import_skipped",
    "%1$d rows could not be read",
    "%1$d Zeilen unlesbar",
    "%1$d 行无法识别",
)
add(
    "health_data_import_empty",
    "This backup contains no usable records.",
    "Diese Sicherung enthält keine verwertbaren Einträge.",
    "这个备份里没有可用记录。",
)
add(
    "health_data_import_unreadable",
    "Could not read the file. Pick the zip produced by the export.",
    "Datei nicht lesbar. Wähle die Zip-Datei aus dem Export.",
    "读不到这个文件，请确认选的是导出的 zip。",
)
add(
    "health_data_import_no_tables",
    "The archive contains no CSV files.",
    "Das Archiv enthält keine CSV-Dateien.",
    "压缩包里没有找到 CSV。",
)
add(
    "health_data_import_failed",
    "Import failed. Check that the file is a zip produced by the export.",
    "Import fehlgeschlagen. Prüfe, ob die Datei aus dem Export stammt.",
    "导入失败，请确认文件是「导出数据」生成的 zip。",
)
add("health_data_import_issue", "%1$s line %2$d", "%1$s Zeile %2$d", "%1$s 第 %2$d 行")


def escape(text):
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
    )


BLOCK_HEADER = "    <!-- Health diary: settings and CSV backup -->\n"


def patch(folder, index):
    path = RES / folder / "strings.xml"
    text = path.read_text(encoding="utf-8")
    body = "".join(
        '    <string name="%s">%s</string>\n' % (key, escape(values[index]))
        for key, values in S.items()
        if 'name="%s"' % key not in text
    )
    text = text.replace("</resources>", BLOCK_HEADER + body + "</resources>")
    path.write_text(text, encoding="utf-8")
    print(folder, "->", body.count("<string"), "added")


patch("values", 0)
patch("values-de", 1)
patch("values-zh-rCN", 2)
