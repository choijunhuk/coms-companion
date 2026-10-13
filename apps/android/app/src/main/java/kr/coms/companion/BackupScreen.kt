package kr.coms.companion

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date

private val lastBackupKey = longPreferencesKey("last-backup-at")

class BackupUiState : ViewModel() {
    val password = mutableStateOf("")
    val restored = mutableStateOf<ByteArray?>(null)
    val preview = mutableStateOf<BackupPreview?>(null)
    val busy = mutableStateOf(false)
    val safetyFile = mutableStateOf<Uri?>(null)

    override fun onCleared() { restored.value?.fill(0) }
}

@Composable
fun BackupScreen(database: AppDatabase, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state: BackupUiState = viewModel()
    val settings by context.operatorSettings.data.collectAsState(initial = null)
    var password by state.password
    var restored by state.restored
    var preview by state.preview
    var busy by state.busy
    var savedSafetyFile by state.safetyFile

    fun runTask(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage(e.message ?: "파일 처리에 실패했어요") }
            finally { busy = false }
        }
    }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) runTask {
            val data = BackupCrypto.encrypt(Backup.capture(database), password.toCharArray())
            writeFile(context, uri, data)
            context.operatorSettings.edit { it[lastBackupKey] = System.currentTimeMillis() }
            password = ""
            onMessage("암호화 백업 파일을 저장했어요: $uri")
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runTask {
            val data = withContext(Dispatchers.IO) { readFile(context, uri) }
            val plain = BackupCrypto.decrypt(data, password.toCharArray())
            try {
                preview = Backup.preview(database, plain)
                restored = plain
                savedSafetyFile = null
            } catch (e: Exception) { plain.fill(0); throw e }
        }
    }
    val safety = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val plain = restored
        if (uri != null && plain != null) runTask {
            withSyncPausedForRestore {
                Backup.preview(database, plain)
                val current = Backup.capture(database)
                val encrypted = BackupCrypto.encrypt(current, password.toCharArray())
                current.fill(0)
                writeFile(context, uri, encrypted)
                savedSafetyFile = uri
                // Credentials are device-bound; restored outbox entries need an explicit same-server reconnect.
                context.operatorSettings.edit {
                    it.remove(SettingsKeys.serverUrl)
                    it.remove(SettingsKeys.adminToken)
                    it[SettingsKeys.syncEnabled] = false
                }
                scheduleSync(context, false)
                Backup.restore(database, plain)
            }
            plain.fill(0)
            restored = null
            preview = null
            password = ""
            onMessage("복원했어요. 기존 데이터 복구 파일: $uri. 서버 연결은 다시 확인해 주세요")
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("암호화 백업·복원") }
        item { Text("마지막 백업: ${settings?.get(lastBackupKey)?.let { DateFormat.getDateTimeInstance().format(Date(it)) } ?: "기록 없음"}") }
        item { Text("백업에는 회원, 행사, 회비, 연락 기록과 개인 초대 링크가 들어갑니다. 암호를 잊으면 복원할 수 없어요. 서버 토큰은 포함하지 않습니다.") }
        item {
            OutlinedTextField(password, { password = it; restored?.fill(0); restored = null; preview = null },
                label = { Text("백업 암호 (10자 이상)") }, visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
        }
        item { Button(onClick = { export.launch("coms-companion-${System.currentTimeMillis()}.comsbak") }, enabled = !busy && password.length >= 10) { Text("새 백업 파일 저장") } }
        item { OutlinedButton(onClick = { import.launch(arrayOf("application/octet-stream", "*/*")) }, enabled = !busy && password.isNotEmpty()) { Text("백업 파일 선택·미리보기") } }
        preview?.let { info ->
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("복원 미리보기 · ${DateFormat.getDateTimeInstance().format(Date(info.createdAt))}")
                    Text("서버 출처: ${info.serverOrigin.ifBlank { "연결 기록 없음" }}")
                    info.counts.forEach { (table, count) -> Text("$table: $count") }
                    Text("복원하면 현재 앱 데이터가 위 내용으로 바뀝니다. 기존 데이터는 별도 암호화 복구 파일로 먼저 저장됩니다. 서버 연결은 해제됩니다.")
                }
            }
            item { Button(onClick = { safety.launch("coms-companion-before-restore-${System.currentTimeMillis()}.comsbak") }, enabled = !busy && restored != null && password.length >= 10) { Text("기존 데이터 저장 후 복원") } }
        }
        savedSafetyFile?.let { uri -> item { Text("기존 데이터 복구 파일: $uri") } }
        item { OutlinedButton(onClick = { restored?.fill(0); restored = null; preview = null; password = ""; onBack() }, enabled = !busy) { Text("뒤로") } }
    }
}

private suspend fun writeFile(context: android.content.Context, uri: Uri, data: ByteArray) = withContext(Dispatchers.IO) {
    requireNotNull(context.contentResolver.openOutputStream(uri, "rwt")) { "저장할 파일을 열지 못했어요" }.use { output ->
        output.write(data)
        output.flush()
    }
}

private fun readFile(context: android.content.Context, uri: Uri): ByteArray {
    val limit = 32 * 1024 * 1024 + 64
    return requireNotNull(context.contentResolver.openInputStream(uri)) { "백업 파일을 열지 못했어요" }.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            require(output.size() + count <= limit) { "백업 파일이 너무 커요" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }
}
