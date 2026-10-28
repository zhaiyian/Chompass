package app.chompass.health.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 时间工具。
 *
 * 约定：业务时间一律存 epoch millis，同时冗余一列本地 `yyyy-MM-dd`
 * 用于按天分组与索引。冗余列只允许由这里推导，不允许手工填。
 *
 * 日界取本地 00:00（`day_boundary_hour = 0`）：
 * 以药盒日期为准，用户在凌晨 1 点补记昨天的药，仍算昨天。
 */
object TimeUtils {

    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val MONTH_DAY_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd HH:mm")

    val zone: ZoneId get() = ZoneId.systemDefault()

    fun toLocalDate(at: Long): LocalDate =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDate()

    fun toLocalDateTime(at: Long): LocalDateTime =
        Instant.ofEpochMilli(at).atZone(zone).toLocalDateTime()

    /** yyyy-MM-dd，冗余列写入用 */
    fun toDateString(at: Long): String = toLocalDate(at).format(DATE)

    /** HH:mm，与 med_schedule.time_of_day 同格式 */
    fun toTimeString(at: Long): String = toLocalDateTime(at).format(TIME)

    /** MM/dd HH:mm，列表副行用 */
    fun toMonthDayTime(at: Long): String = toLocalDateTime(at).format(MONTH_DAY_TIME)

    /** MM/dd，日期选择框用 */
    fun toMonthDay(at: Long): String =
        toLocalDateTime(at).format(DateTimeFormatter.ofPattern("MM/dd"))

    fun dateString(date: LocalDate): String = date.format(DATE)

    fun today(now: Long = System.currentTimeMillis()): LocalDate = toLocalDate(now)

    fun todayString(now: Long = System.currentTimeMillis()): String = toDateString(now)

    /** 本地日期 + HH:mm 合成 epoch millis。时间点非法时返回 null，由调用方报错。 */
    fun at(date: LocalDate, timeOfDay: String): Long? {
        val time = runCatching { LocalTime.parse(timeOfDay, TIME) }.getOrNull() ?: return null
        return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    fun at(date: String, timeOfDay: String): Long? {
        val d = runCatching { LocalDate.parse(date, DATE) }.getOrNull() ?: return null
        return at(d, timeOfDay)
    }

    /** 当天 00:00 的 epoch millis，做区间查询用。 */
    fun startOfDay(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    /** 当天 23:59:59.999，做闭区间查询用。 */
    fun endOfDay(date: LocalDate): Long =
        date.atTime(LocalTime.MAX).atZone(zone).toInstant().toEpochMilli()

    fun startOfDay(now: Long): Long = startOfDay(toLocalDate(now))

    fun endOfDay(now: Long): Long = endOfDay(toLocalDate(now))

    // Duration and "today / yesterday" prose used to live here as Kotlin
    // literals in Chinese. They are user-facing text, so they belong to string
    // resources and are built in the UI layer where a `Context` is available.

    /**
     * Material3 `DatePicker` 内部的 millis 一律是**UTC 零点**，与本地时区无关。
     *
     * 直接传本地 epoch 会错一天：UTC+8 的 07:00 对应 UTC 前一天 23:00，
     * picker 会把它截成前一天的 00:00 并高亮成"昨天"，用户点确定就把记录挪走一天。
     * 进出 picker 必须都经过这两个函数。
     */
    fun toPickerMillis(at: Long): Long =
        toLocalDate(at).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    /** Same UTC-midnight convention, for a date that has no time of day yet. */
    fun toPickerMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromPickerMillis(millis: Long): LocalDate =
        Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}
