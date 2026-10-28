package app.chompass.health.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.chompass.health.data.db.entity.UricRecordEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UricDao {

    @Query(
        """
        SELECT * FROM uric_record
        WHERE deleted_at IS NULL AND measured_at BETWEEN :from AND :to
        ORDER BY measured_at DESC
        """
    )
    fun observeRange(from: Long, to: Long): Flow<List<UricRecordEntity>>

    @Query(
        """
        SELECT * FROM uric_record
        WHERE deleted_at IS NULL
        ORDER BY measured_at DESC
        LIMIT 1
        """
    )
    fun observeLatest(): Flow<UricRecordEntity?>

    @Query("SELECT * FROM uric_record WHERE id = :id AND deleted_at IS NULL")
    suspend fun byId(id: Long): UricRecordEntity?

    @Insert
    suspend fun insert(record: UricRecordEntity): Long

    /** 编辑既有记录：只改本行，不动 deleted_at。 */
    @Update
    suspend fun update(record: UricRecordEntity)

    /**
     * 软删除。业务查询一律带 `deleted_at IS NULL`，
     * 过滤责任在仓储层统一封装，不下放给 UI。
     */
    @Query("UPDATE uric_record SET deleted_at = :at, updated_at = :at WHERE id = :id")
    suspend fun softDelete(id: Long, at: Long)

    /**
     * 达标计数。比较符必须是 `<`，与 UricLevelResolver 的判定一致——
     * 若这里用 `<=`，数值恰好等于目标时，"统计卡说达标" 与 "徽章说接近目标"
     * 会互相矛盾（doc/04 §6.1 已定）。
     */
    @Query(
        """
        SELECT COUNT(*) FROM uric_record
        WHERE deleted_at IS NULL AND measured_at BETWEEN :from AND :to
          AND value_umol < :target
        """
    )
    suspend fun countInTarget(from: Long, to: Long, target: Int): Int

    /**
     * 导出用全量。升序——用户拿给医生看时，时间线从早到晚比倒序好读；
     * 且与界面（倒序、最近的在最上面）形成互补而不是重复。
     */
    @Query(
        """
        SELECT * FROM uric_record
        WHERE deleted_at IS NULL
        ORDER BY measured_at ASC, id ASC
        """
    )
    suspend fun allForExport(): List<UricRecordEntity>

    /**
     * 导入去重：同一分钟、同一数值视为同一条。
     *
     * 两个要点：
     * 1. **按分钟而不是毫秒比**。CSV 只存到 `HH:mm`，秒被截断；用精确毫秒比，
     *    导出再导入会被判成"新记录"，同一份备份导两次就变成两份数据（实测踩到）。
     * 2. **带上数值**。同一分钟前后各测一次（左右手）是真实场景，
     *    只比时间会把第二条当重复吞掉。
     */
    @Query(
        """
        SELECT COUNT(*) FROM uric_record
        WHERE deleted_at IS NULL AND measured_at / 60000 = :minute AND value_umol = :valueUmol
        """
    )
    suspend fun countAtMinute(minute: Long, valueUmol: Int): Int
}
