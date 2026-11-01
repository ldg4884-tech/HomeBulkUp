package kr.co.homebulkup

import java.time.LocalDate

/** A stable exercise identity survives routine reordering; names remain in historical records. */
data class TrainingSet(
    val date: String, val exerciseId: String, val name: String, val number: Int,
    val weight: String = "", val reps: String = "", val done: Boolean = false, val unit: String = "reps", val kind: String = "work", val rir: Int? = null
) {
    val valid: Boolean get() = weight.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..1000.0 } == true &&
        reps.toIntOrNull()?.let { it in 1..1000 } == true
    val volume: Double get() = if (valid && done && kind == "work" && unit == "reps") weight.toDouble() * reps.toInt() else 0.0
    val key: String get() = "$date/$exerciseId/$number"
}

data class Equipment(val dumbbells: Boolean = true, val pullupBar: Boolean = true, val maxKg: Double = 20.0, val increment: Double = 1.0)

internal fun usesDumbbell(name: String): Boolean = !name.startsWith("맨몸") && !name.startsWith("수건") && listOf("덤벨", "고블릿", "해머 컬", "컨센트레이션", "리버스 컬", "트라이셉스", "리버스 런지", "불가리안").any { name.contains(it) }
internal fun available(name: String, gear: Equipment): Boolean =
    (!usesDumbbell(name) || gear.dumbbells) && (!name.contains("철봉") && !name.contains("턱걸이") || gear.pullupBar)

internal fun previousSession(sets: List<TrainingSet>, name: String, today: String): List<TrainingSet> {
    val candidates = sets.filter { it.name == name && it.date < today && it.valid }
    val date = candidates.maxOfOrNull { it.date } ?: return emptyList()
    // A name may appear in two routines on the same day. Keep one coherent session.
    val id = candidates.filter { it.date == date }.last().exerciseId
    return candidates.filter { it.date == date && it.exerciseId == id }.sortedBy { it.number }
}

internal fun nextTarget(previous: List<TrainingSet>, requiredSets: Int, repRange: String, name: String, gear: Equipment): String {
    if (!available(name, gear)) return "현재 장비로 하기 어려워요. 대체 운동을 선택하거나 장비 설정을 확인하세요."
    if (previous.isEmpty()) return "첫 기록을 남겨 주세요. 자세가 안정적인 무게로 시작하세요."
    val work = previous.filter { it.kind == "work" }
    if (work.isEmpty()) return "준비 세트만 기록돼 있어요. 본 세트를 기록한 뒤 목표를 비교하세요."
    if (work.any { it.rir != null && it.rir < 2 }) return "이전 본 세트의 여유가 0–1회였어요. 증량하지 말고 같은 무게에서 자세와 회복을 확인하세요."
    if (work.any { !it.done }) return "이전 입력은 완료 여부를 확인할 수 없어요. 같은 무게부터 시작하고 각 세트 완료를 표시하세요."
    if (repRange.contains("초")) return "지난 유지 시간을 참고해 같은 시간부터 시작하세요. 여유가 있으면 5초 늘려 보세요."
    val upper = Regex("\\d+").findAll(repRange).map { it.value.toInt() }.toList().maxOrNull()
        ?: return "지난 횟수를 유지하고, 여유가 있을 때 마지막 세트에 1회를 더해 보세요."
    val requiredWork = (requiredSets - previous.count { it.kind == "warmup" && it.number <= requiredSets }).coerceAtLeast(1)
    val allAchieved = work.size >= requiredWork && work.take(requiredWork).all { it.reps.toInt() >= upper }
    if (!allAchieved) return "지난 무게를 유지하며 각 세트를 목표 상한 ${upper}회까지 천천히 늘려 보세요."
    val weight = work.maxOf { it.weight.toDouble() }
    if (!usesDumbbell(name) || weight + gear.increment > gear.maxKg)
        return "무게 한계에 도달했어요. 반복 1–2회 추가, 내리는 동작 3초, 또는 1세트 추가 중 하나만 선택하세요."
    return "모든 세트 목표 달성! 다음에는 덤벨 한 개 기준 ${formatKg(weight + gear.increment)}kg로 시도해 보세요. 자세가 흐트러지면 이전 무게로 돌아오세요."
}

internal fun formatKg(value: Double): String = java.text.DecimalFormat("0.0#", java.text.DecimalFormatSymbols(java.util.Locale.KOREA)).format(value)
internal fun weekDates(today: LocalDate, offset: Long = 0): Set<String> = (0L..6L).map { today.minusDays(it + offset).toString() }.toSet()
data class WeeklySummary(val sessions: Int, val sets: Int, val volume: Double, val records: List<String>, val warmups: Int = 0)
internal fun weeklySummary(sets: List<TrainingSet>, completed: Set<String>, today: LocalDate): WeeklySummary {
    val dates = weekDates(today)
    val valid = sets.filter { it.date in dates && it.done && it.valid && it.kind == "work" }
    val records = valid.groupBy { it.name }.mapNotNull { (name, entries) ->
        val past = sets.filter { it.name == name && it.date < today.minusDays(6).toString() && it.done && it.valid && it.kind == "work" }
        val best = entries.maxOf { it.weight.toDouble() }
        if (past.isNotEmpty() && best > past.maxOf { it.weight.toDouble() }) "$name ${formatKg(best)}kg" else null
    }
    return WeeklySummary(completed.count { it.substringBeforeLast('-') in dates }, valid.size, valid.sumOf { it.volume }, records, sets.count { it.date in dates && it.done && it.valid && it.kind == "warmup" })
}

data class WeightSummary(val current: Double?, val previous: Double?, val plateau: Boolean, val enough: Boolean)
internal fun weightSummary(history: List<Pair<String, Float>>, today: LocalDate): WeightSummary {
    val clean = history.filter { it.second.isFinite() && it.second in 20f..400f }.distinctBy { it.first }
    val current = clean.filter { it.first in weekDates(today) }
    val previous = clean.filter { it.first in weekDates(today, 7) }
    val enough = current.size >= 3 && previous.size >= 3
    val a = current.takeIf { it.isNotEmpty() }?.map { it.second.toDouble() }?.average()
    val b = previous.takeIf { it.isNotEmpty() }?.map { it.second.toDouble() }?.average()
    return WeightSummary(a, b, enough && a != null && b != null && a - b < 0.1 - 0.0001, enough)
}

internal fun remainingSeconds(deadline: Long, now: Long): Int = ((deadline - now).coerceAtLeast(0) / 1000.0).let { kotlin.math.ceil(it).toInt() }
