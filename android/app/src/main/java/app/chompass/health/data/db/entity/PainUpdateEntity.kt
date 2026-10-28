package app.chompass.health.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一次发作过程中的**疼痛变化打点**。
 *
 * ## 只存评分，不存「加重 / 减轻」
 *
 * 趋势是**推导态，绝不落库**——与 `dose_instance` 的 OVERDUE/LATE 同一条原则。
 *
 * 原因不是洁癖：如果让用户既选「减轻」又填评分，这两件事必然互相矛盾。
 * 用户点了「减轻」但把 6 改成 8，界面上到底信哪个？一旦落库，就要写
 * "以标签为准"还是"以评分为准"的裁决逻辑，而这个裁决没有正确答案。
 *
 * 只存 [nrs]，趋势由「与上一个点的差值」算出：
 * `+N → 加重` / `-N → 减轻` / `0 → 不变`。单一数据源，永远不会自相矛盾。
 *
 * ## 为什么 t0（开始那条）不在这张表里
 *
 * 开始时填的评分已经在 `pain_episode.nrs`，它是 [episodeId] 这条时间线的**首点**。
 * 这张表只存**之后的**变化。好处是升级兼容：老数据没有打点，当前评分自然退化
 * 为开始评分，统计口径也不变。
 *
 * 反过来说，**当前评分 ≠ `pain_episode.nrs`**（后者是开始时的快照）。
 * 读当前值必须走 `PainRepository.observeTimelines()`，不要直接拿 `nrs` 当现状用。
 *
 * ## 软删除
 *
 * 发作是软删除（`pain_episode.deleted_at`），外键的 CASCADE 在这条链路上不会被触发，
 * 它只是防止孤儿行。打点自身也有 `deleted_at`：误点两下「加重」后要能撤掉。
 */
@Entity(
    tableName = "pain_update",
    indices = [
        Index(value = ["episode_id"]),
        Index(value = ["at"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = PainEpisodeEntity::class,
            parentColumns = ["id"],
            childColumns = ["episode_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PainUpdateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "episode_id")
    val episodeId: Long,
    /** 打点时刻（epoch millis） */
    val at: Long,
    /** 0–10 疼痛评分 */
    val nrs: Int,
    val note: String? = null,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long? = null,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
