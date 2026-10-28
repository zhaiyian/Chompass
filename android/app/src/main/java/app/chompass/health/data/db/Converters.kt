package app.chompass.health.data.db

import androidx.room.TypeConverter
import app.chompass.health.domain.model.DictType
import app.chompass.health.domain.model.DoseInstanceStatus
import app.chompass.health.domain.model.DoseLogKind
import app.chompass.health.domain.model.MealRelation
import app.chompass.health.domain.model.MedStatus
import app.chompass.health.domain.model.PainSide
import app.chompass.health.domain.model.SkipReason
import app.chompass.health.domain.model.UricInputUnit
import app.chompass.health.domain.model.UricMethod
import app.chompass.health.model.MedCategory

/**
 * 枚举一律用**字符串**存储，不用序号。
 * 序号在枚举顺序调整后会静默错位，是历史项目里最贵的一类 bug。
 *
 * 解析失败时回落到安全默认值并保持可读，而不是抛异常让整条查询失败——
 * 数据库里的旧值可能来自更早的版本，用户不该因此看不到自己的记录。
 */
private inline fun <reified T : Enum<T>> enumOr(name: String?, fallback: T): T =
    if (name == null) fallback else enumValues<T>().firstOrNull { it.name == name } ?: fallback

class Converters {

    // ── 尿酸 ────────────────────────────────────────────────
    @TypeConverter
    fun fromUricInputUnit(v: UricInputUnit): String = v.name

    @TypeConverter
    fun toUricInputUnit(v: String?): UricInputUnit =
        enumOr(v, UricInputUnit.UMOL_L)

    @TypeConverter
    fun fromUricMethod(v: UricMethod): String = v.name

    @TypeConverter
    fun toUricMethod(v: String?): UricMethod = enumOr(v, UricMethod.UNKNOWN)

    // ── 疼痛 ────────────────────────────────────────────────
    @TypeConverter
    fun fromPainSide(v: PainSide): String = v.name

    @TypeConverter
    fun toPainSide(v: String?): PainSide = enumOr(v, PainSide.NA)

    /** 性质 / 诱因的 CSV。元素内不允许含逗号。 */
    @TypeConverter
    fun fromStringList(v: List<String>?): String? =
        v?.map { it.trim() }?.filter { it.isNotEmpty() }?.joinToString(",")?.ifEmpty { null }

    @TypeConverter
    fun toStringList(v: String?): List<String>? =
        v?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.ifEmpty { null }

    // ── 用药 ────────────────────────────────────────────────
    @TypeConverter
    fun fromMedCategory(v: MedCategory): String = v.name

    @TypeConverter
    fun toMedCategory(v: String?): MedCategory = enumOr(v, MedCategory.OTHER)

    @TypeConverter
    fun fromMedStatus(v: MedStatus): String = v.name

    @TypeConverter
    fun toMedStatus(v: String?): MedStatus = enumOr(v, MedStatus.ACTIVE)

    @TypeConverter
    fun fromMealRelation(v: MealRelation): String = v.name

    @TypeConverter
    fun toMealRelation(v: String?): MealRelation = enumOr(v, MealRelation.NONE)

    @TypeConverter
    fun fromDoseInstanceStatus(v: DoseInstanceStatus): String = v.name

    @TypeConverter
    fun toDoseInstanceStatus(v: String?): DoseInstanceStatus =
        enumOr(v, DoseInstanceStatus.PENDING)

    @TypeConverter
    fun fromSkipReason(v: SkipReason?): String? = v?.name

    @TypeConverter
    fun toSkipReason(v: String?): SkipReason? =
        if (v == null) null else enumValues<SkipReason>().firstOrNull { it.name == v }

    @TypeConverter
    fun fromDoseLogKind(v: DoseLogKind): String = v.name

    @TypeConverter
    fun toDoseLogKind(v: String?): DoseLogKind = enumOr(v, DoseLogKind.SCHEDULED)

    // ── 字典 ────────────────────────────────────────────────
    @TypeConverter
    fun fromDictType(v: DictType): String = v.name

    @TypeConverter
    fun toDictType(v: String?): DictType = enumOr(v, DictType.SITE)
}
