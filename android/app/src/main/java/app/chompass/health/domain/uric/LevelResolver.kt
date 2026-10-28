package app.chompass.health.domain.uric

import app.chompass.health.model.UricLevel

/**
 * 尿酸分级。
 *
 * 420 / 480 是**临床阈值**，不随用户的目标值变化；只有"达标"这一档
 * 相对用户目标判定。因此 target 允许配置，但必须 < 420 —— 否则会出现
 * "430 显示达标"这种与临床常识冲突、也让徽章失去意义的情况。
 *
 * 边界一律用严格小于：数值恰好等于阈值时归入更高一档。
 * 这条必须与 `UricDao.countInTarget` 的 `<` 一致，否则统计卡与徽章会互相矛盾。
 */
object LevelResolver {

    const val TARGET_MIN = 200
    const val TARGET_MAX_EXCLUSIVE = 420
    const val NEAR_THRESHOLD = 420
    const val HIGH_THRESHOLD = 480

    /** 新装默认目标值，与 app_setting.uric_target_umol 的种子一致。 */
    const val DEFAULT_TARGET = 360

    fun resolve(umol: Int, target: Int = DEFAULT_TARGET): UricLevel = when {
        umol < target -> UricLevel.BELOW_TARGET
        umol < NEAR_THRESHOLD -> UricLevel.NEAR
        umol < HIGH_THRESHOLD -> UricLevel.HIGH
        else -> UricLevel.VERY_HIGH
    }

    /** 设置项校验：目标值必须落在 [200, 420) 内。 */
    fun isValidTarget(target: Int): Boolean =
        target in TARGET_MIN until TARGET_MAX_EXCLUSIVE
}
