package app.chompass.health.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.chompass.health.data.db.entity.AppSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingDao {

    @Query("SELECT * FROM app_setting")
    fun observeAll(): Flow<List<AppSettingEntity>>

    @Query("SELECT value FROM app_setting WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    /** 默认值只在缺失时写入，不覆盖用户改过的值。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun seedDefaults(defaults: List<AppSettingEntity>)
}
