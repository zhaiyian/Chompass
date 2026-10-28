package app.chompass.health.domain.pain

/**
 * Domain model for the site cascade. **No Room entities here**, so it stays pure
 * JVM testable.
 *
 * Not every region has three levels: toes and fingers ask "which one?" first,
 * while wrist / ankle / knee / other go straight from region to joint. An empty
 * [RegionGroup.digits] means exactly that — do not special-case "only one joint,
 * skip a step", because that would route "knee" and "forefoot" through two
 * different code paths despite being on the same level.
 */
data class JointOption(
    val id: String,
    val displayName: String,
    /** Anatomical name, shown in brackets for a doctor's visit; blank for "can't say". */
    val anatomyName: String,
    val sideRequired: Boolean,
) {
    /** No empty brackets when there is no anatomical name. */
    val anatomyOrNull: String?
        get() = anatomyName.takeIf { it.isNotBlank() && it != "—" }
}

data class DigitGroup(
    val id: String,
    val name: String,
    val joints: List<JointOption>,
)

data class RegionGroup(
    val id: String,
    val name: String,
    /** Empty = this region has no "which toe / finger" level. */
    val digits: List<DigitGroup>,
    /** Joints hanging directly off the region (wrist, knee, other, plus forefoot). */
    val joints: List<JointOption>,
) {
    val hasDigits: Boolean get() = digits.isNotEmpty()

    fun jointById(jointId: String): JointOption? =
        joints.firstOrNull { it.id == jointId }
            ?: digits.firstNotNullOfOrNull { d -> d.joints.firstOrNull { it.id == jointId } }
}

/** One row per joint; region and digit are denormalised onto the row (see `pain_joint`). */
data class JointRow(
    val jointId: String,
    val regionId: String,
    val regionName: String,
    val regionOrder: Int,
    val digitId: String?,
    val digitName: String?,
    val digitOrder: Int?,
    val displayName: String,
    val anatomyName: String,
    val sideRequired: Boolean,
    val sortOrder: Int,
)

object PainSiteCatalog {

    /**
     * Flat rows -> cascade tree.
     *
     * Order: region order -> (digit order, then joints) for digit regions, joints
     * only otherwise -> joint order. In a digit region the direct joints
     * (forefoot / dorsum) come after the digits, because they are not "one of
     * the toes".
     */
    fun build(rows: List<JointRow>): List<RegionGroup> {
        if (rows.isEmpty()) return emptyList()
        return rows.groupBy { it.regionId }
            .entries
            .sortedBy { (_, list) -> list.first().regionOrder }
            .map { (regionId, list) ->
                val first = list.first()
                val digitRows = list.filter { it.digitId != null }
                val direct = list.filter { it.digitId == null }
                    .sortedBy { it.sortOrder }
                    .map { it.toOption() }
                val digits = digitRows
                    .groupBy { it.digitId!! }
                    .entries
                    .sortedBy { (_, l) -> l.first().digitOrder ?: Int.MAX_VALUE }
                    .map { (digitId, l) ->
                        DigitGroup(
                            id = digitId,
                            name = l.first().digitName.orEmpty(),
                            joints = l.sortedBy { it.sortOrder }.map { it.toOption() },
                        )
                    }
                RegionGroup(
                    id = regionId,
                    name = first.regionName,
                    digits = digits,
                    joints = direct,
                )
            }
    }

    fun find(catalog: List<RegionGroup>, jointId: String?): Pair<RegionGroup, JointOption>? {
        if (jointId.isNullOrBlank()) return null
        catalog.forEach { region ->
            region.jointById(jointId)?.let { return region to it }
        }
        return null
    }

    /**
     * Site path for chips and lists: `big toe / base` rather than `base`.
     *
     * The digit name is prefixed when there is a digit level — a bare "base" in a
     * screen with three of them does not say which one.
     */
    fun pathOf(catalog: List<RegionGroup>, jointId: String?): String? {
        val (region, joint) = find(catalog, jointId) ?: return null
        val digit = region.digits.firstOrNull { d -> d.joints.any { it.id == joint.id } }
        return if (digit != null) digit.name + joint.displayName else joint.displayName
    }

    private fun JointRow.toOption() = JointOption(
        id = jointId,
        displayName = displayName,
        anatomyName = anatomyName,
        sideRequired = sideRequired,
    )
}
