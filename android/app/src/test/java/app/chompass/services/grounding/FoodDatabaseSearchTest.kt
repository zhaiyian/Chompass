package app.chompass.services.grounding

import android.app.Application
import app.chompass.data.PreferencesStore
import app.chompass.models.NutrientSourceKind
import app.chompass.services.ai.FoodAnalysis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * End-to-end offline search over the real bundled USDA + Swiss + China SQLite
 * assets (no network). Pins [FoodDatabaseSearch] merged/ranked behavior after the
 * Codeberg #26 hardening: every offline source resolves through the shared
 * [Mutex] (serialized) and hits land on one normalized score scale. The
 * network source is exercised only through the injected [FoodDatabaseSearch]
 * seam — the real OFF client has its own MockWebServer suite
 * ([OpenFoodFactsSearchTest] et al.).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class FoodDatabaseSearchTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun newSearch(
        prefs: PreferencesStore = PreferencesStore(context),
        offSearch: suspend (String) -> FoodDatabaseSearch.OnlineSearchOutcome = {
            FoodDatabaseSearch.OnlineSearchOutcome(emptyList(), unreachable = false)
        },
    ): FoodDatabaseSearch =
        FoodDatabaseSearch(
            prefs = prefs,
            usda = UsdaFoodIndex(context),
            swiss = SwissFoodIndex(context),
            china = ChinaFoodIndex(context),
            offSearch = offSearch,
        )

    /** An Open Food Facts backend that is simply not answering. */
    private class OffUnreachable : java.io.IOException("open food facts unreachable")

    @Test
    fun search_mergesUsdaAndSwiss_onOneScoreScale() = runBlocking {
        val results = newSearch().search(
            "pork ground",
            sources = setOf(FoodDatabaseSearch.Source.USDA, FoodDatabaseSearch.Source.SWISS),
        )
        assertTrue("expected offline hits for 'pork ground', got ${results.size}", results.isNotEmpty())
        assertTrue(
            results.all {
                it.sourceKind == NutrientSourceKind.USDA || it.sourceKind == NutrientSourceKind.SWISS
            },
        )
        // Ranked descending on the normalized shared 0..1 scale.
        val scores = results.map { it.matchScore }
        assertEquals(scores.sortedDescending(), scores)
        // Every row is displayable with a serving and provenance.
        assertTrue(results.all { it.name.isNotBlank() })
        assertTrue(results.all { it.sourceId.isNotBlank() })
    }

    @Test
    fun search_singleSource_isolation() = runBlocking {
        val results = newSearch().search(
            "pork",
            sources = setOf(FoodDatabaseSearch.Source.SWISS),
        )
        assertTrue("expected Swiss hits for 'pork', got ${results.size}", results.isNotEmpty())
        assertTrue(results.all { it.sourceKind == NutrientSourceKind.SWISS })
    }

    @Test
    fun search_findsChineseFoodsBySubstring() = runBlocking {
        // The China index is the one source whose script FTS5 cannot split on its
        // own: the asset stores unigrams + bigrams and the query leg reads the
        // typed characters the same way, so a partial word still matches the
        // book's longer name.
        val results = newSearch().search(
            "鸡胸",
            sources = setOf(FoodDatabaseSearch.Source.CHINA),
        )
        assertTrue("expected China hits for '鸡胸', got ${results.size}", results.isNotEmpty())
        assertTrue(results.all { it.sourceKind == NutrientSourceKind.CHINA })
        assertTrue(results.all { it.name.contains("鸡") })
        // Ranked on the shared scale like every other source.
        val scores = results.map { it.matchScore }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun search_dropsChinaWhenSwitchedOff() = runBlocking {
        val prefs = PreferencesStore(context)
        val search = newSearch(prefs = prefs)
        val everySource = setOf(
            FoodDatabaseSearch.Source.USDA,
            FoodDatabaseSearch.Source.SWISS,
            FoodDatabaseSearch.Source.CHINA,
        )
        assertTrue(FoodDatabaseSearch.Source.CHINA in search.enabledSources())

        prefs.setFoodSearchChinaEnabled(false)
        try {
            val results = search.search("牛奶", sources = everySource)
            assertTrue(results.none { it.sourceKind == NutrientSourceKind.CHINA })
        } finally {
            prefs.setFoodSearchChinaEnabled(true)
        }
    }

    @Test
    fun toAnalysis_chinaRow_scalesPer100g() = runBlocking {
        val search = newSearch()
        val hit = search.search("牛奶", sources = setOf(FoodDatabaseSearch.Source.CHINA)).first()

        val per100 = search.toAnalysis(hit)
        assertEquals(NutrientSourceKind.CHINA, per100.grounding?.sourceKind)
        assertEquals(100.0, per100.servingSizeGrams ?: -1.0, 0.001)

        val halved = search.toAnalysis(hit.copy(servingGrams = 50.0))
        assertEquals(50.0, halved.servingSizeGrams ?: -1.0, 0.001)
        assertEquals(per100.calories / 2.0, halved.calories.toDouble(), 1.0)
    }

    @Test
    fun search_dropsSourcesTurnedOffInSettings() = runBlocking {
        val prefs = PreferencesStore(context)
        val search = newSearch(prefs = prefs)
        val both = setOf(FoodDatabaseSearch.Source.USDA, FoodDatabaseSearch.Source.SWISS)

        prefs.setFoodSearchUsdaEnabled(false)
        val withoutUsda = search.search("pork", sources = both)
        assertTrue("expected Swiss hits to survive, got ${withoutUsda.size}", withoutUsda.isNotEmpty())
        assertTrue(withoutUsda.none { it.sourceKind == NutrientSourceKind.USDA })

        // Every source off is a valid state: search falls back to the user's own
        // saved foods, which the ranker adds outside this class.
        prefs.setFoodSearchSwissEnabled(false)
        assertTrue(search.search("pork", sources = both).isEmpty())

        prefs.setFoodSearchUsdaEnabled(true)
        prefs.setFoodSearchSwissEnabled(true)
    }

    @Test
    fun search_emptyQuery_returnsEmptyWithoutTouchingIndexes() = runBlocking {
        val results = newSearch().search(
            "   ",
            sources = setOf(FoodDatabaseSearch.Source.USDA, FoodDatabaseSearch.Source.SWISS),
        )
        assertTrue(results.isEmpty())
    }

    @Test
    fun search_dropsOpenFoodFactsTurnedOffInSettings() = runBlocking {
        // Off is a switch away rather than the shipped default, so the gate has
        // to drop the source on its own: a caller asking for every source still
        // gets only the two bundled ones, and the network leg is not attempted.
        var offCalls = 0
        val prefs = PreferencesStore(context)
        val search = newSearch(prefs = prefs, offSearch = {
            offCalls++
            FoodDatabaseSearch.OnlineSearchOutcome(emptyList(), unreachable = false)
        })
        val sources = setOf(
            FoodDatabaseSearch.Source.OPEN_FOOD_FACTS,
            FoodDatabaseSearch.Source.USDA,
            FoodDatabaseSearch.Source.SWISS,
        )

        prefs.setFoodSearchOpenFoodFactsEnabled(false)
        try {
            assertEquals(
                setOf(
                    FoodDatabaseSearch.Source.USDA,
                    FoodDatabaseSearch.Source.SWISS,
                    FoodDatabaseSearch.Source.CHINA,
                ),
                search.enabledSources(),
            )
            val results = search.search("pork ground", sources = sources)
            assertEquals(0, offCalls)
            assertTrue(results.isNotEmpty())
            assertTrue(results.none { it.sourceKind == NutrientSourceKind.OPEN_FOOD_FACTS })
        } finally {
            prefs.setFoodSearchOpenFoodFactsEnabled(true)
        }
    }

    @Test
    fun searchOnline_readsThePreferenceOnEveryCall() = runBlocking {
        // Why the Add Food switch writes this preference *before* it starts the
        // Open Food Facts leg. The gate is re-read per call, so a request fired
        // while the write is still in flight reads the old value and comes back
        // empty — which is what made switching the opt-in off and straight back
        // on return no packaged rows at all.
        val prefs = PreferencesStore(context)
        val hit = DatabaseSearchResult(
            sourceKind = NutrientSourceKind.OPEN_FOOD_FACTS,
            sourceId = "5449000000996",
            name = "Cola",
            caloriesPerServing = 42.0,
            matchScore = 1.0,
        )
        val search = newSearch(prefs = prefs, offSearch = {
            FoodDatabaseSearch.OnlineSearchOutcome(listOf(hit), unreachable = false)
        })

        prefs.setFoodSearchOpenFoodFactsEnabled(false)
        assertTrue(
            "the switch is off, so nothing reaches the fan-out",
            search.searchOnline("cola").results.isEmpty(),
        )

        prefs.setFoodSearchOpenFoodFactsEnabled(true)

        val results = search.searchOnline("cola")
        assertEquals(1, results.results.size)
        assertEquals(NutrientSourceKind.OPEN_FOOD_FACTS, results.results.single().sourceKind)
    }

    @Test
    fun search_deadOpenFoodFacts_stillReturnsOfflineRows() = runBlocking {
        // The whole point of the per-source isolation: Open Food Facts being
        // down is the common case (no signal, captive portal, OFF outage), and
        // it must cost the user nothing but the packaged products. The bundled
        // indexes are on-device, so their rows are unaffected.
        var offCalls = 0
        val prefs = PreferencesStore(context)
        // This test is about someone who has the source on.
        prefs.setFoodSearchOpenFoodFactsEnabled(true)
        val search = newSearch(prefs = prefs, offSearch = { offCalls++; throw OffUnreachable() })
        val results = search.search(
            "pork ground",
            sources = setOf(
                FoodDatabaseSearch.Source.OPEN_FOOD_FACTS,
                FoodDatabaseSearch.Source.USDA,
                FoodDatabaseSearch.Source.SWISS,
            ),
        )
        assertEquals("the OFF leg should still have been attempted", 1, offCalls)
        assertTrue("expected offline hits despite a dead OFF, got ${results.size}", results.isNotEmpty())
        assertTrue(
            results.all {
                it.sourceKind == NutrientSourceKind.USDA || it.sourceKind == NutrientSourceKind.SWISS
            },
        )
        // Still ranked, not just concatenated: a failed leg must not leave the
        // survivors in fan-out order.
        val scores = results.map { it.matchScore }
        assertEquals(scores.sortedDescending(), scores)
    }

    @Test
    fun searchOffline_neverTouchesOpenFoodFacts() = runBlocking {
        // The Add Food sheet's per-keystroke leg. It must stay on-device even
        // with the OFF source on, or typing would reach the network.
        var offCalls = 0
        val prefs = PreferencesStore(context)
        prefs.setFoodSearchOpenFoodFactsEnabled(true)
        val search = newSearch(prefs = prefs, offSearch = { offCalls++; throw OffUnreachable() })
        val results = search.searchOffline("pork ground")
        assertEquals(0, offCalls)
        assertTrue("expected offline hits, got ${results.size}", results.isNotEmpty())
    }

    @Test
    fun searchOnline_deadOpenFoodFacts_isEmptyRatherThanThrowing() = runBlocking {
        // The sheet merges this leg's result into a list that already has rows
        // on screen; a throw here would take those down with it.
        val prefs = PreferencesStore(context)
        prefs.setFoodSearchOpenFoodFactsEnabled(true)
        val search = newSearch(prefs = prefs, offSearch = { throw OffUnreachable() })
        assertTrue(search.searchOnline("pork ground").results.isEmpty())
    }

    @Test
    fun searchOnline_unreachableSeam_propagatesStatus() = runBlocking {
        // The packaged leg is the only place that names OFF's reachability: a
        // seam reporting unreachable (the real service's all-attempts-failed
        // answer) must come through as an empty, unreachable outcome so the
        // sheet can say "unreachable" instead of "nothing matched".
        val prefs = PreferencesStore(context)
        prefs.setFoodSearchOpenFoodFactsEnabled(true)
        val search = newSearch(prefs = prefs, offSearch = {
            FoodDatabaseSearch.OnlineSearchOutcome(emptyList(), unreachable = true)
        })
        val outcome = search.searchOnline("pork ground")
        assertTrue(outcome.results.isEmpty())
        assertTrue(outcome.unreachable)
    }

    @Test
    fun toAnalysis_offBranch_passesSourceIdToLookupByCode() = runBlocking {
        // OFF search hits carry the product code straight from OFF's search API;
        // the branch must not re-validate it through the scanner normalizer (a
        // code that fails the GTIN check digit would throw "could not be read"
        // under lookup()). The extracted branch passes the id through as-is.
        val nonNormalizable = "1234567890123"
        var seen: String? = null
        val analysis = offToAnalysis(nonNormalizable) { code ->
            seen = code
            FoodAnalysis(
                name = "Test Product",
                calories = 100,
                protein = 5.0,
                carbs = 10.0,
                fat = 2.0,
                servingSizeGrams = 100.0,
            )
        }
        assertEquals(nonNormalizable, seen)
        assertEquals("Test Product", analysis.name)
    }

    @Test
    fun withSearchLabeledServing_attachesServingWhenLookupHasNone() {
        val bare = FoodAnalysis(
            name = "Yogurt",
            calories = 80,
            protein = 8.0,
            carbs = 6.0,
            fat = 2.0,
            servingSizeGrams = 100.0,
        )
        val labeled = bare.withSearchLabeledServing(150.0)
        assertEquals("serving", labeled.selectedServingUnit)
        assertEquals(1.0, labeled.selectedServingQuantity!!, 0.001)
        assertEquals(150.0, labeled.servingUnitOptions.single().gramsPerUnit, 0.001)
        assertEquals(bare, bare.withSearchLabeledServing(null))
        assertEquals(bare, bare.withSearchLabeledServing(0.0))
        val already = labeled.withSearchLabeledServing(200.0)
        assertEquals(150.0, already.servingUnitOptions.single().gramsPerUnit, 0.001)
    }
}
