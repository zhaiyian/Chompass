package app.chompass.services.grounding

import android.app.Application
import app.chompass.models.PurineLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Name matching over the real bundled purine asset (`assets/purine/`), built from
 * the maintainer's 嘌呤表.xlsx by `scripts/build_purine_index.py`.
 *
 * The badge is an annotation, not a nutrient: it rides on a name match, so these
 * tests are mostly about *refusing* to match. A wrong "low purine" on a beer
 * braise is worse than showing nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class PurineFoodIndexTest {
    private val context = RuntimeEnvironment.getApplication()
    private val index = PurineFoodIndex(context)

    @Test
    fun assetLoads_withItsOwnDatasetVersion() {
        assertEquals("purine-table-2026-10", index.version())
        assertEquals(240, index.foodCount())
    }

    @Test
    fun findFor_exactName_returnsTheSheetsBand() {
        val chicken = requireNotNull(index.findFor("鸡胸肉(熟)"))
        assertEquals(153.435, chicken.purineMgPer100g, 0.001)
        assertEquals(PurineLevel.HIGH, chicken.level)

        val rice = requireNotNull(index.findFor("大米饭"))
        assertEquals(PurineLevel.LOW, rice.level)

        val soy = requireNotNull(index.findFor("生抽"))
        assertEquals(PurineLevel.MEDIUM, soy.level)
    }

    @Test
    fun findFor_ignoresPreparationAside() {
        // The sheet writes "鸡胸肉(熟)"; an entry usually says "鸡胸肉".
        val hit = requireNotNull(index.findFor("鸡胸肉"))
        assertEquals("鸡胸肉(熟)", hit.name)
        assertEquals(PurineLevel.HIGH, hit.level)
    }

    @Test
    fun findFor_specificDishContainingKnownFood_findsThatFood() {
        val hit = requireNotNull(index.findFor("凉拌牛肉（少油）"))
        assertEquals("凉拌牛肉", hit.name)
        assertEquals(PurineLevel.MEDIUM, hit.level)
    }

    @Test
    fun findFor_shorterNameInsideLongerTableName_matchesWithinSlack() {
        // "鸡胸" + one character ("肉") is still the same food…
        val hit = requireNotNull(index.findFor("鸡胸"))
        assertEquals("鸡胸肉(熟)", hit.name)

        // …but "牛奶" only reaches 纯牛奶 because the table name is barely longer,
        // and "鸡蛋" must not inherit a dumpling's band.
        val milk = requireNotNull(index.findFor("牛奶"))
        assertEquals("纯牛奶", milk.name)
        assertEquals(PurineLevel.LOW, milk.level)
        assertNull(index.findFor("鸡蛋"))
    }

    @Test
    fun findFor_rawAndCookedVariants_prefersTheStricterValue() {
        // 羊腿肉(生) 103.5 / 羊腿肉(熟) 70.8: cooking lowers measured purine, so
        // the unqualified name reports the higher value.
        val hit = requireNotNull(index.findFor("羊腿肉"))
        assertEquals(103.509, hit.purineMgPer100g, 0.001)
        assertEquals(PurineLevel.MEDIUM, hit.level)
    }

    @Test
    fun findFor_unknownOrTooVague_returnsNull() {
        assertNull(index.findFor(""))
        assertNull(index.findFor("   "))
        assertNull(index.findFor("鸡蛋")) // no plain-egg row; dumplings must not leak in
        assertNull(index.findFor("蛋白粉拿铁特调")) // longest known food wins only on containment
        assertNull(index.findFor("某某牌能量胶"))
        assertNull(index.findFor("虾")) // single glyph is too vague to badge
    }

    @Test
    fun findFor_isStableAcrossCalls() {
        val first = index.findFor("百事可乐")
        val second = index.findFor("百事可乐")
        assertSame("lookups are cached, so Compose can call this per frame", first, second)
        assertEquals(PurineLevel.LOW, first?.level)
    }

    @Test
    fun levelThresholds_matchTheSheetsConvention() {
        assertEquals(PurineLevel.LOW, PurineLevel.fromMgPer100g(0.0))
        assertEquals(PurineLevel.LOW, PurineLevel.fromMgPer100g(49.999))
        assertEquals(PurineLevel.MEDIUM, PurineLevel.fromMgPer100g(50.0))
        assertEquals(PurineLevel.MEDIUM, PurineLevel.fromMgPer100g(150.0))
        assertEquals(PurineLevel.HIGH, PurineLevel.fromMgPer100g(150.001))
    }

    @Test
    fun flattenAndNormalize_dropAsidesAndPunctuation() {
        assertEquals("鸡胸肉", PurineFoodIndex.normalize("鸡胸肉（熟）"))
        assertEquals("rio强爽0嘌呤", PurineFoodIndex.normalize("R I O强爽0嘌呤"))
        assertEquals("纯牛奶250ml", PurineFoodIndex.flatten("纯牛奶 250ml"))
    }
}
