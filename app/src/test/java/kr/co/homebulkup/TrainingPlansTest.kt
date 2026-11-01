package kr.co.homebulkup

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TrainingPlansTest {
    private val date = LocalDate.of(2026, 11, 1)
    private val exercise = Exercise("덤벨 컬", "이두", 3, "8–12회", "curl")
    private fun record(number: Int, kind: String = "work", rir: Int? = 3) = TrainingSet("2026-10-31", "curl", "덤벨 컬", number, "10", "12", true, kind = kind, rir = rir)
    @Test fun hardWorkingSetPreventsIncrease() {
        assertTrue(nextTarget((1..3).map { record(it, rir = 1) }, 3, "8–12회", "덤벨 컬", Equipment()).contains("증량하지"))
    }
    @Test fun hardWarmupDoesNotBlockGoodWorkingSets() {
        val records = listOf(record(1, "warmup", 0).copy(weight = "5"), record(2), record(3))
        assertTrue(nextTarget(records, 3, "8–12회", "덤벨 컬", Equipment()).contains("11.0kg"))
    }
    @Test fun warmupsAreSeparateAndNeverSetPersonalRecords() {
        val report = weeklySummary(listOf(record(1, "warmup").copy(weight = "100"), record(2), record(1).copy(date = "2026-10-01", weight = "20")), emptySet(), date)
        assertEquals(1, report.warmups); assertEquals(1, report.sets); assertEquals(120.0, report.volume, 0.01); assertTrue(report.records.isEmpty())
    }
    @Test fun warmupOnlySessionDoesNotSuggestIncrease() {
        assertTrue(nextTarget(listOf(record(1, "warmup")), 1, "8–12회", "덤벨 컬", Equipment()).contains("본 세트"))
    }
    @Test fun deloadWeightRespectsAvailableIncrementAndMaximum() {
        assertEquals("8.0", deloadWeight(11.0, Equipment(increment = 1.0)))
        assertEquals("5.0", deloadWeight(20.0, Equipment(maxKg = 5.0, increment = 0.5)))
    }
    @Test fun recoveryWindowIsExactlySevenDays() {
        val plan = RecoveryPlan("2026-11-01", "light")
        assertFalse(plan.active(date.minusDays(1))); assertTrue(plan.active(date.plusDays(6))); assertFalse(plan.active(date.plusDays(7)))
    }
    @Test fun deloadDoesNotMutateOriginalRoutine() {
        val list = listOf(exercise)
        assertEquals(2, RecoveryPlan(date.toString(), "light").exercises(list, date).single().sets)
        assertEquals(3, list.single().sets)
        assertEquals(3, RecoveryPlan(date.toString(), "light").exercises(list, date.plusDays(7)).single().sets)
    }
    @Test fun restWeekHidesExercisePlanAndExpires() {
        val plan = RecoveryPlan(date.toString(), "rest")
        assertTrue(plan.exercises(listOf(exercise), date).isEmpty())
        assertEquals(1, plan.exercises(listOf(exercise), date.plusDays(7)).size)
    }
    @Test fun painAndLowEnergyOverrideIncreaseAdvice() {
        val previous = (1..3).map { record(it) }
        assertTrue(trainingAdvice(previous, exercise, Equipment(), null, Wellness(date.toString(), pain = "어깨"), date).contains("중단"))
        assertTrue(trainingAdvice(previous, exercise, Equipment(), null, Wellness(date.toString(), energy = 1), date).contains("회복"))
    }
    @Test fun upcomingWeekdayUsesDeviceZone() {
        val now = ZonedDateTime.of(date, LocalTime.of(20, 0), ZoneId.of("Asia/Seoul"))
        val next = nextWorkoutTime(now, 1, LocalTime.of(18, 0))
        assertEquals("2026-11-02T18:00+09:00[Asia/Seoul]", next.toString())
    }
    @Test fun passedSameDayTimeMovesToNextWeek() {
        val now = ZonedDateTime.of(date, LocalTime.of(20, 0), ZoneId.of("Asia/Seoul"))
        assertEquals(date.plusWeeks(1), nextWorkoutTime(now, 7, LocalTime.of(18, 0)).toLocalDate())
    }
    @Test fun daylightSavingGapResolvesToValidLocalTime() {
        val now = ZonedDateTime.of(2026, 3, 7, 12, 0, 0, 0, ZoneId.of("America/New_York"))
        assertEquals(3, nextWorkoutTime(now, 7, LocalTime.of(2, 30)).hour)
    }
    @Test fun undoRestoresOnlyAffectedRecord() {
        val old = record(1); val unrelated = record(2).copy(weight = "12")
        val undone = undoSetChange(listOf(unrelated), old.key, old)
        assertEquals("12", undone.first { it.number == 2 }.weight); assertEquals(old, undone.first { it.number == 1 })
    }
    @Test fun previewCountsDecodedDataAndLegacyBackup() {
        val values = mapOf("completed" to "[\"2026-10-31-0\"]", "weight_history" to "[{\"date\":\"2026-10-31\",\"weight\":74}]", "exercise_logs" to "{\"2026-10-31-0-0-set-1-weight\":\"10\"}")
        val preview = prepareBackup(BackupCodec.encodeValues(values)).preview
        assertEquals(1, preview.sets); assertEquals(1, preview.weights); assertEquals(1, preview.sessions)
    }
    @Test(expected = IllegalArgumentException::class) fun invalidRirRejectedInBackup() {
        val row = "[{\"date\":\"2026-11-01\",\"id\":\"x\",\"name\":\"컬\",\"number\":1,\"weight\":\"10\",\"reps\":\"12\",\"done\":true,\"rir\":9}]"
        BackupCodec.decode(BackupCodec.encodeValues(mapOf("training_sets_v2" to row)))
    }
    @Test(expected = IllegalArgumentException::class) fun invalidScheduleRejectedInBackup() {
        BackupCodec.decode(BackupCodec.encodeValues(mapOf("schedule_plan" to "{\"days\":[1,2,3,9],\"time\":\"18:00\",\"enabled\":true}")))
    }
}
