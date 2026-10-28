package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.chompass.health.domain.model.PainSide

/**
 * 一次疼痛发作。
 *
 * `endedAt == null` 表示仍在发作。同一时刻最多只有一条进行中的记录，
 * 该约束由**部分唯一索引**保证（Room 的 `@Index` 不支持 WHERE，
 * 在 [app.chompass.health.data.db.AppDatabase] 的 onCreate 与迁移里手写 execSQL）。
 *
 * `siteCode` 是单值且**不带左右**：左右是正交维度，混进 code 会让
 * 「部位分布」被侧别切碎成三倍长清单。展示时按 `side + label` 拼接。
 */
@Entity(
    tableName = "pain_episode",
    indices = [
        Index(value = ["started_at"]),
    ],
)
data class PainEpisodeEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    /** null = 仍在发作 */
    @ColumnInfo(name = "ended_at")
    val endedAt: Long? = null,
    /** 部位字典 code，如 FOOT_MTP1 */
    @ColumnInfo(name = "site_code")
    val siteCode: String,
    val side: PainSide = PainSide.NA,
    /** 0–10 疼痛评分 */
    val nrs: Int,
    /** 性质 code 的 CSV，≤ 3 项 */
    val natures: List<String>? = null,
    /** 诱因 code 的 CSV，≤ 3 项 */
    val triggers: List<String>? = null,
    @ColumnInfo(name = "affect_walk")
    val affectWalk: Boolean = false,
    @ColumnInfo(name = "affect_sleep")
    val affectSleep: Boolean = false,
    val note: String? = null,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
