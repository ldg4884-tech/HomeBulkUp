package kr.co.homebulkup

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.AtomicFile
import androidx.documentfile.provider.DocumentFile
import androidx.work.*
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class HomeBulkUpApplication : Application() {
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null && !key.startsWith("rest_") && !key.startsWith("active_")) BackupJobs.enqueue(this)
    }
    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("homebulkup", 0).registerOnSharedPreferenceChangeListener(listener)
        BackupJobs.schedule(this)
        AppAlerts.restore(this)
    }
}

internal object BackupJobs {
    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("daily-backup", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<AutoBackupWorker>(24, TimeUnit.HOURS).build())
        enqueue(context)
    }
    fun enqueue(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork("changed-backup", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<AutoBackupWorker>().setInitialDelay(5, TimeUnit.SECONDS).build())
    }
}

internal object BackupFiles {
    fun list(context: Context): List<File> = File(context.noBackupFilesDir, "snapshots").listFiles()?.filter { it.extension == "json" }?.sortedByDescending { it.lastModified() } ?: emptyList()
    @Synchronized fun snapshot(context: Context, safety: Boolean = false): File {
        val directory = File(context.noBackupFilesDir, "snapshots").apply { mkdirs() }
        val name = if (safety) "before-restore-${System.currentTimeMillis()}.json" else "auto-${LocalDate.now()}.json"
        val target = File(directory, name)
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            val data = BackupCodec.encode(context.getSharedPreferences("homebulkup", 0))
            if (!safety) BackupCodec.decode(data)
            stream.write(data.toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        list(context).filter { it.name.startsWith(if (safety) "before-restore-" else "auto-") }.drop(if (safety) 3 else 7).forEach { it.delete() }
        return target
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val config = applicationContext.getSharedPreferences("backup_config", 0)
        try {
            val snapshot = BackupFiles.snapshot(applicationContext)
            config.edit().putLong("local_at", System.currentTimeMillis()).apply()
            val tree = config.getString("tree", null)
            if (tree != null) {
                val directory = DocumentFile.fromTreeUri(applicationContext, Uri.parse(tree)) ?: error("백업 폴더를 열 수 없어요.")
                check(directory.canWrite()) { "백업 폴더 권한이 만료됐어요. 다시 선택하세요." }
                val file = directory.createFile("application/json", "homebulkup-auto-${System.currentTimeMillis()}.json") ?: error("백업 파일을 만들 수 없어요.")
                try {
                    val output = applicationContext.contentResolver.openOutputStream(file.uri, "w") ?: error("백업 파일을 열 수 없어요.")
                    output.use { stream -> snapshot.inputStream().use { it.copyTo(stream) } }
                    // Read back before declaring success or pruning earlier copies.
                    val saved = applicationContext.contentResolver.openInputStream(file.uri)?.bufferedReader()?.use { it.readText() } ?: error("백업 검증에 실패했어요.")
                    BackupCodec.decode(saved)
                } catch (e: Exception) { file.delete(); throw e }
                directory.listFiles().filter { it.name?.matches(Regex("homebulkup-auto-\\d+\\.json")) == true }
                    .sortedByDescending { it.name }.drop(7).forEach { it.delete() }
                config.edit().putLong("external_at", System.currentTimeMillis()).remove("error").apply()
            } else config.edit().remove("error").apply()
            return Result.success()
        } catch (e: Exception) {
            config.edit().putString("error", e.message ?: "자동 백업에 실패했어요.").apply()
            return if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }
}
