package kr.co.homebulkup

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class GrowthLogicTest {
    private val today = LocalDate.of(2026, 11, 1)
    private fun set(date: String = "2026-10-31", id: String = "a", number: Int = 1, weight: String = "10", reps: String = "12", done: Boolean = true) =
        TrainingSet(date, id, "덤벨 컬", number, weight, reps, done)

    @Test fun historyExcludesTodayAndFutureAndKeepsOneSession() {
        val result = previousSession(listOf(set(), set(number = 2), set(id = "b"), set(date = today.toString()), set(date = "2026-11-02")), "덤벨 컬", today.toString())
        assertEquals(1, result.size); assertEquals("b", result.single().exerciseId)
    }
    @Test fun historyRejectsInvalidNumbers() {
        assertTrue(previousSession(listOf(set(weight = "NaN"), set(reps = "0")), "덤벨 컬", today.toString()).isEmpty())
    }
    @Test fun completedFullSessionSuggestsNextIncrement() {
        val previous = (1..3).map { set(number = it) }
        assertTrue(nextTarget(previous, 3, "8–12회", "덤벨 컬", Equipment()).contains("11.0kg"))
    }
    @Test fun fractionalIncrementIsNotRoundedToUnavailableWeight() {
        assertTrue(nextTarget(listOf(set()), 1, "8–12회", "덤벨 컬", Equipment(increment = 0.25)).contains("10.25kg"))
    }
    @Test fun partialSessionNeverIncreasesWeight() {
        assertFalse(nextTarget(listOf(set()), 3, "8–12회", "덤벨 컬", Equipment()).contains("11.0kg"))
    }
    @Test fun incompleteRepsKeepWeight() {
        assertTrue(nextTarget(listOf(set(reps = "8")), 1, "8–12회", "덤벨 컬", Equipment()).contains("유지"))
    }
    @Test fun equipmentLimitSuggestsTempoRepsAndSets() {
        val result = nextTarget(listOf(set(weight = "20")), 1, "8–12회", "덤벨 컬", Equipment())
        assertTrue(result.contains("3초")); assertTrue(result.contains("1세트")); assertTrue(result.contains("1–2회"))
    }
    @Test fun unconfirmedPastInputsNeverIncreaseWeight() {
        assertFalse(nextTarget(listOf(set(done = false)), 1, "8–12회", "덤벨 컬", Equipment()).contains("11.0kg"))
    }
    @Test fun timeBasedMovementsUseSeconds() {
        assertTrue(nextTarget(listOf(set()), 1, "30–60초", "플랭크", Equipment()).contains("5초"))
    }
    @Test fun bodyweightAvailabilityDoesNotRequireDumbbells() {
        val gear = Equipment(false, false)
        assertTrue(available("맨몸 리버스 런지", gear)); assertTrue(available("푸시업", gear))
        assertFalse(available("덤벨 컬", gear)); assertFalse(available("문틀 철봉 턱걸이", gear))
    }
    @Test fun replacementsFollowMovementPattern() {
        assertTrue(sameMovement("고블릿 스쿼트", "맨몸 스쿼트"))
        assertFalse(sameMovement("덤벨 컬", "푸시업"))
    }
    @Test fun volumeCountsOnlyValidCompletedSetsInWindow() {
        val report = weeklySummary(listOf(set(), set(done = false), set(reps = "NaN"), set(date = "2026-10-25"), set(date = "2026-11-02")), setOf("2026-10-31-0", "2026-11-02-0"), today)
        assertEquals(1, report.sets); assertEquals(1, report.sessions); assertEquals(120.0, report.volume, 0.001)
    }
    @Test fun timedSetsDoNotCountSecondsAsRepetitionsInVolume() {
        assertEquals(0.0, set().copy(unit = "seconds").volume, 0.0)
    }
    @Test fun recordsRequireActualPastComparison() {
        assertTrue(weeklySummary(listOf(set()), emptySet(), today).records.isEmpty())
        assertEquals(1, weeklySummary(listOf(set(), set(date = "2026-10-24", weight = "9")), emptySet(), today).records.size)
    }
    @Test fun weightPlateauNeedsAtLeastThreeDatesPerWindow() {
        val summary = weightSummary(listOf("2026-10-31" to 74f, "2026-10-24" to 74f), today)
        assertFalse(summary.enough); assertFalse(summary.plateau)
    }
    @Test fun weeklyAveragesIgnoreFutureAndUseCalendarWindows() {
        val history = (0L..13L).map { today.minusDays(it).toString() to 74f } + ("2026-11-02" to 200f)
        val summary = weightSummary(history, today)
        assertTrue(summary.plateau); assertEquals(74.0, summary.current!!, 0.001)
    }
    @Test fun exactPointOneKgGainIsNotAFalsePlateauFromFloatRounding() {
        val history = (0L..13L).map { today.minusDays(it).toString() to if (it < 7) 74.1f else 74f }
        assertFalse(weightSummary(history, today).plateau)
    }
    @Test fun weightGainClearsPlateau() {
        val history = (0L..13L).map { today.minusDays(it).toString() to if (it < 7) 74.5f else 74f }
        assertFalse(weightSummary(history, today).plateau)
    }
    @Test fun timerRecoversElapsedTimeAndNeverGoesNegative() {
        assertEquals(60, remainingSeconds(100000, 40000))
        assertEquals(0, remainingSeconds(100000, 200000))
        assertEquals(1, remainingSeconds(100000, 99999))
    }
}
