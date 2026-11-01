package kr.co.homebulkup

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

internal object BackupCodec {
    private val strings = setOf("weight", "goal", "protein", "calories", "completed", "weight_history", "exercise_logs", "custom_workout_program_v1", "food_logs_v1", "training_sets_v2", "gear_max", "gear_step", "schedule_plan", "recovery_plan", "wellness_logs", "focus_date")
    private val integers = mapOf("active_tab" to 0..4, "active_day" to 0..3, "focus_day" to 0..3, "focus_cursor" to 0..1000, "rest_duration" to 15..300, "rest_remaining" to 0..300, "food_goal_kcal" to 500..8000, "food_goal_protein" to 20..400)
    private val booleans = setOf("rest_visible", "gear_dumbbells", "gear_pullup", "notify_enabled", "focus_mode")
    fun encode(prefs: SharedPreferences): String = encodeValues(prefs.all)
    fun encodeValues(values: Map<String, *>): String {
        val data = JSONObject()
        values.filterKeys { it in strings || it in integers || it in booleans || it == "rest_deadline" }.forEach { (key, value) -> data.put(key, value) }
        return JSONObject().put("app", "HomeBulkUp").put("backupVersion", 1).put("exportedAt", System.currentTimeMillis()).put("preferences", data).toString(2)
    }
    fun read(reader: java.io.Reader): String {
        val text = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            require(text.length + count <= 8 * 1024 * 1024) { "백업 파일이 너무 커요 (최대 8MB)." }
            text.append(buffer, 0, count)
        }
        return text.toString()
    }
    fun decode(raw: String): Map<String, Any> {
        require(raw.length <= 8 * 1024 * 1024) { "백업 파일이 너무 커요 (최대 8MB)." }
        val backup = JSONObject(raw)
        require(backup.optString("app") == "HomeBulkUp" && backup.optInt("backupVersion") == 1) { "지원하는 홈벌크업 백업이 아니에요." }
        val prefs = backup.getJSONObject("preferences")
        val result = mutableMapOf<String, Any>()
        prefs.keys().forEach { key ->
            val value = prefs.get(key)
            when {
                key in strings -> { require(value is String) { "$key 데이터 형식이 올바르지 않아요." }; validateString(key, value); result[key] = value }
                key in integers -> { require(value is Number && value.toDouble() == value.toInt().toDouble() && value.toInt() in integers.getValue(key)) { "$key 범위를 확인하세요." }; result[key] = value.toInt() }
                key in booleans -> { require(value is Boolean); result[key] = value }
                key == "rest_deadline" -> { require(value is Number && value.toDouble().isFinite() && value.toLong() >= 0); result[key] = value.toLong() }
                // Unknown future preferences are ignored, never injected into typed app storage.
            }
        }
        require(result.isNotEmpty()) { "복원할 데이터가 없어요." }
        return result
    }
    private fun validateString(key: String, value: String) {
        when (key) {
            "weight", "goal" -> require(value.toDoubleOrNull()?.let { it.isFinite() && it in 20.0..400.0 } == true)
            "protein", "calories", "gear_max", "gear_step" -> require(value.toDoubleOrNull()?.let { it.isFinite() && it > 0 && it <= 10000 } == true)
            "focus_date" -> LocalDate.parse(value)
            "schedule_plan" -> { val o = JSONObject(value); val days = o.getJSONArray("days"); require(days.length() == 4); for (i in 0..3) require(days.getInt(i) in 1..7); require(Regex("\\d{2}:\\d{2}").matches(o.getString("time"))); java.time.LocalTime.parse(o.getString("time")); require(o.get("enabled") is Boolean) }
            "recovery_plan" -> { val o = JSONObject(value); LocalDate.parse(o.getString("start")); require(o.getString("mode") in setOf("rest", "light")) }
            "wellness_logs" -> { val a = JSONArray(value); for (i in 0 until a.length()) { val o = a.getJSONObject(i); LocalDate.parse(o.getString("date")); require(o.getInt("energy") in 1..5); val sleep = o.optString("sleep"); require(sleep.isBlank() || sleep.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..24.0 } == true); require(o.optString("pain").length <= 200 && o.optString("note").length <= 500) } }
            "exercise_logs" -> JSONObject(value)
            "completed" -> { val a = JSONArray(value); for (i in 0 until a.length()) { val entry = a.getString(i); LocalDate.parse(entry.substringBeforeLast('-')); require(entry.substringAfterLast('-').toInt() in 0..3) } }
            "weight_history" -> { val a = JSONArray(value); for (i in 0 until a.length()) { val o = a.getJSONObject(i); LocalDate.parse(o.getString("date")); require(o.getDouble("weight").let { it.isFinite() && it in 20.0..400.0 }) } }
            "food_logs_v1" -> { val a = JSONArray(value); for (i in 0 until a.length()) { val o = a.getJSONObject(i); LocalDate.parse(o.getString("date")); require(o.getString("name").isNotBlank()); require(o.getDouble("kcal").let { it.isFinite() && it >= 0 }) } }
            "training_sets_v2" -> { val a = JSONArray(value); for (i in 0 until a.length()) { val o = a.getJSONObject(i); LocalDate.parse(o.getString("date")); require(o.getString("id").isNotBlank() && o.getString("name").isNotBlank() && o.getInt("number") in 1..10); require(o.get("weight") is String && o.get("reps") is String && o.get("done") is Boolean); require(o.optString("unit", "reps") in setOf("reps", "seconds")); require(o.optString("kind", "work") in setOf("work", "warmup")); if (!o.isNull("rir")) require(o.get("rir") is Number && o.getDouble("rir") == o.getInt("rir").toDouble() && o.getInt("rir") in 0..5) } }
            "custom_workout_program_v1" -> { val a = JSONArray(value); require(a.length() == 4); val ids = mutableSetOf<String>(); for (i in 0..3) { val exercises = a.getJSONObject(i).getJSONArray("exercises"); require(exercises.length() in 1..100); for (j in 0 until exercises.length()) { val e = exercises.getJSONObject(j); require(e.getString("name").isNotBlank() && e.getInt("sets") in 1..10); if (e.has("id")) require(e.getString("id").isNotBlank() && ids.add(e.getString("id"))) } } }
        }
    }
    fun restore(prefs: SharedPreferences, values: Map<String, Any>) {
        val editor = prefs.edit().clear()
        values.forEach { (key, value) -> when (value) { is String -> editor.putString(key, value); is Boolean -> editor.putBoolean(key, value); is Int -> editor.putInt(key, value); is Long -> editor.putLong(key, value) } }
        check(editor.commit()) { "복원 데이터를 저장하지 못했어요." }
    }
}
