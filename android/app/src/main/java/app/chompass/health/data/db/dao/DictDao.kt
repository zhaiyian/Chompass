package app.chompass.health.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.chompass.health.data.db.entity.DictItemEntity
import app.chompass.health.domain.model.DictType
import kotlinx.coroutines.flow.Flow

@Dao
interface DictDao {

    /**
     * 展示不 join：全表只有几十行，启动时一次读进内存 Map，
     * UI 层查表。逐条 join 会让"一次发作 3 个标签"变成 3 次 join，收益为零。
     */
    @Query("SELECT * FROM dict_item ORDER BY type ASC, sort_order ASC")
    fun observeAll(): Flow<List<DictItemEntity>>

    @Query("SELECT * FROM dict_item WHERE type = :type ORDER BY sort_order ASC")
    suspend fun allOf(type: DictType): List<DictItemEntity>

    @Query(
        """
        SELECT * FROM dict_item
        WHERE type = :type AND enabled = 1
        ORDER BY sort_order ASC
        """
    )
    suspend fun enabledOf(type: DictType): List<DictItemEntity>

    /** 种子幂等：已存在的 (type, code) 不覆盖用户改过的文案。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(items: List<DictItemEntity>)

    /** 停用而非删除：历史记录引用的 code 仍要能解析出文案。 */
    @Query("UPDATE dict_item SET enabled = :enabled, updated_at = :at WHERE type = :type AND code = :code")
    suspend fun setEnabled(type: DictType, code: String, enabled: Boolean, at: Long)
}
