package com.openminis.app.intelligentui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** JVM tests for the bounded pure logic behind the native interactive tools. */
class IntelligentUiToolLogicTest {

    // ------------------------------------------------------------ units

    @Test fun converterExposesTheExactUnitCatalog() {
        assertEquals(listOf("mm", "cm", "m", "km", "in", "ft", "yd", "mi"), UiUnits.symbols(UiDimension.LENGTH))
        assertEquals(listOf("mg", "g", "kg", "oz", "lb", "t"), UiUnits.symbols(UiDimension.MASS))
        assertEquals(listOf("C", "F", "K"), UiUnits.symbols(UiDimension.TEMPERATURE))
        assertEquals(listOf("B", "KB", "MB", "GB", "TB", "KiB", "MiB", "GiB", "TiB"), UiUnits.symbols(UiDimension.STORAGE))
        assertEquals(4, UiDimension.entries.size)
    }

    @Test fun lengthAndMassConversionsAreExact() {
        assertEquals(100.0, UiUnits.convert(1.0, "m", "cm", UiDimension.LENGTH), 1e-9)
        assertEquals(1609.344, UiUnits.convert(1.0, "mi", "m", UiDimension.LENGTH), 1e-9)
        assertEquals(0.0254, UiUnits.convert(1.0, "in", "m", UiDimension.LENGTH), 1e-12)
        assertEquals(2.2046226218487757, UiUnits.convert(1.0, "kg", "lb", UiDimension.MASS), 1e-9)
        assertEquals(1000.0, UiUnits.convert(1.0, "t", "kg", UiDimension.MASS), 1e-9)
    }

    @Test fun temperatureUsesAffineConversion() {
        assertEquals(32.0, UiUnits.convert(0.0, "C", "F", UiDimension.TEMPERATURE), 1e-9)
        assertEquals(212.0, UiUnits.convert(100.0, "C", "F", UiDimension.TEMPERATURE), 1e-9)
        assertEquals(-40.0, UiUnits.convert(-40.0, "C", "F"), 1e-9)
        assertEquals(0.0, UiUnits.convert(273.15, "K", "C", UiDimension.TEMPERATURE), 1e-9)
        assertEquals(273.15, UiUnits.convert(32.0, "F", "K", UiDimension.TEMPERATURE), 1e-9)
    }

    @Test fun storageDistinguishesDecimalAndBinaryUnits() {
        assertEquals(1000.0, UiUnits.convert(1.0, "GB", "MB", UiDimension.STORAGE), 1e-9)
        assertEquals(1024.0, UiUnits.convert(1.0, "GiB", "MiB", UiDimension.STORAGE), 1e-9)
        assertEquals(1.0, UiUnits.convert(1024.0, "GiB", "TiB", UiDimension.STORAGE), 1e-9)
        assertEquals(1e9, UiUnits.convert(1.0, "GB", "B", UiDimension.STORAGE), 1e-3)
        assertEquals(1e9 / 1024.0, UiUnits.convert(1.0, "GB", "KiB", UiDimension.STORAGE), 1e-6)
    }

    @Test fun aliasesResolveToCanonicalSymbols() {
        assertEquals("m", UiUnits.resolve(" meters ", UiDimension.LENGTH)?.symbol)
        assertEquals("kg", UiUnits.resolve("KILOGRAM", UiDimension.MASS)?.symbol)
        assertEquals("C", UiUnits.resolve("celsius", UiDimension.TEMPERATURE)?.symbol)
        assertEquals("F", UiUnits.resolve("\u00B0f", UiDimension.TEMPERATURE)?.symbol)
        assertEquals("MB", UiUnits.resolve("mb", UiDimension.STORAGE)?.symbol)
        assertNull(UiUnits.resolve("parsec", UiDimension.LENGTH))
        assertNull(UiUnits.resolve("", UiDimension.LENGTH))
    }

    @Test fun mismatchedDimensionsFailInsteadOfReturningNumbers() {
        val mismatch = runCatching { UiUnits.convert(1.0, "m", "kg") }.exceptionOrNull()
        assertTrue(mismatch is IllegalArgumentException)
        assertTrue(mismatch!!.message!!.contains("dimension mismatch"))

        val wrongDimension = runCatching { UiUnits.convert(1.0, "m", "cm", UiDimension.MASS) }.exceptionOrNull()
        assertTrue(wrongDimension is IllegalArgumentException)
        assertTrue(wrongDimension!!.message!!.contains("unknown unit"))

        assertNull(UiUnits.convertOrNull(1.0, "m", "kg"))
        assertNull(UiUnits.convertOrNull(1.0, "lightyear", "m"))
    }

    @Test fun converterRejectsNonFiniteAndOversizedValues() {
        assertTrue(runCatching { UiUnits.convert(Double.NaN, "m", "cm") }.isFailure)
        assertTrue(runCatching { UiUnits.convert(Double.POSITIVE_INFINITY, "m", "cm") }.isFailure)
        assertTrue(runCatching { UiUnits.convert(1e13, "m", "cm") }.isFailure)
        assertTrue(runCatching { UiUnits.convert(1.0, "m", "") }.isFailure)
    }

    @Test fun numberFormattingIsLocaleIndependentAndBounded() {
        assertEquals("5.00", UiFormat.money(5.0))
        assertEquals("\u2014", UiFormat.money(Double.NaN))
        assertEquals("1", UiFormat.number(1.0))
        assertEquals("33.8", UiFormat.number(33.8))
        assertEquals("0", UiFormat.number(-0.0))
        assertEquals("50%", UiFormat.percent(50.0))
        assertEquals("33.3%", UiFormat.percent(100.0 / 3.0))
        assertEquals("\u2014", UiFormat.number(Double.POSITIVE_INFINITY))
    }

    @Test fun parsingAcceptsCommonInputsAndRejectsInvalidOnes() {
        assertEquals(1200.0, UiFormat.parse("\u00A5 1,200")!!, 1e-9)
        assertEquals(1500.0, UiFormat.parse("1.5k")!!, 1e-9)
        assertEquals(30000.0, UiFormat.parse("3\u4E07")!!, 1e-9)
        assertEquals(-12.5, UiFormat.parse("-12.5")!!, 1e-9)
        assertEquals(0.0, UiFormat.parse("0")!!, 1e-9)
        assertNull(UiFormat.parse(""))
        assertNull(UiFormat.parse("abc"))
        assertNull(UiFormat.parse("1e13"))
        assertNull(UiFormat.parse("Infinity"))
        assertNull(UiFormat.parse("3..2"))
    }

    // ------------------------------------------------------------ budget

    @Test fun budgetComputesBalanceAndShares() {
        val summary = UiBudget.summarize(
            1000.0,
            listOf(UiBudgetItem("Rent", 300.0), UiBudgetItem("Food", 200.0)),
        )
        assertEquals(500.0, summary.totalExpense, 1e-9)
        assertEquals(500.0, summary.balance, 1e-9)
        assertEquals(50.0, summary.balancePercent, 1e-9)
        assertEquals(60.0, summary.itemPercents[0], 1e-9)
        assertEquals(40.0, summary.itemPercents[1], 1e-9)
        assertFalse(summary.overBudget)
        assertEquals(2, summary.count)
    }

    @Test fun budgetHandlesOverBudgetZeroIncomeAndInvalidValues() {
        val over = UiBudget.summarize(100.0, listOf(UiBudgetItem("A", 150.0)))
        assertEquals(-50.0, over.balance, 1e-9)
        assertEquals(-50.0, over.balancePercent, 1e-9)
        assertTrue(over.overBudget)

        val zeroIncome = UiBudget.summarize(0.0, listOf(UiBudgetItem("A", 100.0)))
        assertEquals(0.0, zeroIncome.balancePercent, 1e-9)
        assertEquals(100.0, zeroIncome.itemPercents[0], 1e-9)

        val invalid = UiBudget.summarize(
            Double.NaN,
            listOf(UiBudgetItem("NaN", Double.NaN), UiBudgetItem("Inf", Double.POSITIVE_INFINITY), UiBudgetItem("Neg", -8.0)),
        )
        assertEquals(0.0, invalid.income, 1e-9)
        assertEquals(0.0, invalid.totalExpense, 1e-9)
        assertEquals(listOf(0.0, 0.0, 0.0), invalid.itemPercents)
        assertEquals(0.0, invalid.balance, 1e-9)
    }

    @Test fun budgetParsingRejectsNegativeAndHugeAmounts() {
        assertEquals(1000.0, UiBudget.parseAmount("1,000")!!, 1e-9)
        assertNull(UiBudget.parseAmount("-1"))
        assertNull(UiBudget.parseAmount("1e13"))
        assertNull(UiBudget.parseAmount("abc"))
        assertEquals(0.0, UiBudget.sanitizeAmount(Double.NEGATIVE_INFINITY), 1e-9)
    }

    @Test fun budgetCapsItemCount() {
        val items = (1..80).map { UiBudgetItem("i$it", 1.0) }
        val summary = UiBudget.summarize(1000.0, items)
        assertEquals(UiBudget.MAX_ITEMS, summary.count)
        assertEquals(UiBudget.MAX_ITEMS.toDouble(), summary.totalExpense, 1e-9)
    }

    // ------------------------------------------------------------ compare

    @Test fun compareComputesUnitPriceAndTotal() {
        val row = UiCompare.rows(listOf(UiCompareItem("A", 10.0, 2.0, "\u4EF6"))).single()
        assertEquals(5.0, row.unitPrice!!, 1e-9)
        assertEquals(10.0, row.total!!, 1e-9)
        assertNull(UiCompare.unitPrice(10.0, 0.0))
        assertNull(UiCompare.unitPrice(-1.0, 2.0))
        assertNull(UiCompare.unitPrice(Double.NaN, 2.0))
        assertNull(UiCompare.unitPrice(10.0, Double.POSITIVE_INFINITY))
    }

    @Test fun compareRankingKeepsInvalidRowsLastAndTiesStable() {
        val rows = UiCompare.rows(
            listOf(
                UiCompareItem("A", 10.0, 2.0, "\u4EF6"),
                UiCompareItem("B", 18.0, 3.0, "\u4EF6"),
                UiCompareItem("C", 1.0, 0.0, "\u4EF6"),
                UiCompareItem("D", 10.0, 2.0, "\u76D2"),
            ),
        )
        assertEquals(listOf("A", "B", "C", "D"), UiCompare.rankByUnitPrice(rows).map { it.name })
        assertEquals(listOf("B", "A", "C", "D"), UiCompare.rankByUnitPrice(rows, descending = true).map { it.name })
    }

    @Test fun compareOnlyComparesWithinTheSameUnit() {
        val rows = UiCompare.rows(
            listOf(
                UiCompareItem("A", 10.0, 2.0, "\u4EF6"),
                UiCompareItem("B", 18.0, 3.0, "\u4EF6"),
                UiCompareItem("Box", 40.0, 2.0, "\u76D2"),
            ),
        )
        val cheapest = UiCompare.cheapestByUnit(rows)
        assertEquals("A", cheapest["\u4EF6"]?.name)
        assertEquals("Box", cheapest["\u76D2"]?.name)
        assertTrue(UiCompare.isLowest(rows, rows[0]))
        assertFalse(UiCompare.isLowest(rows, rows[1]))
        assertEquals(20.0, UiCompare.differenceFromCheapest(rows, rows[1])!!, 1e-9)
        assertEquals(0.0, UiCompare.differenceFromCheapest(rows, rows[0])!!, 1e-9)
    }

    @Test fun compareHandlesEmptyAndBrokenItems() {
        assertTrue(UiCompare.rows(emptyList()).isEmpty())
        val broken = UiCompare.rows(listOf(UiCompareItem("X", Double.NaN, 2.0, ""))).single()
        assertNull(broken.unitPrice)
        assertNull(broken.total)
        assertNull(UiCompare.differenceFromCheapest(listOf(broken), broken))
    }

    // ------------------------------------------------------------ decision

    @Test fun decisionUsesWeightedAverageAndRanksDescending() {
        val criteria = listOf(UiCriterion("Price", 2.0), UiCriterion("Quality", 1.0))
        val items = listOf(UiDecisionItem("A", listOf(10.0, 1.0)), UiDecisionItem("B", listOf(1.0, 10.0)))
        val ranking = UiDecision.rank(criteria, items)
        assertEquals(listOf("A", "B"), ranking.map { it.name })
        assertEquals(7.0, ranking[0].score, 1e-9)
        assertEquals(4.0, ranking[1].score, 1e-9)
    }

    @Test fun decisionFallsBackToEqualWeightsWhenAllWeightsAreZero() {
        val criteria = listOf(UiCriterion("A", 0.0), UiCriterion("B", 0.0))
        val ranking = UiDecision.rank(criteria, listOf(UiDecisionItem("X", listOf(10.0, 2.0))))
        assertEquals(6.0, ranking.single().score, 1e-9)
    }

    @Test fun decisionWeightsChangeRankingLive() {
        val criteria = listOf(UiCriterion("Price", 8.0), UiCriterion("Quality", 2.0))
        val items = listOf(UiDecisionItem("A", listOf(9.0, 1.0)), UiDecisionItem("B", listOf(5.0, 9.0)))
        assertEquals("A", UiDecision.rank(criteria, items).first().name)
        assertEquals("B", UiDecision.rank(criteria, items, listOf(0.0, 10.0)).first().name)
    }

    @Test fun decisionSanitizesNaNMissingScoresAndBounds() {
        val criteria = listOf(UiCriterion("A", Double.NaN), UiCriterion("B", 5.0))
        assertEquals(0.0, UiDecision.sanitizeWeight(Double.NaN), 1e-9)
        assertEquals(UiDecision.MAX_WEIGHT, UiDecision.sanitizeWeight(999.0), 1e-9)
        val rank = UiDecision.rank(criteria, listOf(UiDecisionItem("Short", listOf(4.0)))).single()
        assertEquals(4.0, rank.score, 1e-9)
        assertEquals(100.0, UiDecision.score(listOf(1000.0), listOf(1.0)), 1e-9)
    }

    @Test fun decisionPreservesPercentScaleScores() {
        val criteria = listOf(UiCriterion("Price", 50.0), UiCriterion("Quality", 50.0))
        val ranked = UiDecision.rank(criteria, listOf(UiDecisionItem("A", listOf(90.0,70.0)), UiDecisionItem("B", listOf(65.0,95.0))))
        assertEquals(80.0, ranked[0].score, 1e-9)
        assertEquals(80.0, ranked[1].score, 1e-9)
        assertEquals("A", UiDecision.rank(criteria, listOf(UiDecisionItem("A", listOf(90.0,70.0)), UiDecisionItem("B", listOf(65.0,95.0))), listOf(100.0,0.0)).first().name)
    }

    @Test fun decisionTiesKeepInputOrder() {
        val criteria = listOf(UiCriterion("A", 1.0))
        val ranking = UiDecision.rank(criteria, listOf(UiDecisionItem("First", listOf(5.0)), UiDecisionItem("Second", listOf(5.0))))
        assertEquals(listOf("First", "Second"), ranking.map { it.name })
    }

    // ------------------------------------------------------------ quiz

    @Test fun quizGradesRightWrongAndUnanswered() {
        val questions = UiQuiz.validate(
            listOf(
                UiQuizQuestion("2+2?", listOf("3", "4"), 1, "Four."),
                UiQuizQuestion("Capital of France?", listOf("Paris", "Rome"), 0),
            ),
        )
        val partial = UiQuiz.grade(questions, listOf(1, -1))
        assertEquals(1, partial.correct)
        assertEquals(1, partial.answered)
        assertEquals(2, partial.total)
        assertEquals(50, partial.percent)
        assertEquals(listOf(true, null), partial.verdicts)
        assertFalse(partial.complete)

        val perfect = UiQuiz.grade(questions, listOf(1, 0))
        assertEquals(100, perfect.percent)
        assertTrue(perfect.complete)
    }

    @Test fun quizRejectsUnusableQuestions() {
        assertTrue(runCatching { UiQuiz.validate(emptyList()) }.isFailure)
        assertTrue(runCatching {
            UiQuiz.validate(listOf(UiQuizQuestion("Q", listOf("A", "B"), 2)))
        }.isFailure)
        assertTrue(runCatching {
            UiQuiz.validate(listOf(UiQuizQuestion("Q", listOf("A", "A"), 0)))
        }.isFailure)
        assertTrue(runCatching {
            UiQuiz.validate(listOf(UiQuizQuestion("Q", listOf("A"), 0)))
        }.isFailure)
        assertTrue(runCatching {
            UiQuiz.validate(listOf(UiQuizQuestion("  ", listOf("A", "B"), 0)))
        }.isFailure)
        assertEquals(0, UiQuiz.percent(0, 0))
    }

    @Test fun quizValidationTrimsAndKeepsExplanation() {
        val question = UiQuiz.validate(listOf(UiQuizQuestion("  Q  ", listOf(" A ", " B "), 1, " because "))).single()
        assertEquals("Q", question.question)
        assertEquals(listOf("A", "B"), question.options)
        assertEquals("because", question.explanation)
    }

    // ------------------------------------------------------------ directory

    private val directoryItems = listOf(
        UiDirectoryItem("Alpha", "First tool", "Tools", 10.0),
        UiDirectoryItem("beta", "Second doc", "Docs", 30.0),
        UiDirectoryItem("Gamma", "Free sample", "Tools", null),
    )

    @Test fun directoryFiltersByCaseInsensitiveQueryAndCategory() {
        assertEquals(listOf("Alpha"), UiDirectory.filter(directoryItems, "aLpHa").map { it.name })
        assertEquals(listOf("beta"), UiDirectory.filter(directoryItems, "SECOND").map { it.name })
        assertEquals(listOf("Alpha", "Gamma"), UiDirectory.filter(directoryItems, category = "Tools").map { it.name })
        assertEquals(listOf("Gamma"), UiDirectory.filter(directoryItems, "free", "Tools").map { it.name })
        assertTrue(UiDirectory.filter(directoryItems, "missing").isEmpty())
        assertTrue(UiDirectory.matches(directoryItems[1], "doc", "Docs"))
        assertFalse(UiDirectory.matches(directoryItems[1], "doc", "Tools"))
    }

    @Test fun directorySortsWithNullPricesLast() {
        assertEquals(listOf("Alpha", "beta", "Gamma"), UiDirectory.filter(directoryItems, sort = UiDirectorySort.PRICE_ASC).map { it.name })
        assertEquals(listOf("beta", "Alpha", "Gamma"), UiDirectory.filter(directoryItems, sort = UiDirectorySort.PRICE_DESC).map { it.name })
        assertEquals(listOf("Alpha", "beta", "Gamma"), UiDirectory.filter(directoryItems, sort = UiDirectorySort.NAME).map { it.name })
    }

    @Test fun directoryCategoriesAreDistinctInOrder() {
        assertEquals(listOf("Tools", "Docs"), UiDirectory.categories(directoryItems + UiDirectoryItem("Delta", category = "Tools")))
        assertNull(UiDirectory.validImage("javascript:alert(1)"))
        assertNull(UiDirectory.validImage("file:///etc/passwd"))
        assertNotNull(UiDirectory.validImage("https://example.com/a.png"))
    }

    // ------------------------------------------------------------ wizard

    @Test fun wizardClampsNavigationAtBothEnds() {
        assertEquals(0, UiWizard.clamp(-3, 4))
        assertEquals(3, UiWizard.clamp(99, 4))
        assertEquals(3, UiWizard.next(3, 4))
        assertEquals(0, UiWizard.back(0, 4))
        assertEquals(1, UiWizard.next(0, 4))
        assertTrue(UiWizard.isFirst(0, 4))
        assertTrue(UiWizard.isLast(3, 4))
        assertFalse(UiWizard.isLast(2, 4))
        assertEquals("2 / 4", UiWizard.stepLabel(1, 4))
        assertEquals(0, UiWizard.clamp(2, 0))
    }

    @Test fun formattingStaysStableUnderOtherDefaultLocales() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("5.00", UiFormat.money(5.0))
            assertEquals("12.5", UiFormat.number(12.5))
        } finally {
            Locale.setDefault(original)
        }
    }
}
