package kr.co.homebulkup

import org.json.JSONArray
import org.json.JSONObject
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.*

internal data class BackupPreview(val exportedAt: Long, val sets: Int, val sessions: Int, val weights: Int, val foods: Int, val wellness: Int)
internal data class PreparedBackup(val values: Map<String, Any>, val preview: BackupPreview)
internal fun prepareBackup(raw: String): PreparedBackup {
    val values = BackupCodec.decode(raw)
    fun count(key: String): Int = (values[key] as? String)?.let { JSONArray(it).length() } ?: 0
    val sets = if (values.containsKey("training_sets_v2")) count("training_sets_v2") else (values["exercise_logs"] as? String)?.let { JSONObject(it).keys().asSequence().count { key -> key.endsWith("-weight") } } ?: 0
    return PreparedBackup(values, BackupPreview(JSONObject(raw).optLong("exportedAt", 0), sets, count("completed"), count("weight_history"), count("food_logs_v1"), count("wellness_logs")))
}
@Composable internal fun BackupPreviewText(preview: BackupPreview) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("백업 날짜: " + if (preview.exportedAt > 0) SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(preview.exportedAt)) else "정보 없음 (이전 형식)")
        Text("운동 완료 ${preview.sessions}건 · 세트 ${preview.sets}건")
        Text("체중 ${preview.weights}건 · 식단 ${preview.foods}건 · 컨디션 ${preview.wellness}건")
        Text("이 백업의 기록으로 교체합니다. 현재 기록은 복원 전 보호 사본으로 먼저 저장해요.", color = Orange)
    }
}
