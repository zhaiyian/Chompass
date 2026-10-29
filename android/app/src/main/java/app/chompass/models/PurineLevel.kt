package app.chompass.models

/**
 * Purine band of a food, as the bundled purine table states it per 100 g:
 * under 50 mg is low, 50–150 mg is medium, over 150 mg is high. Gout and
 * hyperuricemia guidance is written against those three bands, so the app never
 * shows a bare number without one.
 */
enum class PurineLevel {
    LOW,
    MEDIUM,
    HIGH;

    companion object {
        const val LOW_MAX_MG = 50.0
        const val MEDIUM_MAX_MG = 150.0

        fun fromMgPer100g(mg: Double): PurineLevel = when {
            mg < LOW_MAX_MG -> LOW
            mg <= MEDIUM_MAX_MG -> MEDIUM
            else -> HIGH
        }
    }
}
