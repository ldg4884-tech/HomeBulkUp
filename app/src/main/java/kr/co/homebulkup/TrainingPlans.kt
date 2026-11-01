package kr.co.homebulkup

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.*
import kotlin.math.ceil

internal data class RecoveryPlan(val start: String, val mode: String) {
    fun active(date: LocalDate): Boolean = runCatching { date in LocalDate.parse(start)..LocalDate.parse(start).plusDays(6) }.getOrDefault(false)
    fun exercises(original: List<Exercise>, date: LocalDate): List<Exercise> = when {
        !active(date) -> original
        mode == "rest" -> emptyList()
        else -> original.map { it.copy(sets = ceil(it.sets * 0.5).toInt().coerceAtLeast(1)) }
    }
}
internal data class Wellness(val date: String, val sleep: String = "", val energy: Int = 3, val pain: String = "", val note: String = "")
internal data class SchedulePlan(val days: List<Int> = listOf(1, 2, 4, 5), val time: String = "18:00", val enabled: Boolean = false)

internal object PlanStore {
    fun recovery(prefs: SharedPreferences): RecoveryPlan? = runCatching { JSONObject(prefs.getString("recovery_plan", null) ?: return null).let { RecoveryPlan(it.getString("start"), it.getString("mode")) } }.getOrNull()
    fun saveRecovery(prefs: SharedPreferences, plan: RecoveryPlan?) {
        prefs.edit().apply { if (plan == null) remove("recovery_plan") else putString("recovery_plan", JSONObject().put("start", plan.start).put("mode", plan.mode).toString()) }.apply()
    }
    fun wellness(prefs: SharedPreferences): List<Wellness> = runCatching {
        val a = JSONArray(prefs.getString("wellness_logs", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Wellness(o.getString("date"), o.optString("sleep"), o.optInt("energy", 3), o.optString("pain"), o.optString("note")) } }
    }.getOrDefault(emptyList())
    fun saveWellness(prefs: SharedPreferences, value: Wellness) {
        val a = JSONArray()
        (wellness(prefs).filterNot { it.date == value.date } + value).sortedBy { it.date }.forEach { a.put(JSONObject().put("date", it.date).put("sleep", it.sleep).put("energy", it.energy).put("pain", it.pain).put("note", it.note)) }
        prefs.edit().putString("wellness_logs", a.toString()).apply()
    }
    fun schedule(prefs: SharedPreferences): SchedulePlan = runCatching {
        val o = JSONObject(prefs.getString("schedule_plan", null) ?: return SchedulePlan())
        val a = o.getJSONArray("days")
        SchedulePlan((0..3).map { a.getInt(it) }, o.getString("time"), o.getBoolean("enabled"))
    }.getOrDefault(SchedulePlan())
    fun saveSchedule(prefs: SharedPreferences, plan: SchedulePlan) {
        prefs.edit().putString("schedule_plan", JSONObject().put("days", JSONArray(plan.days)).put("time", plan.time).put("enabled", plan.enabled).toString()).apply()
    }
}
internal fun nextWorkoutTime(now: ZonedDateTime, weekday: Int, time: LocalTime): ZonedDateTime {
    require(weekday in 1..7)
    var date = now.toLocalDate().plusDays(((weekday - now.dayOfWeek.value + 7) % 7).toLong())
    var candidate = date.atTime(time).atZone(now.zone)
    if (!candidate.isAfter(now)) { date = date.plusWeeks(1); candidate = date.atTime(time).atZone(now.zone) }
    return candidate
}
internal fun trainingAdvice(previous: List<TrainingSet>, exercise: Exercise, gear: Equipment, recovery: RecoveryPlan?, wellness: Wellness, date: LocalDate): String = when {
    recovery?.active(date) == true -> if (recovery.mode == "rest") "휴식 주간입니다. 회복을 우선하세요." else "가벼운 주간 · 세트 절반, 이전 무게의 약 80%를 권장해요. 원래 루틴은 보존됩니다."
    wellness.pain.isNotBlank() -> "불편한 부위를 기록했어요. 통증을 유발하는 운동은 중단하고 증량은 미뤄 주세요."
    wellness.energy <= 2 || wellness.sleep.toDoubleOrNull()?.let { it < 6 } == true -> "컨디션이 낮거나 수면이 부족해요. 증량보다 이전 무게 유지와 회복을 우선하세요."
    else -> nextTarget(previous, exercise.sets, exercise.reps, exercise.name, gear)
}
internal fun undoSetChange(current: List<TrainingSet>, key: String, previous: TrainingSet?): List<TrainingSet> = current.filterNot { it.key == key } + listOfNotNull(previous)

internal fun deloadWeight(weight: Double, gear: Equipment): String = formatKg((kotlin.math.floor(kotlin.math.min(weight * 0.8, gear.maxKg) / gear.increment) * gear.increment).coerceAtLeast(0.0))
