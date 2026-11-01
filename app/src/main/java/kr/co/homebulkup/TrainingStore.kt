package kr.co.homebulkup

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

internal object TrainingStore {
    fun load(prefs: SharedPreferences, days: List<WorkoutDay>): List<TrainingSet> {
        val saved = prefs.getString("training_sets_v2", null)
        if (saved != null) return runCatching {
            val array = JSONArray(saved)
            (0 until array.length()).map { index ->
                val o = array.getJSONObject(index)
                TrainingSet(o.getString("date"), o.getString("id"), o.getString("name"), o.getInt("number"), o.optString("weight"), o.optString("reps"), o.optBoolean("done"), o.optString("unit", "reps"), o.optString("kind", "work"), if (o.isNull("rir")) null else o.getInt("rir"))
            }
        }.getOrElse { emptyList() }
        // Legacy keys store only routine positions. Preserve these as unconfirmed historical
        // inputs; users confirm sets in v2 before they count toward recommendations/statistics.
        val legacy = runCatching { JSONObject(prefs.getString("exercise_logs", "{}") ?: "{}") }.getOrElse { JSONObject() }
        val pattern = Regex("(\\d{4}-\\d{2}-\\d{2})-(\\d+)-(\\d+)-set-(\\d+)-weight")
        val result = legacy.keys().asSequence().mapNotNull { key ->
            val match = pattern.matchEntire(key) ?: return@mapNotNull null
            val (date, d, e, number) = match.destructured
            val exercise = days.getOrNull(d.toInt())?.exercises?.getOrNull(e.toInt()) ?: return@mapNotNull null
            TrainingSet(date, exercise.id, exercise.name, number.toInt(), legacy.optString(key), legacy.optString(key.removeSuffix("weight") + "reps"), unit = if (exercise.reps.contains("초")) "seconds" else "reps")
        }.toList()
        save(prefs, result)
        return result
    }
    fun save(prefs: SharedPreferences, sets: List<TrainingSet>) {
        val array = JSONArray()
        sets.forEach { array.put(JSONObject().put("date", it.date).put("id", it.exerciseId).put("name", it.name).put("number", it.number).put("weight", it.weight).put("reps", it.reps).put("done", it.done).put("unit", it.unit).put("kind", it.kind).put("rir", it.rir ?: JSONObject.NULL)) }
        prefs.edit().putString("training_sets_v2", array.toString()).apply()
    }
}

internal fun loadEquipment(prefs: SharedPreferences): Equipment = Equipment(
    prefs.getBoolean("gear_dumbbells", true), prefs.getBoolean("gear_pullup", true),
    prefs.getString("gear_max", "20")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.5..200.0 } ?: 20.0,
    prefs.getString("gear_step", "1")?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.25..20.0 } ?: 1.0
)

internal fun sameMovement(first: String, second: String): Boolean = movement(first) == movement(second)
private fun movement(name: String): String = when {
    listOf("로우", "턱걸이", "풀오버", "슈퍼맨", "리버스 플라이").any { name.contains(it) } -> "pull"
    listOf("스쿼트", "런지", "월싯").any { name.contains(it) } -> "squat"
    listOf("데드리프트", "브리지").any { name.contains(it) } -> "hinge"
    listOf("컬").any { name.contains(it) } -> "curl"
    listOf("익스텐션", "킥백", "클로즈그립").any { name.contains(it) } -> "triceps"
    listOf("숄더", "파이크", "레터럴", "프런트").any { name.contains(it) } -> "shoulder"
    listOf("푸시업", "프레스", "플라이").any { name.contains(it) } -> "push"
    name.contains("카프") -> "calf"
    else -> "core"
}
