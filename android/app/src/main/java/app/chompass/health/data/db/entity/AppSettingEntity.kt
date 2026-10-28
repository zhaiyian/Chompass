package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 键值设置（全局单例）。
 *
 * 业务配置留这里；UI 偏好（上次 Tab、图表范围）走 DataStore，不进数据库。
 */
@Entity(tableName = "app_setting")
data class AppSettingEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/** 设置项的键与默认值，集中一处避免字符串散落。 */
object SettingKeys {
    const val URIC_TARGET_UMOL = "uric_target_umol"
    const val URIC_UNIT = "uric_unit"
    const val URIC_LAST_METHOD = "uric_last_method"
    const val PAIN_LAST_SIDE = "pain_last_side"
    const val ADHERENCE_ON_TIME_MIN = "adherence_on_time_min"
    const val STOCK_ALERT_DEFAULT_DAYS = "stock_alert_default_days"
    const val REMINDER_MASTER_ENABLED = "reminder_master_enabled"
    const val DAY_BOUNDARY_HOUR = "day_boundary_hour"
    const val EXPORT_LAST_AT = "export_last_at"

    /**
     * 上次发库存提醒的日期（yyyy-MM-dd）。
     *
     * 存在设置表而不是内存变量：进程随时可能被杀，用内存标记会导致
     * 用户每次冷启动都收到一次"该续药了"，一天能收到七八条。
     */
    const val STOCK_ALERT_LAST_DATE = "stock_alert_last_date"

    val defaults: Map<String, String> = mapOf(
        URIC_TARGET_UMOL to "360",
        URIC_UNIT to "UMOL_L",
        URIC_LAST_METHOD to "FINGER",
        PAIN_LAST_SIDE to "RIGHT",
        ADHERENCE_ON_TIME_MIN to "30",
        STOCK_ALERT_DEFAULT_DAYS to "14",
        REMINDER_MASTER_ENABLED to "1",
        DAY_BOUNDARY_HOUR to "0",
        EXPORT_LAST_AT to "0",
    )
}
