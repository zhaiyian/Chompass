#!/usr/bin/env python3
"""One-shot: append the medication-reminder strings to values / values-de / values-zh-rCN."""
from pathlib import Path

RES = Path(__file__).resolve().parent / "app" / "src" / "main" / "res"

S = {}


def add(key, en, de, zh):
    S[key] = (en, de, zh)


# --- channels -------------------------------------------------------------
add(
    "notif_health_dose_channel",
    "Dose reminders",
    "Einnahmeerinnerungen",
    "服药提醒",
)
add(
    "notif_health_dose_channel_desc",
    "Reminds you at each planned dose time",
    "Erinnert dich zu jeder geplanten Einnahmezeit",
    "到计划服药时间提醒你",
)
add(
    "notif_health_stock_channel",
    "Refill alerts",
    "Nachfüll-Hinweise",
    "续药提醒",
)
add(
    "notif_health_stock_channel_desc",
    "Warns you before a medication runs out",
    "Meldet sich, bevor ein Medikament ausgeht",
    "药物快用完时提前提醒",
)

# --- dose notification ----------------------------------------------------
add(
    "notif_health_dose_title",
    "Time for %1$s",
    "Zeit für %1$s",
    "该服用 %1$s 了",
)
add(
    "notif_health_dose_text",
    "%1$s · planned %2$s",
    "%1$s · geplant %2$s",
    "%1$s · 计划 %2$s",
)
add(
    "notif_health_dose_big",
    "%1$s · planned %2$s. Record it, snooze 10 minutes, or skip this dose from here.",
    "%1$s · geplant %2$s. Hier erfassen, 10 Minuten später erinnern oder diese Dosis überspringen.",
    "%1$s · 计划 %2$s。可以直接记录、10 分钟后再提醒，或跳过这次。",
)
add("notif_health_action_take", "Taken", "Eingenommen", "已服用")
add("notif_health_action_snooze", "Snooze 10 min", "10 Min. später", "10 分钟后")
add("notif_health_action_skip", "Skip this dose", "Dosis überspringen", "跳过这次")

# --- refill notification --------------------------------------------------
add("notif_health_stock_title", "Time to refill", "Zeit zum Nachfüllen", "该续药了")
add(
    "notif_health_stock_text",
    "%1$s has about %2$d days left",
    "%1$s hat noch etwa %2$d Tage",
    "%1$s 还剩约 %2$d 天",
)
add(
    "notif_health_stock_empty",
    "%1$s has run out",
    "%1$s ist aufgebraucht",
    "%1$s 已用完",
)

# --- reminder card --------------------------------------------------------
add(
    "health_med_reminder_on",
    "On · you are notified at each planned time",
    "An · Hinweis zu jeder geplanten Zeit",
    "已开启 · 到点发送提醒",
)
add(
    "health_med_reminder_off",
    "Off · nothing will be sent",
    "Aus · es wird nichts gesendet",
    "已关闭 · 不会发出任何通知",
)
add(
    "health_med_reminder_off_hint",
    "Only notifications stop. The plan, overdue marks and stock days keep working.",
    "Nur die Hinweise entfallen. Plan, Überfällig-Marken und Vorratstage bleiben unverändert.",
    "关掉的只是通知。今日清单、逾期标记与库存天数照常计算。",
)
add(
    "health_med_reminder_perm_title",
    "Notification permission",
    "Benachrichtigungen erlauben",
    "通知权限",
)
add("health_med_reminder_perm_ok", "Allowed", "Erlaubt", "已允许")
add(
    "health_med_reminder_perm_blocked",
    "Not allowed · reminders cannot appear",
    "Nicht erlaubt · Hinweise können nicht erscheinen",
    "未允许 · 提醒不会出现",
)
add(
    "health_med_reminder_exact_title",
    "Exact alarms",
    "Exakte Alarme",
    "精确闹钟",
)
add(
    "health_med_reminder_exact_ok",
    "Allowed · fires on the minute",
    "Erlaubt · pünktlich zur Minute",
    "已允许 · 准点提醒",
)
add(
    "health_med_reminder_exact_blocked",
    "Not allowed · reminders may arrive a few minutes late",
    "Nicht erlaubt · Hinweise können einige Minuten später kommen",
    "未允许 · 提醒可能晚几分钟",
)
add("health_med_reminder_open", "Open settings", "Einstellungen öffnen", "去开启")


def escape(text):
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "\\'")
    )


BLOCK_HEADER = "    <!-- Health diary: medication reminders -->\n"


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
