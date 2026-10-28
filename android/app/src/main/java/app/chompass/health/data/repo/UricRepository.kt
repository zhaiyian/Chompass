package app.chompass.health.data.repo

import app.chompass.health.data.db.dao.SettingDao
import app.chompass.health.data.db.dao.UricDao
import app.chompass.health.data.db.entity.SettingKeys
import app.chompass.health.data.db.entity.UricRecordEntity
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.model.UricMethod
import app.chompass.health.domain.uric.LevelResolver
import app.chompass.health.domain.uric.UnitConverter
import app.chompass.health.domain.uric.UricRange
import app.chompass.health.util.TimeUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.roundToInt

/** 录入入参。单位只影响落库前的换算，DB 里永远只存 μmol/L。 */
data class UricInput(
    val value: Double,
    val unit: UricInputUnit,
    val measuredAt: Long,
    val method: UricMethod,
    val fasting: Boolean,
    val note: String? = null,
)

interface UricRepository {
    fun observeRecords(range: UricRange): Flow<List<UricRecordEntity>>
    fun observeLatest(): Flow<UricRecordEntity?>
    fun observeTarget(): Flow<Int>
    fun observeUnit(): Flow<UricInputUnit>
    fun observeLastMethod(): Flow<UricMethod>
    suspend fun add(input: UricInput): Long
    suspend fun byId(id: Long): UricRecordEntity?
    /** 编辑已有记录。id 不存在返回 false。 */
    suspend fun update(id: Long, input: UricInput): Boolean
    suspend fun softDelete(id: Long)
    /** 保存成功后才写，保证"上次使用"来自真实行为而不是点选 */
    suspend fun rememberMethod(method: UricMethod)

    /**
     * 修改尿酸目标值。
     *
     * 这是全 App 里唯一会影响"偏高/接近目标/达标"判定口径的旋钮：
     * 目标值一改，A1 的徽章、目标进度条、"还差多少"、达标次数统计都要跟着变，
     * 所以这里只写设置表，不做任何缓存——`observeTarget()` 是唯一读取入口。
     *
     * **必须校验 200 ≤ target < 420**（`LevelResolver.isValidTarget`）：
     * 420 是临床诊断阈值，把它设成目标会出现「430 显示达标」——
     * 与医学常识冲突，也让徽章彻底失去意义。校验放在仓储层而不是只放 UI，
     * 是因为 UI 有多处入口（现在只有关于页，将来可能有引导页）。
     *
     * @return 值合法并写入返回 true
     */
    suspend fun setTarget(valueUmol: Int): Boolean
}

class UricRepositoryImpl(
    private val dao: UricDao,
    private val settingDao: SettingDao,
    private val clock: () -> Long,
) : UricRepository {

    /**
     * 区间取数。首尾都是闭区间，且**右端不再固定为 Long.MAX_VALUE**——
     * 翻到历史区间时，后面那些记录不该混进当月统计。
     */
    override fun observeRecords(range: UricRange): Flow<List<UricRecordEntity>> =
        dao.observeRange(range.startMillis(), range.endMillis())

    override fun observeLatest(): Flow<UricRecordEntity?> = dao.observeLatest()

    override fun observeTarget(): Flow<Int> =
        settingDao.observeAll().map { rows ->
            rows.firstOrNull { it.key == SettingKeys.URIC_TARGET_UMOL }
                ?.value?.toIntOrNull()
                ?: SettingKeys.defaults.getValue(SettingKeys.URIC_TARGET_UMOL).toInt()
        }

    override fun observeUnit(): Flow<UricInputUnit> =
        settingDao.observeAll().map { rows ->
            val raw = rows.firstOrNull { it.key == SettingKeys.URIC_UNIT }?.value
            UricInputUnit.entries.firstOrNull { it.name == raw } ?: UricInputUnit.UMOL_L
        }

    override fun observeLastMethod(): Flow<UricMethod> =
        settingDao.observeAll().map { rows ->
            val raw = rows.firstOrNull { it.key == SettingKeys.URIC_LAST_METHOD }?.value
            UricMethod.entries.firstOrNull { it.name == raw } ?: UricMethod.FINGER
        }

    override suspend fun add(input: UricInput): Long {
        val now = clock()
        val umol = when (input.unit) {
            UricInputUnit.UMOL_L -> input.value.roundToInt()
            UricInputUnit.MG_DL -> UnitConverter.mgdlToUmol(input.value)
        }
        val entity = UricRecordEntity(
            valueUmol = umol,
            inputValue = input.value,
            inputUnit = input.unit,
            measuredAt = input.measuredAt,
            measuredDate = TimeUtils.toDateString(input.measuredAt),
            measureMethod = input.method,
            fasting = input.fasting,
            note = input.note?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        return dao.insert(entity)
    }

    override suspend fun softDelete(id: Long) = dao.softDelete(id, clock())

    override suspend fun byId(id: Long): UricRecordEntity? = dao.byId(id)

    override suspend fun update(id: Long, input: UricInput): Boolean {
        val existing = dao.byId(id) ?: return false
        val umol = when (input.unit) {
            UricInputUnit.UMOL_L -> input.value.roundToInt()
            UricInputUnit.MG_DL -> UnitConverter.mgdlToUmol(input.value)
        }
        dao.update(
            existing.copy(
                valueUmol = umol,
                inputValue = input.value,
                inputUnit = input.unit,
                measuredAt = input.measuredAt,
                measuredDate = TimeUtils.toDateString(input.measuredAt),
                measureMethod = input.method,
                fasting = input.fasting,
                note = input.note?.takeIf { it.isNotBlank() },
                updatedAt = clock(),
            )
        )
        return true
    }

    override suspend fun setTarget(valueUmol: Int): Boolean {
        if (!LevelResolver.isValidTarget(valueUmol)) return false
        settingDao.put(
            app.chompass.health.data.db.entity.AppSettingEntity(
                key = SettingKeys.URIC_TARGET_UMOL,
                value = valueUmol.toString(),
                updatedAt = clock(),
            )
        )
        return true
    }

    override suspend fun rememberMethod(method: UricMethod) {
        settingDao.put(
            app.chompass.health.data.db.entity.AppSettingEntity(
                key = SettingKeys.URIC_LAST_METHOD,
                value = method.name,
                updatedAt = clock(),
            )
        )
    }
}
