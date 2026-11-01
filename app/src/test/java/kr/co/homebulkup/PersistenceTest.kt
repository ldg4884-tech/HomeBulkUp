package kr.co.homebulkup

import android.app.Application
import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.testing.TestWorkerBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersistenceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("homebulkup", 0)
    @Before fun reset() {
        prefs.edit().clear().commit()
        context.getSharedPreferences("backup_config", 0).edit().clear().commit()
        File(context.noBackupFilesDir, "snapshots").deleteRecursively()
    }
    @Test fun stableIdsAndDraftInputsSurviveReorderingAndReload() {
        val routine = program.toMutableList()
        val exercise = routine[0].exercises[0]
        val draft = TrainingSet("2026-11-01", exercise.id, exercise.name, 1, "12.5", "9", false)
        TrainingStore.save(prefs, listOf(draft))
        routine[0] = routine[0].copy(exercises = routine[0].exercises.reversed())
        saveWorkoutProgram(prefs, routine)
        val reloaded = loadWorkoutProgram(prefs)
        assertEquals(exercise.id, reloaded[0].exercises.last().id)
        assertEquals(draft, TrainingStore.load(prefs, reloaded).single())
    }
    @Test fun restTimerSurvivesProcessReloadAndHiddenDialog() {
        val running = RestTimer().fresh(1000L).copy(visible = false)
        RestTimerStore.save(prefs, running)
        val restored = RestTimerStore.load(prefs, 31000L)
        assertEquals(60, restored.remaining); assertTrue(restored.running); assertFalse(restored.visible)
        val paused = restored.pause(41000L)
        RestTimerStore.save(prefs, paused)
        assertEquals(50, RestTimerStore.load(prefs, 100000L).remaining)
        RestTimerStore.save(prefs, running)
        val expired = RestTimerStore.load(prefs, 200000L)
        assertEquals(0, expired.remaining); assertFalse(expired.running)
    }
    @Test fun rirPlansAndWellnessSurviveBackupRoundTrip() {
        val record = TrainingSet("2026-11-01", "curl", "덤벨 컬", 1, "10", "12", true, kind = "warmup", rir = 2)
        TrainingStore.save(prefs, listOf(record))
        PlanStore.saveWellness(prefs, Wellness("2026-11-01", "7.5", 4, "어깨", "가벼운 운동"))
        PlanStore.saveSchedule(prefs, SchedulePlan(listOf(2, 3, 5, 6), "19:30", true))
        PlanStore.saveRecovery(prefs, RecoveryPlan("2026-11-01", "light"))
        val values = prepareBackup(BackupCodec.encode(prefs))
        assertEquals(1, values.preview.wellness)
        prefs.edit().clear().commit(); BackupCodec.restore(prefs, values.values)
        assertEquals(record, TrainingStore.load(prefs, program).single())
        assertEquals("7.5", PlanStore.wellness(prefs).single().sleep)
        assertEquals("19:30", PlanStore.schedule(prefs).time)
        assertEquals("light", PlanStore.recovery(prefs)!!.mode)
    }
    @Test fun legacyLogsMigrateOnceWithoutInventingCompletedSets() {
        prefs.edit().putString("exercise_logs", "{\"2026-10-31-0-0-set-1-weight\":\"10\",\"2026-10-31-0-0-set-1-reps\":\"12\"}").commit()
        val migrated = TrainingStore.load(prefs, program).single()
        assertEquals(program[0].exercises[0].name, migrated.name); assertFalse(migrated.done)
        prefs.edit().putString("exercise_logs", "{}").commit()
        assertEquals(migrated, TrainingStore.load(prefs, program).single())
    }
    @Test fun localSnapshotRestoresAllTypedDataAndProtectsCurrentData() {
        prefs.edit().putString("weight", "74").putInt("active_day", 2).putBoolean("gear_pullup", false).commit()
        val file = BackupFiles.snapshot(context)
        val values = BackupCodec.decode(file.readText())
        prefs.edit().putString("weight", "75").commit()
        BackupFiles.snapshot(context, safety = true)
        BackupCodec.restore(prefs, values)
        assertEquals("74", prefs.getString("weight", null)); assertEquals(2, prefs.getInt("active_day", 0))
        assertFalse(prefs.getBoolean("gear_pullup", true))
        assertTrue(BackupFiles.list(context).any { it.name.startsWith("before-restore-") && it.readText().contains("75") })
    }
    @Test fun damagedCurrentDataCanStillBeProtectedBeforeRestore() {
        prefs.edit().putString("weight", "invalid").commit()
        val safety = BackupFiles.snapshot(context, safety = true)
        assertTrue(safety.readText().contains("invalid"))
        BackupCodec.restore(prefs, mapOf("weight" to "74"))
        assertEquals("74", prefs.getString("weight", null))
    }
    @Test fun corruptBackupNeverReplacesCurrentPreferences() {
        prefs.edit().putString("weight", "75").commit()
        val raw = JSONObject().put("app", "HomeBulkUp").put("backupVersion", 1).put("preferences", JSONObject().put("active_day", "wrong")).toString()
        runCatching { BackupCodec.restore(prefs, BackupCodec.decode(raw)) }.onSuccess { fail("Must reject corrupt backup") }
        assertEquals("75", prefs.getString("weight", null))
    }
    @Test fun safetyCopiesHaveBoundedRetention() {
        prefs.edit().putString("weight", "74").commit()
        repeat(5) { index ->
            val file = File(context.noBackupFilesDir, "snapshots/before-restore-old-$index.json")
            file.parentFile!!.mkdirs(); file.writeText("{}"); file.setLastModified(index.toLong() + 1)
        }
        BackupFiles.snapshot(context, safety = true)
        assertEquals(3, BackupFiles.list(context).count { it.name.startsWith("before-restore-") })
    }
    @Test fun backupWorkerProducesValidatedSnapshotAndSuccessStatus() {
        prefs.edit().putString("weight", "74").commit()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = TestWorkerBuilder.from(context, AutoBackupWorker::class.java, executor).build()
            assertEquals(ListenableWorker.Result.success(), worker.doWork())
            assertEquals("74", BackupCodec.decode(BackupFiles.list(context).single().readText())["weight"])
            assertTrue(context.getSharedPreferences("backup_config", 0).getLong("local_at", 0) > 0)
        } finally { executor.shutdownNow() }
    }
    @Test fun folderBackupCanBeReadBackAndRestored() {
        val authority = "kr.co.homebulkup.test.backups"
        val info = android.content.pm.ProviderInfo().apply {
            this.authority = authority; grantUriPermissions = true; exported = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"; writePermission = readPermission
        }
        val provider = BackupDocumentsProvider().apply { attachInfo(this@PersistenceTest.context, info) }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(authority, provider)
        val tree = android.provider.DocumentsContract.buildTreeDocumentUri(authority, "root")
        context.grantUriPermission(context.packageName, tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString("weight", "74").commit()
        val config = context.getSharedPreferences("backup_config", 0)
        config.edit().putString("tree", tree.toString()).commit()
        // Robolectric does not model persisted SAF URI grants. Give only this test
        // provider the URI permission a real OpenDocumentTree selection supplies.
        val grantedContext = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun checkCallingOrSelfUriPermission(uri: android.net.Uri, modeFlags: Int): Int =
                if (uri.authority == authority) android.content.pm.PackageManager.PERMISSION_GRANTED else super.checkCallingOrSelfUriPermission(uri, modeFlags)
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = TestWorkerBuilder.from(grantedContext, AutoBackupWorker::class.java, executor).build()
            val result = worker.doWork()
            assertEquals(config.getString("error", null) + " | " + org.robolectric.shadows.ShadowLog.getLogs().joinToString { it.msg + " " + it.throwable?.stackTraceToString() }, ListenableWorker.Result.success(), result)
            assertTrue(config.getLong("external_at", 0L) > 0)
            val document = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, tree)!!.listFiles().single()
            val raw = context.contentResolver.openInputStream(document.uri)!!.bufferedReader().use { it.readText() }
            assertEquals("74", BackupCodec.decode(raw)["weight"])
        } finally { executor.shutdownNow() }
    }
    @Test fun lostFolderAccessKeepsLocalSnapshotAndReportsFailure() {
        prefs.edit().putString("weight", "74").commit()
        context.getSharedPreferences("backup_config", 0).edit().putString("tree", "content://missing/tree/unavailable").commit()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = TestWorkerBuilder.from(context, AutoBackupWorker::class.java, executor).build()
            assertEquals(ListenableWorker.Result.retry(), worker.doWork())
            assertTrue(BackupFiles.list(context).isNotEmpty())
            assertNotNull(context.getSharedPreferences("backup_config", 0).getString("error", null))
        } finally { executor.shutdownNow() }
    }
}
