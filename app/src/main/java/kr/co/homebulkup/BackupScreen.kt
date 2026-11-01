package kr.co.homebulkup

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable internal fun AutoBackupSettings() {
    val context = LocalContext.current
    val config = remember { context.getSharedPreferences("backup_config", 0) }
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var restore by remember { mutableStateOf<File?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    DisposableEffect(config) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh++ }
        config.registerOnSharedPreferenceChangeListener(listener)
        onDispose { config.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            config.edit().putString("tree", uri.toString()).remove("external_at").remove("error").apply(); BackupJobs.enqueue(context)
            message = "자동 백업 폴더를 연결했어요. 백업 결과를 확인해 주세요."
        } catch (_: Exception) { message = "이 폴더의 지속 접근 권한을 얻을 수 없어요. 다른 폴더를 선택하세요." }
    }
    fun timestamp(value: Long): String = if (value == 0L) "아직 없음" else SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(value))
    val files = remember(refresh) { BackupFiles.list(context) }
    Column(Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(20.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("자동 백업 · 복원", color = Lime, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text("기록 변경 후 자동 저장 · 하루 한 번 예약 백업", color = White, fontSize = 13.sp)
        Text("기기 내부 백업: ${timestamp(config.getLong("local_at", 0L))}", color = Muted, fontSize = 12.sp)
        Text("폴더 백업: ${if (config.contains("tree")) timestamp(config.getLong("external_at", 0L)) else "폴더 연결 필요"}", color = Muted, fontSize = 12.sp)
        config.getString("error", null)?.let { Text("백업 확인 필요: $it", color = Orange, fontSize = 12.sp) }
        Text("내부는 날짜별 최근 7개, 연결 폴더는 최근 7개 파일을 보관해요. 내부 백업은 앱 삭제 시 함께 삭제돼요. 기기 분실에 대비하려면 파일 앱에서 외부 저장소나 지원하는 클라우드 폴더를 연결하세요. Android의 절전·강제 종료 상태에 따라 예약 작업이 늦어질 수 있어요.", color = Muted, fontSize = 12.sp)
        Button(onClick = { picker.launch(null) }, enabled = !busy) { Text("자동 백업 폴더 선택") }
        if (config.contains("tree")) OutlinedButton(onClick = { config.edit().remove("tree").remove("external_at").remove("error").apply() }, enabled = !busy) { Text("폴더 연결 해제") }
        OutlinedButton(onClick = { BackupJobs.enqueue(context); message = "백업을 예약했어요. 잠시 후 결과를 확인하세요." }, enabled = !busy) { Text("지금 백업") }
        Text("기기 내부 복원 지점", color = White, fontWeight = FontWeight.Bold)
        files.forEach { file -> TextButton(onClick = { restore = file }, enabled = !busy) { Text((if (file.name.startsWith("before")) "복원 전 보호 사본 · " else "자동 백업 · ") + timestamp(file.lastModified())) } }
        if (files.isEmpty()) Text("첫 자동 백업을 준비하고 있어요.", color = Muted, fontSize = 12.sp)
        message?.let { Text(it, color = Muted, fontSize = 12.sp) }
    }
    restore?.let { file ->
        var prepared by remember(file) { mutableStateOf<Result<PreparedBackup>?>(null) }
        LaunchedEffect(file) {
            prepared = withContext(Dispatchers.IO) { runCatching { file.bufferedReader().use { prepareBackup(BackupCodec.read(it)) } } }
        }
        AlertDialog(onDismissRequest = { if (!busy) restore = null }, title = { Text("기록을 복원할까요?") }, text = {
            val result = prepared
            when {
                result == null -> Text("백업을 확인하고 있어요…")
                result.isSuccess -> BackupPreviewText(result.getOrThrow().preview)
                else -> Text("복원할 수 없는 파일: ${result.exceptionOrNull()?.message}", color = Orange)
            }
        }, confirmButton = {
            TextButton(enabled = !busy && prepared?.isSuccess == true, onClick = {
                val values = prepared?.getOrNull()?.values ?: return@TextButton
                busy = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching {
                        BackupFiles.snapshot(context, safety = true)
                        BackupCodec.restore(context.getSharedPreferences("homebulkup", 0), values)
                    } }
                    busy = false; restore = null
                    result.onSuccess { (context as? Activity)?.recreate() }.onFailure { message = "복원 실패: ${it.message}" }
                }
            }) { Text(if (busy) "복원 중…" else "복원") }
        }, dismissButton = { TextButton(enabled = !busy, onClick = { restore = null }) { Text("취소") } })
    }
}
