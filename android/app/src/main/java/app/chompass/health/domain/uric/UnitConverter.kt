package app.chompass.health.domain.uric

import kotlin.math.roundToInt

/**
 * 单位换算。数据库只存 μmol/L，mg/dL 只在输入与展示层出现。
 *
 * 452 μmol/L ↔ 7.6 mg/dL 是单测基准值。
 */
object UnitConverter {

    /** 尿酸分子量换算系数：1 mg/dL = 59.48 μmol/L */
    const val UMOL_PER_MGDL: Double = 59.48

    fun mgdlToUmol(mgdl: Double): Int = (mgdl * UMOL_PER_MGDL).roundToInt()

    /** 保留 1 位小数，仅用于回显。 */
    fun umolToMgdl(umol: Int): Double =
        (umol / UMOL_PER_MGDL * 10).roundToInt() / 10.0
}
