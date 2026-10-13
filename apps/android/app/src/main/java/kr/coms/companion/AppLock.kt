package kr.coms.companion

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch

private val appLockKey = booleanPreferencesKey("app-lock")

@Composable
fun AppLockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val settings by context.operatorSettings.data.collectAsState(initial = null)
    val enabled = settings?.get(appLockKey) == true
    val states = rememberSaveableStateHolder()
    var unlocked by remember { mutableStateOf(false) }
    var credentialOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val keyguard = remember(context) { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        credentialOpen = false
        unlocked = result.resultCode == Activity.RESULT_OK
        if (!unlocked) error = "인증을 마쳐야 앱을 열 수 있어요"
    }

    DisposableEffect(lifecycle, enabled) {
        val activity = context as? Activity
        if (enabled) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val observer = LifecycleEventObserver { _, event ->
            if ((event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) && enabled && !credentialOpen) unlocked = false
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    androidx.compose.runtime.LaunchedEffect(enabled, unlocked) {
        if (enabled && !unlocked) focus.clearFocus(force = true)
    }

    val locked = enabled && !unlocked
    BackHandler(enabled = locked) { }
    if (settings == null) Surface(Modifier.fillMaxSize()) { Text("앱 잠금 확인 중…") }
    else if (!locked) states.SaveableStateProvider("operator-content") { content() }
    else Surface(Modifier.fillMaxSize().onPreviewKeyEvent { true }) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("COM’s Companion 잠금")
            if (error.isNotBlank()) Text(error)
            Button(onClick = {
                if (!keyguard.isDeviceSecure) { error = "휴대폰에 화면 잠금을 먼저 설정해 주세요"; return@Button }
                val intent = keyguard.createConfirmDeviceCredentialIntent("앱 잠금 해제", "휴대폰 화면 잠금으로 인증해 주세요")
                if (intent == null) { error = "기기 인증 화면을 열 수 없어요"; return@Button }
                credentialOpen = true
                launcher.launch(intent)
            }) { Text("휴대폰 잠금으로 열기") }
            if (!keyguard.isDeviceSecure) OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }) { Text("화면 잠금 설정 열기") }
        }
    }
}

@Composable
fun AppLockSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by context.operatorSettings.data.collectAsState(initial = null)
    val enabled = settings?.get(appLockKey) == true
    val keyguard = remember(context) { context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager }
    var error by remember { mutableStateOf("") }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) scope.launch { context.operatorSettings.edit { it[appLockKey] = false } }
        else error = "앱 잠금을 끄려면 기기 인증이 필요해요"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("앱 잠금")
        Text("앱을 다시 열 때 휴대폰 화면 잠금으로 인증합니다. 켜면 최근 앱 화면과 스크린샷도 보호합니다.")
        Switch(checked = enabled, enabled = settings != null, onCheckedChange = { checked ->
            error = ""
            if (checked) {
                if (keyguard.isDeviceSecure) scope.launch { context.operatorSettings.edit { it[appLockKey] = true } }
                else error = "휴대폰에 화면 잠금을 먼저 설정해 주세요"
            } else {
                val intent = keyguard.createConfirmDeviceCredentialIntent("앱 잠금 끄기", "휴대폰 화면 잠금으로 인증해 주세요")
                if (intent != null) launcher.launch(intent) else error = "기기 인증 화면을 열 수 없어요"
            }
        })
        if (error.isNotBlank()) Text(error)
    }
}
