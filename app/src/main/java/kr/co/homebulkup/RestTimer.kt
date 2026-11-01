package kr.co.homebulkup

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal data class RestTimer(val duration: Int = 90, val remaining: Int = 90, val deadline: Long = 0L, val visible: Boolean = false) {
    val running: Boolean get() = deadline > 0L
    fun tick(now: Long): RestTimer {
        if (!running) return this
        val seconds = remainingSeconds(deadline, now).coerceAtMost(300)
        return copy(remaining = seconds, deadline = if (seconds == 0) 0L else deadline)
    }
    fun start(now: Long): RestTimer = copy(remaining = if (remaining > 0) remaining else duration,
        deadline = now + (if (remaining > 0) remaining else duration) * 1000L)
    fun fresh(now: Long): RestTimer = copy(remaining = duration, deadline = now + duration * 1000L, visible = true)
    fun pause(now: Long): RestTimer = tick(now).copy(deadline = 0L)
    fun reset(): RestTimer = copy(remaining = duration, deadline = 0L)
    fun adjust(seconds: Int): RestTimer = if (running) this else (remaining + seconds).coerceIn(15, 300).let { copy(duration = it, remaining = it) }
}

internal object RestTimerStore {
    fun load(prefs: SharedPreferences, now: Long): RestTimer = RestTimer(
        prefs.getInt("rest_duration", 90).coerceIn(15, 300), prefs.getInt("rest_remaining", 90).coerceIn(0, 300),
        prefs.getLong("rest_deadline", 0L).coerceAtLeast(0), prefs.getBoolean("rest_visible", false)
    ).tick(now)
    fun save(prefs: SharedPreferences, state: RestTimer) {
        prefs.edit().putInt("rest_duration", state.duration).putInt("rest_remaining", state.remaining)
            .putLong("rest_deadline", state.deadline).putBoolean("rest_visible", state.visible).apply()
    }
}

@Composable internal fun RestTimerDialog(state: RestTimer, update: (RestTimer) -> Unit) {
    AlertDialog(onDismissRequest = { update(state.copy(visible = false)) }, title = { Text("세트 휴식 타이머") }, text = {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${state.remaining}초", color = Lime, fontSize = 42.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { update(state.adjust(-15)) }, enabled = !state.running) { Text("-15초") }
                OutlinedButton(onClick = { update(state.adjust(15)) }, enabled = !state.running) { Text("+15초") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { update(if (state.running) state.pause(System.currentTimeMillis()) else state.start(System.currentTimeMillis())) }) { Text(if (state.running) "일시정지" else "시작") }
                OutlinedButton(onClick = { update(state.reset()) }) { Text("초기화") }
            }
            Text(if (state.remaining == 0) "휴식 완료! 다음 세트를 시작하세요." else "창을 닫아도 타이머는 계속됩니다.", color = Muted)
        }
    }, confirmButton = { TextButton(onClick = { update(state.copy(visible = false)) }) { Text("닫기") } })
}
