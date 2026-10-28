package app.chompass.health.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.chompass.health.data.db.entity.DoseInstanceEntity
import app.chompass.health.data.db.entity.DoseLogEntity
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.SkipReason
import kotlinx.coroutines.flow.Flow

@Dao
interface DoseDao {

    /**
     * 同一天的计划按时间升序。
     * 必须带 `id` 兜底排序：多个药可能共用同一个时刻（都 08:00），
     * 只按 plan_at 排会让行序随更新抖动，用户会以为列表自己重排了。
     */
    @Query("SELECT * FROM dose_instance WHERE plan_date = :date ORDER BY plan_at ASC, id ASC")
    fun observeByDate(date: String): Flow<List<DoseInstanceEntity>>

    @Query("SELECT * FROM dose_instance WHERE plan_date = :date ORDER BY plan_at ASC, id ASC")
    suspend fun byDate(date: String): List<DoseInstanceEntity>

    /** 周视图 / 区间统计用 */
    @Query(
        """
        SELECT * FROM dose_instance
        WHERE plan_date BETWEEN :from AND :to
        ORDER BY plan_date ASC, plan_at ASC, id ASC
        """
    )
    fun observeBetween(from: String, to: String): Flow<List<DoseInstanceEntity>>

    @Query("SELECT * FROM dose_instance WHERE id = :id")
    suspend fun instanceById(id: Long): DoseInstanceEntity?

    /** 某药当天仍是待服的实例，改时间点时用来重算（`04 §2.3`）。 */
    @Query(
        """
        SELECT * FROM dose_instance
        WHERE medication_id = :medicationId AND plan_date = :date AND status = 'PENDING'
        ORDER BY plan_at ASC, id ASC
        """
    )
    suspend fun pendingOf(medicationId: Long, date: String): List<DoseInstanceEntity>

    /**
     * 断开实例与时间点行的引用。
     *
     * `dose_instance.schedule_id` 是指向 `med_schedule` 的外键，而改时间点的做法是
     * "删掉旧时间点行、插入新的"。不先断开引用，DELETE 会直接撞
     * `FOREIGN KEY constraint failed (787)` —— 表现是"改完时间点保存不了，
     * 而且错误原文是一句英文 SQLite 报错"。实测踩到过。
     *
     * 断开而不是级联删除：实例是有价值的业务记录（可能已有服药日志），
     * 不能因为改了时间点就连实例一起消失。
     */
    @Query("UPDATE dose_instance SET schedule_id = NULL WHERE medication_id = :medicationId")
    suspend fun detachSchedule(medicationId: Long)

    /**
     * 只删"尚未发生、无记录引用"的实例：改时间点时被移除的那个点。
     * 已经到点的必须走 [transit] 置 SKIPPED，否则会留下历史空白。
     */
    @Query("DELETE FROM dose_instance WHERE id = :id AND status = 'PENDING'")
    suspend fun deletePendingInstance(id: Long): Int

    /** 停用药物：今天及以后的待服实例统一置「已取消（医嘱停用）」。 */
    @Query(
        """
        UPDATE dose_instance
        SET status = 'SKIPPED', skip_reason = :reason, updated_at = :at
        WHERE medication_id = :medicationId AND status = 'PENDING' AND plan_date >= :fromDate
        """
    )
    suspend fun cancelPendingFrom(
        medicationId: Long,
        fromDate: String,
        reason: SkipReason,
        at: Long,
    ): Int

    @Query(
        """
        SELECT * FROM dose_instance
        WHERE plan_date = :date AND medication_id = :medicationId AND plan_at = :planAt
        LIMIT 1
        """
    )
    suspend fun findInstance(medicationId: Long, planAt: Long, date: String): DoseInstanceEntity?

    /**
     * 幂等生成：靠 `(medication_id, plan_at)` 唯一索引 + IGNORE。
     * 因此 ensureInstances 可以在每次 onResume 里无脑调用。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertInstances(instances: List<DoseInstanceEntity>): List<Long>

    /**
     * 带前置状态校验的迁移，避免并发双击把终态覆盖。
     * 返回 0 表示当前状态不是 expected，调用方据此提示"已被处理"。
     */
    @Query(
        """
        UPDATE dose_instance
        SET status = :next, skip_reason = :reason, updated_at = :at
        WHERE id = :id AND status = :expected
        """
    )
    suspend fun transit(
        id: Long,
        expected: DoseInstanceStatus,
        next: DoseInstanceStatus,
        reason: SkipReason?,
        at: Long,
    ): Int

    /** 日结兜底：昨天及更早仍 PENDING 的实例统一置 MISSED。 */
    @Query(
        """
        UPDATE dose_instance
        SET status = 'MISSED', updated_at = :at
        WHERE status = 'PENDING' AND plan_date < :today
        """
    )
    suspend fun rolloverPending(today: String, at: Long): Int

    @Insert
    suspend fun insertLog(log: DoseLogEntity): Long

    @Query(
        """
        SELECT * FROM dose_log
        WHERE revoked_at IS NULL AND actual_at BETWEEN :from AND :to
        ORDER BY actual_at DESC
        """
    )
    fun observeLogs(from: Long, to: Long): Flow<List<DoseLogEntity>>

    @Query(
        """
        SELECT * FROM dose_log
        WHERE instance_id = :instanceId AND revoked_at IS NULL
        LIMIT 1
        """
    )
    suspend fun activeLogFor(instanceId: Long): DoseLogEntity?

    @Query("UPDATE dose_log SET revoked_at = :at WHERE id = :id AND revoked_at IS NULL")
    suspend fun revokeLog(id: Long, at: Long)

    @Query("SELECT * FROM dose_log WHERE id = :id")
    suspend fun logById(id: Long): DoseLogEntity?

    /**
     * 改正一条记录的实际时刻与服用量。
     *
     * **允许改的就这两项**，都是"用户会记错、改完不影响历史结论"的字段：
     *
     * - `actual_at`：几点吃的。
     * - `intake_units`：吃了几片 / 几粒。改了要同步把库存差额补回去（调用方负责），
     *   不补的话"记成 2 片改回 1 片"会让库存永久少扣 1 片。
     *   每片含量（`strength_*`）是药本身的属性，不在这里改。
     *
     * `deviation_minutes` 必须一起重算：它是记录当时的快照，
     * 只改 `actual_at` 会让导出的 CSV 里"实际 09:30 / 延迟 -180 分钟"自相矛盾。
     */
    @Query(
        """
        UPDATE dose_log
        SET actual_at = :actualAt,
            actual_date = :actualDate,
            deviation_minutes = :deviationMinutes,
            intake_units = :intakeUnits
        WHERE id = :id AND revoked_at IS NULL
        """
    )
    suspend fun updateLog(
        id: Long,
        actualAt: Long,
        actualDate: String,
        deviationMinutes: Int?,
        intakeUnits: Double,
    ): Int

    /**
     * 导出用全量服药记录。
     * 排除已撤销（`04 §10`）——被撤销的那条在用户视角里等于"没吃过"，
     * 导出去只会让医生多问一轮"这条到底吃没吃"。
     */
    @Query(
        """
        SELECT * FROM dose_log
        WHERE revoked_at IS NULL
        ORDER BY actual_at ASC, id ASC
        """
    )
    suspend fun allLogsForExport(): List<DoseLogEntity>

    /**
     * 导入去重：同一分钟、同一味药视为同一次服用。
     * 按分钟而非毫秒：CSV 只存到 `HH:mm`，秒会被截断。
     */
    @Query(
        """
        SELECT COUNT(*) FROM dose_log
        WHERE revoked_at IS NULL AND actual_at / 60000 = :minute AND drug_name = :drugName
        """
    )
    suspend fun countLogAtMinute(minute: Long, drugName: String): Int

    /**
     * 导入时按"计划时刻 + 药名"找回本机的实例。
     *
     * 导出件里没有 `dose_instance` 表（那是本机自增 id，跨设备没意义），
     * 但日志带着 `plan_at`。能在本机找回对应实例的话，就把日志绑回去、
     * 把实例置 TAKEN —— 否则同一天会同时出现"逾期"和"额外服用"，
     * 看起来像病人多吃了一顿。
     *
     * 按分钟匹配，理由同导入去重：CSV 的时间只到 `HH:mm`。
     */
    @Query(
        """
        SELECT * FROM dose_instance
        WHERE plan_date = :date AND drug_name = :drugName AND plan_at / 60000 = :minute
        LIMIT 1
        """
    )
    suspend fun findViewByIdByPlan(date: String, drugName: String, minute: Long): DoseInstanceEntity?
}
