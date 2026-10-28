package app.chompass.health.domain.model

/* ============================================================
 * 参与逻辑判断的枚举：一律留在 Kotlin，不进 dict_item。
 * 判据：代码里有 `when` 分支的，值即行为；字典里的 code 是可改数据。
 * 详见 doc/02 §3.8「边界：什么进字典，什么不进」。
 * ============================================================ */

/** 录入单位。落库永远只存 μmol/L，本枚举只用于回显用户原始输入。 */
enum class UricInputUnit { UMOL_L, MG_DL }

/**
 * 测量方式（血从哪来），code 与字典 `URIC_METHOD` 逐字一致。
 * 字典只负责文案与顺序，行为判断仍走这里。
 */
enum class UricMethod { FINGER, LAB, REPORT, UNKNOWN }

/** 发作侧别。与 `pain_episode.site_code` 正交，code 里不带左右。 */
enum class PainSide { LEFT, RIGHT, BOTH, NA }

/** 与餐关系。 */
enum class MealRelation { BEFORE, AFTER, NONE }

/** 药物状态。停用不删除，保留历史快照的可读性。 */
enum class MedStatus { ACTIVE, STOPPED }

/**
 * 实例的 4 个持久状态。
 * `OVERDUE` / `LATE` / `已服准时` 全部是推导态，绝不落库——
 * 落库后手机时间一改就永久错误（doc/02 §1.4）。
 */
enum class DoseInstanceStatus { PENDING, TAKEN, SKIPPED, MISSED }

/** 实例被取消的原因，仅 `SKIPPED` 时有值。 */
enum class SkipReason { USER_SKIP, SCHEDULE_REMOVED, DOCTOR_STOP }

/** 服药记录的类型。PRN / EXTRA 没有对应实例。 */
enum class DoseLogKind { SCHEDULED, BACKFILL, PRN, EXTRA }

/** 字典类型。只有纯展示选项进字典。 */
enum class DictType { SITE, PAIN_NATURE, TRIGGER, URIC_METHOD }
