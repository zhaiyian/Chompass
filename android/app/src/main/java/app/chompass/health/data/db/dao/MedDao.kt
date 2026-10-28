package app.chompass.health.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import app.chompass.health.data.db.entity.MedScheduleEntity
import app.chompass.health.data.db.entity.MedicationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MedDao {

    @Query("SELECT * FROM medication WHERE status = 'ACTIVE' ORDER BY id ASC")
    fun observeActive(): Flow<List<MedicationEntity>>

    /**
     * 「我的药物」列表：启用中的排前面，停用的留在后面。
     * 停用的不隐藏——用户需要能看到"这药我停了"，而不是以为数据丢了。
     */
    @Query(
        """
        SELECT * FROM medication
        ORDER BY CASE status WHEN 'ACTIVE' THEN 0 ELSE 1 END, id ASC
        """
    )
    fun observeAll(): Flow<List<MedicationEntity>>

    /** 有固定计划、需要生成实例的药（is_prn = 0）。 */
    @Query("SELECT * FROM medication WHERE status = 'ACTIVE' AND is_prn = 0 ORDER BY id ASC")
    fun observeScheduled(): Flow<List<MedicationEntity>>

    /** 生成实例时的一次性读取，避免在 suspend 流程里引爆一个 Flow。 */
    @Query("SELECT * FROM medication WHERE status = 'ACTIVE' AND is_prn = 0 ORDER BY id ASC")
    suspend fun scheduledOnce(): List<MedicationEntity>

    /** 按需备用：不生成实例，C1 用「按需备用」卡片承载。 */
    @Query("SELECT * FROM medication WHERE status = 'ACTIVE' AND is_prn = 1 ORDER BY id ASC")
    fun observePrn(): Flow<List<MedicationEntity>>

    @Query("SELECT * FROM medication WHERE id = :id")
    fun observeById(id: Long): Flow<MedicationEntity?>

    @Query("SELECT * FROM medication WHERE id = :id")
    suspend fun byId(id: Long): MedicationEntity?

    @Query(
        """
        SELECT * FROM med_schedule
        WHERE medication_id = :medicationId
        ORDER BY ordinal ASC, time_of_day ASC
        """
    )
    suspend fun schedulesOf(medicationId: Long): List<MedScheduleEntity>

    @Query("SELECT * FROM med_schedule ORDER BY medication_id, ordinal")
    fun observeAllSchedules(): Flow<List<MedScheduleEntity>>

    @Insert
    suspend fun insert(medication: MedicationEntity): Long

    @Update
    suspend fun update(medication: MedicationEntity)

    @Insert
    suspend fun insertSchedules(schedules: List<MedScheduleEntity>)

    @Query("DELETE FROM med_schedule WHERE medication_id = :medicationId")
    suspend fun clearSchedules(medicationId: Long)

    @Query(
        """
        UPDATE medication
        SET status = 'STOPPED', stopped_on = :stoppedOn, updated_at = :at
        WHERE id = :id
        """
    )
    suspend fun stop(id: Long, stoppedOn: String, at: Long)

    /**
     * 扣减库存。只在用户真的点「记录」时调用，不按日期自动推算——
     * 系统自己算消耗会让库存静默漂移。
     */
    @Query(
        """
        UPDATE medication
        SET stock_units = MAX(0, stock_units - :delta), updated_at = :at
        WHERE id = :id AND stock_units IS NOT NULL
        """
    )
    suspend fun decreaseStock(id: Long, delta: Double, at: Long)

    /** 撤销记录时把库存加回去，与 decreaseStock 严格成对。 */
    @Query(
        """
        UPDATE medication
        SET stock_units = stock_units + :delta, updated_at = :at
        WHERE id = :id AND stock_units IS NOT NULL
        """
    )
    suspend fun increaseStock(id: Long, delta: Double, at: Long)
}
