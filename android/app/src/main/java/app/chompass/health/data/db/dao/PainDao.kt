package app.chompass.health.data.db.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import app.chompass.health.data.db.entity.PainEpisodeEntity
import app.chompass.health.data.db.entity.PainJointEntity
import app.chompass.health.data.db.entity.PainUpdateEntity
import app.chompass.health.domain.model.PainSide
import kotlinx.coroutines.flow.Flow

@Dao
interface PainDao {

    @Query(
        """
        SELECT * FROM pain_episode
        WHERE deleted_at IS NULL
        ORDER BY started_at DESC
        """
    )
    fun observeAll(): Flow<List<PainEpisodeEntity>>

    @Query("SELECT * FROM pain_episode WHERE id = :id AND deleted_at IS NULL")
    fun observeById(id: Long): Flow<PainEpisodeEntity?>

    @Query("SELECT * FROM pain_episode WHERE id = :id AND deleted_at IS NULL")
    suspend fun byId(id: Long): PainEpisodeEntity?

    @Query(
        """
        SELECT * FROM pain_episode
        WHERE deleted_at IS NULL AND started_at BETWEEN :from AND :to
        ORDER BY started_at DESC
        """
    )
    suspend fun inWindow(from: Long, to: Long): List<PainEpisodeEntity>

    @Insert
    suspend fun insert(episode: PainEpisodeEntity): Long

    @Update
    suspend fun update(episode: PainEpisodeEntity)

    /* ---------------- 疼痛变化打点（pain_update） ---------------- */

    /**
     * 全量打点流，**不带 WHERE episode_id**。
     *
     * B1 列表要显示每条进行中发作的当前评分，逐条查会变成 N 次 Flow 订阅；
     * 发作记录是十几条量级、打点几十条，一次全读再按 episodeId 分组更省。
     */
    @Query("SELECT * FROM pain_update WHERE deleted_at IS NULL ORDER BY at ASC, id ASC")
    fun observeAllUpdates(): Flow<List<PainUpdateEntity>>

    @Query(
        """
        SELECT * FROM pain_update
        WHERE episode_id = :episodeId AND deleted_at IS NULL
        ORDER BY at ASC, id ASC
        """
    )
    fun observeUpdates(episodeId: Long): Flow<List<PainUpdateEntity>>

    @Insert
    suspend fun insertUpdate(update: PainUpdateEntity): Long

    /**
     * 同一分钟是否已有打点。
     *
     * 用户自己选时间之后，"连点"不再是一个问题（每次都要过一遍表单），
     * 但**同一分钟落两条**仍然没有意义——时间线上会出现两个并列且无法分辨先后的小时:分。
     * 判据从"写入时刻相近"改成"打点时刻落在同一分钟"，与 CSV 只存到分钟的口径一致。
     */
    @Query(
        """
        SELECT * FROM pain_update
        WHERE episode_id = :episodeId AND deleted_at IS NULL AND at / 60000 = :minute
        LIMIT 1
        """
    )
    suspend fun updateAtMinute(episodeId: Long, minute: Long): PainUpdateEntity?

    /** 改写一条打点的评分与时刻（同一分钟重复记录时覆盖，不新增行） */
    @Query("UPDATE pain_update SET nrs = :nrs, at = :at WHERE id = :id")
    suspend fun reviseUpdate(id: Long, nrs: Int, at: Long)

    /** 撤销一次打点。误点「加重」连按两下是常态，必须能退回来。 */
    @Query("UPDATE pain_update SET deleted_at = :at WHERE id = :id AND deleted_at IS NULL")
    suspend fun softDeleteUpdate(id: Long, at: Long): Int

    @Query(
        """
        UPDATE pain_update SET deleted_at = :at
        WHERE episode_id = :episodeId AND deleted_at IS NULL
        """
    )
    suspend fun softDeleteUpdatesOf(episodeId: Long, at: Long)

    /** 导出：只导尚未删除的打点，由调用方按 episode 过滤 */
    @Query("SELECT * FROM pain_update WHERE deleted_at IS NULL ORDER BY at ASC, id ASC")
    suspend fun allUpdatesForExport(): List<PainUpdateEntity>

    /** 记录结束：只写结束时间，其余字段不动。 */
    @Query("UPDATE pain_episode SET ended_at = :endedAt, updated_at = :at WHERE id = :id")
    suspend fun end(id: Long, endedAt: Long, at: Long)

    @Query("UPDATE pain_episode SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)

    /** 导出用全量，时间线升序 */
    @Query(
        """
        SELECT * FROM pain_episode
        WHERE deleted_at IS NULL
        ORDER BY started_at ASC, id ASC
        """
    )
    suspend fun allForExport(): List<PainEpisodeEntity>

    /**
     * 导入去重：同一分钟视为同一次发作。
     * 按分钟而非毫秒：CSV 只存到 `HH:mm`，秒会被截断。
     */
    @Query(
        """
        SELECT COUNT(*) FROM pain_episode
        WHERE deleted_at IS NULL AND started_at / 60000 = :minute
        """
    )
    suspend fun countStartedMinute(minute: Long): Int

    /**
     * 导入打点时按开始时刻找回对应的发作。
     *
     * 备份里的 `episode_id` 是本机自增 id，跨设备没有意义，所以打点文件只带
     * `episode_start`。同分钟不允许两次发作（上面那个去重查询就是依据），因此
     * 按分钟反查是唯一且稳定的。
     */
    @Query(
        """
        SELECT * FROM pain_episode
        WHERE deleted_at IS NULL AND started_at / 60000 = :minute
        LIMIT 1
        """
    )
    suspend fun findByStartMinute(minute: Long): PainEpisodeEntity?

    /**
     * 删除一次发作：连它的打点一起软删。
     *
     * 外键的 CASCADE 指望不上——发作本身是软删除（写 `deleted_at`），不会真的 DELETE，
     * 所以打点必须手动跟着标。漏掉这一步，导出的 CSV 里会出现"孤儿打点"，
     * 而界面上看不到任何对应的发作。
     */
    @Transaction
    suspend fun softDeleteWithUpdates(id: Long, at: Long) {
        softDelete(id, at)
        softDeleteUpdatesOf(id, at)
    }

    /* ---------------- 部位解剖树（pain_joint，内置只读） ---------------- */

    @Query("SELECT * FROM pain_joint ORDER BY region_order ASC, sort_order ASC")
    fun observeJoints(): Flow<List<PainJointEntity>>

    @Query("SELECT * FROM pain_joint ORDER BY region_order ASC, sort_order ASC")
    suspend fun joints(): List<PainJointEntity>

    /**
     * 「最近用过的部位」：按 (部位, 侧别) 去重，取最近记过的那几条。
     *
     * 为什么这个查询值得存在：级联把最常用的部位从 2 击变成 4 击，
     * 这一行是唯一把点击次数拿回去的手段——首次 4 击，之后 1 击。
     * `GROUP BY` 里带 side，是因为「右大脚趾根部」和「左大脚趾根部」是两条不同的快捷项。
     */
    @Query(
        """
        SELECT site_code, side, MAX(started_at) AS last_at
        FROM pain_episode
        WHERE deleted_at IS NULL
        GROUP BY site_code, side
        ORDER BY last_at DESC
        LIMIT :limit
        """
    )
    suspend fun recentSites(limit: Int): List<RecentSiteRow>
}

/** 「最近用过」的投影：只要三个字段，不要把整条发作捞回来。 */
data class RecentSiteRow(
    @ColumnInfo(name = "site_code") val siteCode: String,
    val side: PainSide,
    @ColumnInfo(name = "last_at") val lastAt: Long,
)
