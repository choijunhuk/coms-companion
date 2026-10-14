package kr.coms.companion

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kakao.sdk.common.KakaoSdk
import com.kakao.sdk.share.ShareClient
import com.kakao.sdk.template.model.Link
import com.kakao.sdk.template.model.TextTemplate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    private var launchDraftId by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchDraftId = intent.getStringExtra("draftId")
        if (BuildConfig.KAKAO_APP_KEY.isNotBlank()) KakaoSdk.init(this, BuildConfig.KAKAO_APP_KEY)
        val dao = AppDatabase.get(this).dao()
        setContent { AppLockGate { CompanionApp(dao, launchDraftId) { launchDraftId = null } } }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        launchDraftId = intent.getStringExtra("draftId")
    }
}

private val light = lightColorScheme(
    background = Color(0xFFF6F7F9), surface = Color.White, onSurface = Color(0xFF17212B),
    onBackground = Color(0xFF17212B), primary = Color(0xFF0F766E), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F5F2), onPrimaryContainer = Color(0xFF17212B),
    secondary = Color(0xFF0F766E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F5F2), onSecondaryContainer = Color(0xFF17212B),
    tertiary = Color(0xFF0F766E), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE8F5F2), onTertiaryContainer = Color(0xFF17212B),
    surfaceTint = Color(0xFF0F766E), inversePrimary = Color(0xFF67D5C5),
    surfaceVariant = Color(0xFFE8F5F2), onSurfaceVariant = Color(0xFF536171), outline = Color(0xFFDCE2E8),
)
private val dark = darkColorScheme(
    background = Color(0xFF10161C), surface = Color(0xFF18212A), onSurface = Color(0xFFECF2F7),
    onBackground = Color(0xFFECF2F7), primary = Color(0xFF67D5C5), onPrimary = Color(0xFF10201F),
    primaryContainer = Color(0xFF193B38), onPrimaryContainer = Color(0xFFECF2F7),
    secondary = Color(0xFF67D5C5), onSecondary = Color(0xFF10201F),
    secondaryContainer = Color(0xFF193B38), onSecondaryContainer = Color(0xFFECF2F7),
    tertiary = Color(0xFF67D5C5), onTertiary = Color(0xFF10201F),
    tertiaryContainer = Color(0xFF193B38), onTertiaryContainer = Color(0xFFECF2F7),
    surfaceTint = Color(0xFF67D5C5), inversePrimary = Color(0xFF0F766E),
    surfaceVariant = Color(0xFF193B38), onSurfaceVariant = Color(0xFFADBCCA), outline = Color(0xFF344451),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CompanionApp(dao: AppDao, launchDraftId: String?, onDraftLaunched: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val syncEngine = remember(context) { SyncEngine(context) }
    val preferences by remember(context) { context.operatorSettings.data }.collectAsState(emptyPreferences())
    val theme = preferences[SettingsKeys.theme] ?: "밝게"
    val semester = preferences[SettingsKeys.semester] ?: "학기 미설정"
    val serverUrl = preferences[SettingsKeys.serverUrl] ?: ""
    val adminToken = preferences[SettingsKeys.adminToken] ?: ""
    val syncEnabled = preferences[SettingsKeys.syncEnabled] ?: false
    val isDark = theme == "어둡게" || theme == "시스템" && androidx.compose.foundation.isSystemInDarkTheme()
    val members by dao.members().collectAsState(emptyList())
    val events by dao.events().collectAsState(emptyList())
    val rsvps by dao.rsvps().collectAsState(emptyList())
    val charges by dao.charges().collectAsState(emptyList())
    val ledger by dao.ledger().collectAsState(emptyList())
    val duties by dao.duties().collectAsState(emptyList())
    val templates by dao.templates().collectAsState(emptyList())
    val contacts by dao.contacts().collectAsState(emptyList())
    val reminders by dao.reminders().collectAsState(emptyList())
    val drafts by dao.drafts().collectAsState(emptyList())
    val invites by dao.invites().collectAsState(emptyList())
    val clubConfig by dao.clubConfig().collectAsState(null)
    val pendingMutations by dao.pendingMutations().collectAsState(emptyList())
    val onlineStateExists = events.any { it.serverVersion > 0 || it.pendingOperationId.isNotBlank() } || invites.isNotEmpty()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var settings by rememberSaveable { mutableStateOf(false) }
    val savedScreens = rememberSaveableStateHolder()
    var editorOpen by remember { mutableStateOf(false) }
    var pendingNavigation by remember { mutableStateOf<Int?>(null) }
    var discardEpoch by rememberSaveable { mutableIntStateOf(0) }
    fun navigate(destination: Int) {
        if (editorOpen) pendingNavigation = destination
        else if (destination == 5) settings = true
        else { settings = false; if (destination in 0..4) tab = destination }
    }
    var contactSelection by rememberSaveable { mutableStateOf(listOf<String>()) }
    var requestedDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(launchDraftId) { if (launchDraftId != null) { requestedDraftId = launchDraftId; tab = 4; settings = false; onDraftLaunched() } }
    var message by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) { message?.let { snackbar.showSnackbar(it); message = null } }
    MaterialTheme(colorScheme = if (isDark) dark else light, typography = Typography()) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(title = {
                    Column {
                        Text(if (settings) "설정" else if (tab == 0) "COM’s Companion" else listOf("홈", "회원", "행사", "회비", "연락")[tab], fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (!settings && tab == 0) Text(semester, fontSize = 14.sp)
                    }
                }, navigationIcon = { if (settings) IconButton(onClick = { navigate(tab) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } }, actions = {
                    if (!settings) IconButton(onClick = { navigate(5) }) { Icon(Icons.Default.Settings, "설정") }
                })
            },
            bottomBar = {
                if (!settings) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    val icons = listOf(Icons.Default.Home, Icons.Default.People, Icons.Default.Event, Icons.Default.Payments, Icons.Default.Sms)
                    listOf("홈", "회원", "행사", "회비", "연락").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { if (tab != index) navigate(index) }, icon = { Icon(icons[index], label) }, label = { Text(label) }, alwaysShowLabel = true, colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.surfaceVariant, unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant, unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                    }
                }
            },
        ) { inset ->
            Box(Modifier.padding(inset).fillMaxSize()) {
                if (settings) savedScreens.SaveableStateProvider("settings") { SettingsScreen(AppDatabase.get(context), clubConfig, members, events, charges, ledger, pendingMutations, theme, semester, serverUrl, adminToken, syncEnabled, onlineStateExists, discardEpoch, onEditingChange = { editorOpen = it }, onMessage = { message = it }, onOpenTemplates = { navigate(4) }, onSaveClub = { name, account, actor ->
                    require(name.isNotBlank() && actor.isNotBlank()) { "동아리명과 담당자를 입력해 주세요" }
                    dao.put((dao.clubConfigSnapshot() ?: ClubConfig()).copy(clubName = name.trim(), accountGuide = account.trim(), actor = actor.trim()))
                    message = "동아리 정보를 저장했어요"
                }, onManualSync = { syncEngine.syncAll(); message = "온라인 응답과 대기 작업을 동기화했어요" }, onSyncEnabled = { enabled ->
                    val old = context.operatorSettings.data.first()[SettingsKeys.syncEnabled] ?: false
                    context.operatorSettings.edit { it[SettingsKeys.syncEnabled] = enabled }
                    try { scheduleSync(context, enabled) }
                    catch (e: Exception) { context.operatorSettings.edit { it[SettingsKeys.syncEnabled] = old }; throw e }
                }, onTheme = { context.operatorSettings.edit { preferences -> preferences[SettingsKeys.theme] = it } }, onSemester = { context.operatorSettings.edit { preferences -> preferences[SettingsKeys.semester] = it } }, onConnection = { url, token ->
                    val storedUrl = context.operatorSettings.data.first()[SettingsKeys.serverUrl] ?: ""
                    val persistedOrigin = dao.clubConfigSnapshot()?.serverOrigin.orEmpty()
                    val boundOrigin = persistedOrigin.ifBlank { storedUrl }
                    require((persistedOrigin.isBlank() && dao.onlineStateCount() == 0) || url == boundOrigin) { "온라인 기록이 연결된 서버 주소만 사용할 수 있어요. 같은 주소의 토큰은 교체할 수 있어요" }
                    context.operatorSettings.edit { preferences -> preferences[SettingsKeys.serverUrl] = url; preferences[SettingsKeys.adminToken] = token }
                    message = "온라인 연결 설정을 이 기기에 저장했어요"
                }) }
                else savedScreens.SaveableStateProvider("tab-$tab") { when (tab) {
                    0 -> HomeScreen(members, events, charges, ledger, duties, reminders, discardEpoch, onEditingChange = { editorOpen = it }, onTab = { navigate(it) }, onAddDuty = { dao.put(it); message = "할 일을 저장했어요" }, onDutyDone = { scope.launch { try { dao.updateDuty(it.id, !it.done) } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "할 일 변경 실패 · ${e.message}" } } })
                    1 -> MembersScreen(members, charges, ledger, events, rsvps, contacts, discardEpoch, onEditingChange = { editorOpen = it }, onSave = { member ->
                        val currentMembers = dao.memberSnapshot()
                        val duplicate = currentMembers.any { it.id != member.id && normalizePhone(it.phone).isNotEmpty() && normalizePhone(it.phone) == normalizePhone(member.phone) }
                        syncEngine.saveMember(member)
                        if (member.status == "탈퇴") {
                            try {
                                if (serverUrl.isNotBlank() && adminToken.isNotBlank()) syncEngine.syncAll()
                                val pending = dao.pendingMutations().first().any { it.kind == "revoke-member" && it.targetId == member.id }
                                message = if (pending) "탈퇴를 저장했어요. 개인 링크 취소는 대기·오류 상태를 확인해 주세요" else "탈퇴를 저장했어요. 대기 중인 개인 링크 취소는 없어요"
                            }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "탈퇴를 저장하고 개인 링크 취소를 대기함에 넣었어요. 연결 후 다시 동기화해 주세요" }
                        } else message = if (duplicate) "저장했어요. 같은 번호의 회원이 있어요. 자동 병합하지 않았어요" else "회원 정보를 저장했어요"
                    }, onImport = { rows ->
                        val currentMembers = dao.memberSnapshot()
                        val keys = currentMembers.map { normalizePhone(it.phone) }.filter(String::isNotBlank).toMutableSet()
                        val valid = rows.filter { it.issue == null }
                        require(valid.isNotEmpty()) { "가져올 정상 행이 없어요" }
                        valid.forEach { row ->
                            require(row.name.isNotBlank() && (row.phone.isBlank() || validPhone(row.phone))) { "${row.row}행 내용을 다시 확인해 주세요" }
                            val phone = normalizePhone(row.phone)
                            require(phone.isBlank() || keys.add(phone)) { "${row.row}행 번호가 현재 명단 또는 파일에서 중복됐어요. 미리보기를 다시 열어 주세요" }
                        }
                        dao.importMembers(valid.map { Member(name = it.name, phone = it.phone, cohort = it.cohort, groupName = it.group) })
                        message = "회원 ${valid.size}명을 저장했어요"
                    }, onBulkChange = { ids, status, group ->
                        syncEngine.bulkUpdateMembers(ids, status, group)
                        if (status == "탈퇴") {
                            try {
                                if (serverUrl.isNotBlank() && adminToken.isNotBlank()) syncEngine.syncAll()
                                val pending = dao.pendingMutations().first().any { it.kind == "revoke-member" && it.targetId in ids }
                                message = if (pending) "탈퇴를 저장했어요. 개인 링크 취소는 대기·오류 상태를 확인해 주세요" else "선택 회원의 탈퇴를 저장했어요. 대기 중인 링크 취소는 없어요"
                            }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "탈퇴를 저장하고 링크 취소를 대기함에 넣었어요" }
                        } else message = "선택한 ${ids.size}명 정보를 변경했어요"
                    }, onPrepareContact = { ids -> contactSelection = ids.toList(); tab = 4 }, onMessage = { message = it })
                    2 -> EventsScreen(events, members, rsvps, duties, charges, ledger, invites, pendingMutations, discardEpoch, onlineReady = serverUrl.isNotBlank() && adminToken.isNotBlank(), onEditingChange = { editorOpen = it }, onMessage = { message = it }, onAddDuty = { dao.put(it); message = "행사 준비 할 일을 저장했어요" }, onSave = { event ->
                        if (events.any { it.id == event.id }) require(dao.updateEventWithPoll(event.id, event.title, event.whenText, event.place, event.details, event.deadline, event.capacity, event.feeWon, event.cancelled, event.pollOptionsJson, event.pollDeadline) == 1) { "게시 중인 행사는 수정할 수 없어요. 게시 결과를 먼저 확인해 주세요" } else dao.put(event)
                        message = "행사를 저장했어요"
                    }, onRsvp = { rsvp ->
                        val eventState = dao.eventById(rsvp.eventId)
                        require(eventState != null && !eventState.cancelled && eventState.pendingOperationId.isBlank()) { "취소·게시 중인 행사 응답은 수정할 수 없어요" }
                        if (eventState.serverVersion == 0) {
                            val prior = dao.rsvpsFor(rsvp.eventId).firstOrNull { it.memberId == rsvp.memberId }
                            if (prior == null) dao.put(rsvp) else dao.updateRsvp(prior.id, rsvp.response, rsvp.attendance)
                            message = "운영자 수동 응답을 저장했어요"
                        } else {
                            syncEngine.queueResponse(rsvp.eventId, rsvp.memberId, rsvp.response)
                            try { syncEngine.syncAll(); message = "서버 응답을 확인했어요" }
                            catch (e: CancellationException) { throw e }
                            catch (_: Exception) { message = "응답 변경을 대기함에 저장했어요. 연결 후 다시 동기화해 주세요" }
                        }
                    }, onPublish = { event -> scope.launch {
                        try { syncEngine.publishEvent(event.id); message = "행사와 수동 응답의 서버 저장을 확인했어요" }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { message = "게시 결과 미확인 · ${e.message ?: "같은 작업으로 재시도해 주세요"}" }
                    } }, onLink = { event, member -> scope.launch {
                        try {
                            syncEngine.createLink(event.id, member?.id)
                            message = "링크가 준비됐어요. 개인 링크는 해당 회원에게만 전달해 주세요"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "링크 실패 · ${e.message ?: "연결을 확인하고 다시 시도해 주세요"}" }
                    } }, onRefresh = { event -> scope.launch {
                        try {
                            syncEngine.syncAll()
                            message = "서버 응답을 가져왔어요. 납부는 별도 확인이 필요해요"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "응답 가져오기 실패 · ${e.message ?: "연결을 확인하고 다시 시도해 주세요"}" }
                    } })
                    3 -> FeesScreen(charges, members, ledger, events, pendingMutations, clubConfig, discardEpoch, onEditingChange = { editorOpen = it }, onMessage = { message = it }, onCharge = { dao.put(it); message = "회비 항목을 저장했어요" }, onEntry = { dao.put(it.copy(actor = clubConfig?.actor ?: "운영자")); message = "원장 기록을 저장했어요" })
                    4 -> ContactScreen(members, events, charges, ledger, rsvps, invites, pendingMutations, templates, contacts, reminders, drafts, clubConfig, semester, dao, contactSelection, requestedDraftId, discardEpoch, onEditingChange = { editorOpen = it }, onSelectionConsumed = { contactSelection = emptyList() }, onRequestedDraftConsumed = { requestedDraftId = null }, onTemplate = { template -> if (templates.any { it.id == template.id }) dao.updateTemplate(template.id, template.title, template.body) else dao.put(template); message = "템플릿을 저장했어요" }, onMessage = { message = it })
                } }
            }
        }
        if (pendingNavigation != null) AlertDialog(onDismissRequest = { pendingNavigation = null }, title = { Text("작성 중인 내용") }, text = { Text("화면을 떠나면 저장하지 않은 변경사항을 버립니다. 계속 작성할 수 있어요") }, confirmButton = { TextButton(onClick = { val destination = pendingNavigation!!; pendingNavigation = null; discardEpoch++; editorOpen = false; navigate(destination) }) { Text("변경사항 버리기") } }, dismissButton = { TextButton(onClick = { pendingNavigation = null }) { Text("계속 작성") } })
    }
}

@Composable private fun TrackEditor(open: Boolean, onChange: (Boolean) -> Unit) {
    DisposableEffect(open) {
        onChange(open)
        onDispose { if (open) onChange(false) }
    }
}
@Composable private fun DiscardOnEpoch(epoch: Int, discard: () -> Unit) {
    var seen by rememberSaveable { mutableIntStateOf(epoch) }
    LaunchedEffect(epoch) { if (seen != epoch) { seen = epoch; discard() } }
}
@Composable private fun DiscardPrompt(open: Boolean, onKeep: () -> Unit, onDiscard: () -> Unit) {
    if (open) AlertDialog(onDismissRequest = onKeep, title = { Text("변경사항 버리기") }, text = { Text("저장하지 않은 입력이 사라집니다. 계속 작성할 수 있어요") }, confirmButton = { TextButton(onClick = onDiscard) { Text("변경사항 버리기") } }, dismissButton = { TextButton(onClick = onKeep) { Text("계속 작성") } })
}

@Composable private fun Page(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), state = rememberLazyListState(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
    }
}

@Composable private fun Section(title: String) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
@Composable private fun Hint(text: String) { Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
private fun eventPhase(event: Event): String {
    if (event.cancelled) return "취소"
    val date = runCatching { java.time.OffsetDateTime.parse(event.whenText).toLocalDate() }.getOrNull() ?: return "예정"
    return when {
        date.isAfter(java.time.LocalDate.now()) -> "예정"
        date.isBefore(java.time.LocalDate.now()) -> "종료"
        else -> "진행"
    }
}
private fun paymentRequestSummary(request: JSONObject): String {
    val requested = "%,d원".format(request.optLong("amount"))
    return when (request.optString("status")) {
        "pending" -> "요청 $requested · 입금 확인 대기 · 은행 기록 확인 전"
        "rejected" -> "요청 $requested · 반려 · 요청 기록 보존"
        "confirmed" -> {
            val review = request.optJSONObject("review")
            val confirmed = if (review != null && review.has("confirmedAmount") && review.optLong("confirmedAmount") > 0) "%,d원".format(review.optLong("confirmedAmount")) else "확인액 영수증 참조"
            "요청 $requested · 은행 확인 $confirmed · 확인 완료"
        }
        else -> "요청 $requested · 상태 확인 필요"
    }
}
private fun JSONObject?.displayText(key: String, fallback: String): String =
    this?.takeUnless { it.isNull(key) }?.optString(key)?.takeUnless { it.isBlank() || it == "null" } ?: fallback
private fun pollOptions(event: Event): List<Pair<String, String>> = runCatching {
    val values = JSONArray(event.pollOptionsJson)
    (0 until values.length()).map { index -> values.getJSONObject(index).let { it.getString("id") to it.getString("label") } }
}.getOrDefault(emptyList())
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CardLine(title: String, detail: String, onClick: (() -> Unit)? = null) {
    val content: @Composable () -> Unit = {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text(detail, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (onClick == null) Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) { content() }
    else Card(onClick = onClick, shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) { content() }
}
@Composable private fun Action(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(label) }
}

@Composable private fun HomeScreen(members: List<Member>, events: List<Event>, charges: List<Charge>, ledger: List<LedgerEntry>, duties: List<Duty>, reminders: List<Reminder>, discardEpoch: Int, onEditingChange: (Boolean) -> Unit, onTab: (Int) -> Unit, onAddDuty: suspend (Duty) -> Unit, onDutyDone: (Duty) -> Unit) {
    var addDuty by remember { mutableStateOf(false) }
    DiscardOnEpoch(discardEpoch) { addDuty = false }
    TrackEditor(addDuty, onEditingChange)
    Page {
        Section("오늘 처리할 일")
        val pending = duties.filterNot(Duty::done)
        val paymentCount = events.sumOf { event -> runCatching { JSONObject(event.responsesJson).optJSONArray("paymentRequests") }.getOrNull()?.let { requests -> (0 until requests.length()).count { requests.optJSONObject(it)?.optString("status") == "pending" } } ?: 0 }
        val dueEvents = events.filter { event -> !event.cancelled && runCatching { java.time.OffsetDateTime.parse(event.deadline).toInstant() }.getOrNull()?.let { it.isAfter(java.time.Instant.now()) && it.isBefore(java.time.Instant.now().plusSeconds(86400)) } == true }
        val missed = reminders.filter { it.state.contains("거부") || it.state.contains("지남") || it.state == "대기" && it.atMillis < System.currentTimeMillis() }
        var shown = 0
        if (paymentCount > 0 && shown++ < 3) CardLine("입금 확인 요청 $paymentCount 건", "회원의 주장 기록 · 은행 확인 후 원장 처리", onClick = { onTab(3) })
        if (missed.isNotEmpty() && shown++ < 3) CardLine("연락 알림 ${missed.size}건 확인", "권한 거부·지연된 알림 포함 · 자동 발송 없음", onClick = { onTab(4) })
        if (dueEvents.isNotEmpty() && shown++ < 3) CardLine("응답 마감 임박 ${dueEvents.size}건", "행사와 마지막 응답 확인", onClick = { onTab(2) })
        pending.take((3 - shown).coerceAtLeast(0)).forEach { duty -> CardLine(duty.title, "행사 준비 할 일 · 완료하려면 탭", onClick = { onDutyDone(duty) }) }
        if (shown == 0 && pending.isEmpty()) Hint("오늘 확인할 일이 없어요")
        if (pending.size > (3 - shown).coerceAtLeast(0)) TextButton(onClick = { addDuty = true }) { Text("미완료 할 일 ${pending.size}건 보기") }
        if (members.isEmpty() && events.isEmpty()) {
            Hint("이 기기에서 첫 업무를 시작해 보세요")
            Action("첫 회원 추가", { onTab(1) })
            OutlinedButton(onClick = { onTab(2) }, modifier = Modifier.fillMaxWidth()) { Text("행사 만들기") }
        } else {
            Section("업무 현황")
            Hint("활동 회원 ${members.count { it.status == "활동" }}명 · 입금 확인 대기 $paymentCount 건 · 회비 항목 ${charges.size}개")
            val upcoming = events.filterNot(Event::cancelled).take(2)
            Section("다가오는 행사")
            if (upcoming.isEmpty()) Hint("등록된 행사가 없어요") else upcoming.forEach { CardLine(it.title, "${it.whenText.ifBlank { "일시 미정" }} · ${it.place.ifBlank { "장소 미정" }}", onClick = { onTab(2) }) }
        }
        Section("운영 할 일")
        Action("할 일 추가", { addDuty = true })
        duties.forEach { duty -> CardLine(if (duty.done) "✓ ${duty.title}" else "○ ${duty.title}", if (duty.done) "완료" else "미완료", onClick = { onDutyDone(duty) }) }
    }
    if (addDuty) DutyDialog(onDismiss = { addDuty = false }, onSave = onAddDuty)
}

@Composable private fun DutyDialog(onDismiss: () -> Unit, onSave: suspend (Duty) -> Unit) {
    val scope = rememberCoroutineScope()
    var title by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    fun requestDismiss() { if (title.isNotBlank()) discard = true else onDismiss() }
    AlertDialog(onDismissRequest = { if (!saving) requestDismiss() }, title = { Text("할 일 추가") }, text = { Column { OutlinedTextField(title, { title = it }, label = { Text("할 일") }); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; scope.launch { try { commitSave({ onSave(Duty(title = title.trim())) }, onDismiss, { error = it }) } finally { saving = false } } } }, enabled = title.isNotBlank() && !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = ::requestDismiss, enabled = !saving) { Text("취소") } })
    DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; onDismiss() })
}

@Composable private fun MembersScreen(members: List<Member>, charges: List<Charge>, ledger: List<LedgerEntry>, events: List<Event>, rsvps: List<Rsvp>, contacts: List<ContactRecord>, discardEpoch: Int, onEditingChange: (Boolean) -> Unit, onSave: suspend (Member) -> Unit, onImport: suspend (List<ImportRow>) -> Unit, onBulkChange: suspend (List<String>, String?, String?) -> Unit, onPrepareContact: (Set<String>) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val importState: MemberImportState = viewModel()
    var query by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("전체") }
    var groupFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var cohortFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var detailTab by rememberSaveable { mutableStateOf("기본 정보") }
    var selectedIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var bulkKind by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    DiscardOnEpoch(discardEpoch) { adding = false; editingId = null; bulkKind = null; importState.rows = null }
    TrackEditor(adding || editingId != null || bulkKind != null || importState.rows != null, onEditingChange)
    val scope = rememberCoroutineScope()
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val bytes = ByteArrayOutputStream()
            context.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                while (bytes.size() <= 1_000_000) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    bytes.write(buffer, 0, count)
                }
            } ?: error("파일을 열 수 없어요")
            require(bytes.size() <= 1_000_000) { "1MB 이하 CSV를 선택해 주세요" }
            previewMembersCsv(bytes.toString(Charsets.UTF_8.name()), members)
        }.onSuccess { importState.rows = it; importState.error = "" }.onFailure { importState.error = it.message ?: "CSV를 읽지 못했어요" }
    }
    Page {
        val detail = members.firstOrNull { it.id == detailId }
        if (detail != null) {
            TextButton(onClick = { detailId = null }) { Text("← 회원 목록") }
            Section(detail.name)
            Hint("${detail.cohort.ifBlank { "기수 미입력" }} · ${detail.groupName.ifBlank { "소모임 미입력" }} · ${detail.status}")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("기본 정보", "회비", "행사", "연락 기록").forEach { choice -> FilterChip(detailTab == choice, { detailTab = choice }, label = { Text(choice) }) } }
            when (detailTab) {
                "기본 정보" -> {
                    CardLine("연락처", detail.phone.ifBlank { "번호 미입력" })
                    Hint("학기 ${detail.semester.ifBlank { "미입력" }} · 역할 ${detail.role.ifBlank { "미입력" }} · 수신 ${if (detail.optedOut) "제외" else "허용"}")
                    OutlinedButton(onClick = { editingId = detail.id }, modifier = Modifier.fillMaxWidth()) { Text("회원 수정") }
                    if (detail.phone.isNotBlank()) OutlinedButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${normalizePhone(detail.phone)}"))) } catch (_: ActivityNotFoundException) { onMessage("전화 앱을 찾지 못했어요") } }, modifier = Modifier.fillMaxWidth()) { Text("전화 앱 열기") }
                    Action("이 회원 연락 준비", { onPrepareContact(setOf(detail.id)) })
                }
                "회비" -> if (charges.isEmpty()) Hint("회비 항목이 없어요") else charges.forEach { charge -> CardLine(charge.title, "남은 금액 %,d원".format(balance(charge.id, detail.id, ledger))) }
                "행사" -> if (events.isEmpty()) Hint("행사가 없어요") else events.forEach { event -> CardLine(event.title, rsvps.firstOrNull { it.eventId == event.id && it.memberId == detail.id }?.response ?: "미응답") }
                else -> if (contacts.none { it.memberId == detail.id }) Hint("연락 기록이 없어요") else contacts.filter { it.memberId == detail.id }.forEach { record -> CardLine(record.channel, "${record.state} · ${record.bodySnapshot.take(80)}") }
            }
        } else {
        OutlinedTextField(query, { query = it }, label = { Text("회원 검색") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("전체", "활동", "휴면", "탈퇴").forEach { FilterChip(selected = status == it, onClick = { status = it }, label = { Text(it) }) } }
        val groups = members.map(Member::groupName).filter(String::isNotBlank).distinct()
        if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(groupFilter == null, { groupFilter = null }, label = { Text("전체 소모임") })
            groups.forEach { group -> FilterChip(groupFilter == group, { groupFilter = group }, label = { Text(group) }) }
        }
        val cohorts = members.map(Member::cohort).filter(String::isNotBlank).distinct()
        if (cohorts.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(cohortFilter == null, { cohortFilter = null }, label = { Text("전체 기수") })
            cohorts.forEach { cohort -> FilterChip(cohortFilter == cohort, { cohortFilter = cohort }, label = { Text(cohort) }) }
        }
        val found = members.filter { it.name.contains(query, ignoreCase = true) && (status == "전체" || it.status == status) && (groupFilter == null || it.groupName == groupFilter) && (cohortFilter == null || it.cohort == cohortFilter) }
        Hint("${found.size}명")
        Action("회원 추가", { adding = true })
        OutlinedButton(onClick = { csvPicker.launch(arrayOf("text/*", "application/csv", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) { Text("CSV 명단 가져오기") }
        if (importState.error.isNotBlank()) Text("⚠ ${importState.error}", color = MaterialTheme.colorScheme.error)
        if (found.isEmpty()) Hint("조건에 맞는 회원이 없어요") else found.forEach { member -> Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(member.id in selectedIds, { checked -> selectedIds = if (checked) selectedIds + member.id else selectedIds - member.id })
            Box(Modifier.weight(1f)) { CardLine(member.name, "${member.cohort.ifBlank { "기수 미입력" }} · ${member.groupName.ifBlank { "소모임 미입력" }} · ${member.status}", onClick = { detailId = member.id; detailTab = "기본 정보" }) }
        } }
        if (selectedIds.isNotEmpty()) {
            Section("선택 ${selectedIds.size}명")
            Action("선택 회원 연락 준비", { onPrepareContact(selectedIds.toSet()) })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { bulkKind = "상태" }, modifier = Modifier.weight(1f)) { Text("상태 변경") }
                OutlinedButton(onClick = { bulkKind = "소모임" }, modifier = Modifier.weight(1f)) { Text("소모임 변경") }
            }
        }
        }
    }
    val editingMember = members.firstOrNull { it.id == editingId }
    if (adding || editingId != null && editingMember != null) MemberDialog(if (adding) null else editingMember, members, onDismiss = { adding = false; editingId = null }, onSave = onSave)
    if (bulkKind != null) {
        val kind = bulkKind!!
        var value by remember(kind) { mutableStateOf(if (kind == "상태") "활동" else "") }
        var saving by remember(kind) { mutableStateOf(false) }
        var error by remember(kind) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) bulkKind = null }, title = { Text("선택 ${selectedIds.size}명 · $kind 변경") }, text = { Column { if (kind == "상태") Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("활동", "휴면", "탈퇴").forEach { option -> FilterChip(value == option, { value = option }, label = { Text(option) }) } } else OutlinedTextField(value, { value = it }, label = { Text("새 소모임") }); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val ids = selectedIds.toList(); scope.launch { try { commitSave({ onBulkChange(ids, if (kind == "상태") value else null, if (kind == "소모임") value.trim() else null) }, { selectedIds = emptyList(); bulkKind = null }, { error = it }) } finally { saving = false } } } }, enabled = !saving && (kind == "상태" || value.isNotBlank())) { Text("변경") } }, dismissButton = { TextButton(onClick = { bulkKind = null }, enabled = !saving) { Text("취소") } })
    }
    if (importState.rows != null) {
        val rows = importState.rows!!
        AlertDialog(onDismissRequest = { if (!importing) importState.rows = null }, title = { Text("명단 가져오기 미리보기") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("열 이름: 이름, 전화번호, 기수, 소모임. 정상 ${rows.count { it.issue == null }}건 · 오류·중복 ${rows.count { it.issue != null }}건")
                rows.take(30).forEach { row -> Text("${row.row}행 · ${row.name.ifBlank { "이름 없음" }} · ${row.issue ?: "가져오기 가능"}", fontSize = 14.sp) }
                if (rows.size > 30) Hint("나머지 ${rows.size - 30}행은 적용 시 같은 규칙으로 처리해요")
                if (importState.error.isNotBlank()) Text(importState.error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            if (!importing) { importing = true; scope.launch { try { commitSave({ onImport(rows) }, { importState.rows = null; importState.error = "" }, { importState.error = it }) } finally { importing = false } } }
        }, enabled = rows.any { it.issue == null } && !importing) { Text("정상 행만 추가") } }, dismissButton = { TextButton(onClick = { importState.rows = null }, enabled = !importing) { Text("취소") } })
    }
}

@Composable private fun MemberDialog(existing: Member?, members: List<Member>, onDismiss: () -> Unit, onSave: suspend (Member) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name ?: "") }
    var phone by rememberSaveable(existing?.id) { mutableStateOf(existing?.phone ?: "") }
    var cohort by rememberSaveable(existing?.id) { mutableStateOf(existing?.cohort ?: "") }
    var group by rememberSaveable(existing?.id) { mutableStateOf(existing?.groupName ?: "") }
    var semester by rememberSaveable(existing?.id) { mutableStateOf(existing?.semester ?: "") }
    var role by rememberSaveable(existing?.id) { mutableStateOf(existing?.role ?: "") }
    var status by rememberSaveable(existing?.id) { mutableStateOf(existing?.status ?: "활동") }
    var optOut by rememberSaveable(existing?.id) { mutableStateOf(existing?.optedOut ?: false) }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    fun requestDismiss() { if (existing != null || name.isNotBlank() || phone.isNotBlank() || cohort.isNotBlank() || group.isNotBlank()) discard = true else onDismiss() }
    val duplicate = members.firstOrNull { it.id != existing?.id && phone.isNotBlank() && normalizePhone(it.phone) == normalizePhone(phone) }
    AlertDialog(onDismissRequest = { if (!saving) requestDismiss() }, title = { Text(if (existing == null) "회원 추가" else "회원 수정") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("이름 *") }, singleLine = true)
            OutlinedTextField(phone, { phone = it }, label = { Text("전화번호") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            if (duplicate != null) Text("⚠ ${duplicate.name} 회원과 번호가 같습니다. 자동 병합하지 않아요", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            OutlinedTextField(cohort, { cohort = it }, label = { Text("기수·학년") }, singleLine = true)
            OutlinedTextField(group, { group = it }, label = { Text("소모임") }, singleLine = true)
            OutlinedTextField(semester, { semester = it }, label = { Text("활동 학기") }, singleLine = true)
            OutlinedTextField(role, { role = it }, label = { Text("역할") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("활동", "휴면", "탈퇴").forEach { FilterChip(selected = status == it, onClick = { status = it }, label = { Text(it) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(optOut, { optOut = it }); Text("안내 수신 제외") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        error = when { name.isBlank() -> "이름을 입력해 주세요"; phone.isNotBlank() && !validPhone(phone) -> "휴대전화번호를 확인해 주세요"; else -> "" }
        if (error.isEmpty() && !saving) {
            saving = true
            val member = Member(id = existing?.id ?: newId(), name = name.trim(), phone = phone.trim(), cohort = cohort.trim(), groupName = group.trim(), status = status, optedOut = optOut, semester = semester.trim(), role = role.trim())
            scope.launch { try { commitSave({ onSave(member) }, onDismiss, { error = it }) } finally { saving = false } }
        }
    }, enabled = !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = ::requestDismiss, enabled = !saving) { Text("취소") } })
    DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; onDismiss() })
}

@Composable private fun EventsScreen(events: List<Event>, members: List<Member>, rsvps: List<Rsvp>, duties: List<Duty>, charges: List<Charge>, ledger: List<LedgerEntry>, invites: List<Invite>, pendingMutations: List<PendingMutation>, discardEpoch: Int, onlineReady: Boolean, onEditingChange: (Boolean) -> Unit, onMessage: (String) -> Unit, onAddDuty: suspend (Duty) -> Unit, onSave: suspend (Event) -> Unit, onRsvp: suspend (Rsvp) -> Unit, onPublish: (Event) -> Unit, onLink: (Event, Member?) -> Unit, onRefresh: (Event) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dao = remember(context) { AppDatabase.get(context).dao() }
    val engine = remember(context) { SyncEngine(context) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("예정") }
    var detailTab by rememberSaveable { mutableStateOf("개요") }
    var confirmDate by rememberSaveable { mutableStateOf(false) }
    var addEventDuty by rememberSaveable { mutableStateOf(false) }
    var cancelInviteId by rememberSaveable { mutableStateOf<String?>(null) }
    var linkSubmissionId by rememberSaveable { mutableStateOf<String?>(null) }
    var conflictDecision by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var rsvpMemberId by rememberSaveable { mutableStateOf<String?>(null) }
    DiscardOnEpoch(discardEpoch) { adding = false; editingId = null; rsvpMemberId = null; confirmDate = false; linkSubmissionId = null; conflictDecision = null; addEventDuty = false; cancelInviteId = null }
    TrackEditor(adding || editingId != null || rsvpMemberId != null || confirmDate || linkSubmissionId != null || conflictDecision != null || addEventDuty || cancelInviteId != null, onEditingChange)
    fun revocation(invite: Invite): PendingMutation? = pendingMutations.firstOrNull { it.eventId == invite.eventId && (it.kind == "revoke-link" && it.targetId == invite.id || invite.memberId.isNotBlank() && it.kind == "revoke-member" && it.targetId == invite.memberId) }
    fun copyInvite(invite: Invite) {
        scope.launch {
            try {
                val fresh = dao.inviteFor(invite.eventId, invite.memberId)
                val blocked = dao.pendingMutations().first().any { it.eventId == invite.eventId && (it.kind == "revoke-link" && it.targetId == invite.id || invite.memberId.isNotBlank() && it.kind == "revoke-member" && it.targetId == invite.memberId) }
                val retired = invite.memberId.isNotBlank() && dao.memberById(invite.memberId)?.status == "탈퇴"
                if (fresh == null || fresh.id != invite.id || fresh.url.isBlank() || blocked || retired || !java.time.Instant.parse(fresh.expiresAt).isAfter(java.time.Instant.now())) { onMessage("링크가 취소·만료됐거나 취소 대기 중이에요. 복사하지 않았어요"); return@launch }
                copyText(context, fresh.url)
                onMessage("링크를 복사했어요. 대상과 범위를 확인해 공유해 주세요")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage("링크 상태 확인 실패 · ${e.message}") }
        }
    }
    fun runSyncAction(action: suspend () -> Unit, success: String) {
        scope.launch {
            try { action(); onMessage(success) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage("연결 오류 · ${e.message}. 대기 상태를 확인하고 다시 시도해 주세요") }
        }
    }
    Page {
        Action("행사 만들기", { adding = true })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("예정", "진행", "종료", "취소").forEach { choice -> FilterChip(filter == choice, { filter = choice }, label = { Text(choice) }) } }
        if (events.isEmpty()) Hint("행사가 없어요. 일시와 장소는 미정으로 둘 수 있어요")
        events.filter { eventPhase(it) == filter }.forEach { event ->
            val eventRsvps = rsvps.filter { it.eventId == event.id }
            CardLine("${if (event.cancelled) "취소됨 · " else ""}${event.title}", "${event.whenText.ifBlank { "일시 미정" }} · ${event.place.ifBlank { "장소 미정" }} · 응답 ${eventRsvps.count { it.response != "미응답" }}명", onClick = { selectedId = event.id; detailTab = "개요" })
        }
        val event = events.firstOrNull { it.id == selectedId }
        if (event != null) {
            Section(event.title)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("개요", "참여자", "준비·정산").forEach { choice -> FilterChip(detailTab == choice, { detailTab = choice }, label = { Text(choice) }) } }
            if (detailTab == "개요") {
            if (event.cancelled) Text("⚠ 취소된 행사입니다. 기존 응답은 보존하고 새 응답은 받지 않습니다", color = MaterialTheme.colorScheme.error)
            Hint("${event.whenText.ifBlank { "일시 미정" }} · ${event.place.ifBlank { "장소 미정" }}")
            if (event.details.isNotBlank()) Text(event.details)
            val options = pollOptions(event)
            if (options.isNotEmpty()) {
                Section("일정 투표")
                Hint("후보 종료 후 운영자가 일시를 직접 확정합니다 · 마감 ${event.pollDeadline.ifBlank { "미정" }}")
                val submissions = runCatching { JSONObject(event.responsesJson).optJSONArray("submissions") }.getOrNull()
                options.forEach { (id, label) ->
                    val votes = (0 until (submissions?.length() ?: 0)).count { index ->
                        val picks = submissions?.optJSONObject(index)?.optJSONArray("selectedOptions")
                        (0 until (picks?.length() ?: 0)).any { picks?.optString(it) == id }
                    }
                    CardLine(label, "가능 응답 ${votes}건 · 마지막 동기화 ${event.lastSyncAt?.let { SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(it)) } ?: "없음"}")
                }
                if (!event.cancelled) OutlinedButton(onClick = { confirmDate = true }, modifier = Modifier.fillMaxWidth()) { Text("행사 일시 직접 확정") }
            }
            if (!onlineReady) Hint("응답 링크를 사용하려면 설정에서 HTTPS 서버와 운영자 토큰을 연결해 주세요")
            else {
                val manualCount = rsvps.count { it.eventId == event.id && it.response != "미응답" }
                if (manualCount > 0 && event.serverVersion == 0) Hint("최초 게시에 운영자 수동 응답 $manualCount 건을 함께 올려요")
                Hint(if (event.serverVersion == 0) "온라인 미게시 · 일시/마감은 ISO 8601 날짜와 시간대(예: 2026-11-01T15:00:00+09:00)로 입력해 주세요" else if (event.serverDirty) "로컬 수정사항 미게시 · 서버 버전 ${event.serverVersion}" else "서버 버전 ${event.serverVersion} 게시 확인")
                if (event.conflictJson.isNotBlank()) {
                    Text("⚠ 행사 내용 충돌 · 로컬 수정과 서버 최신 버전을 검토해 주세요", color = MaterialTheme.colorScheme.error)
                    val remote = runCatching { JSONObject(event.conflictJson).optJSONObject("event") ?: JSONObject(event.conflictJson) }.getOrNull()
                    CardLine("서버 최신 정보", "${remote.displayText("title", "정보 확인 필요")} · ${remote.displayText("startsAt", "일시 미정")}")
                    CardLine("이 기기 수정", "${event.title} · ${event.whenText.ifBlank { "일시 미정" }}")
                    OutlinedButton(onClick = { conflictDecision = true }, modifier = Modifier.fillMaxWidth()) { Text("서버 버전 사용") }
                    OutlinedButton(onClick = { conflictDecision = false }, modifier = Modifier.fillMaxWidth()) { Text("내 수정 다시 제안") }
                }
                if (event.pendingOperationId.isNotBlank()) Hint("이전 게시 요청 결과 미확인 · 같은 작업으로 재시도합니다")
                Action(if (event.pendingOperationId.isNotBlank()) "게시 결과 재확인" else if (event.serverVersion > 0) "현재 행사 다시 게시" else "행사 서버 게시", enabled = event.conflictJson.isBlank(), onClick = { onPublish(event) })
                if (event.serverVersion > 0) {
                    Action("서버 응답 가져오기", onClick = { onRefresh(event) })
                    Hint("마지막 확인: ${event.lastSyncAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)) } ?: "아직 없음"} · 회원 응답은 읽기 전용이며 로컬 회비 원장에 자동 반영하지 않아요")
                    val snapshot = runCatching { JSONObject(event.responsesJson) }.getOrNull()
                    val submissions = snapshot?.optJSONArray("submissions")
                    val payments = snapshot?.optJSONArray("paymentRequests")
                    if (submissions != null) for (i in 0 until submissions.length()) {
                        val response = submissions.optJSONObject(i) ?: continue
                        CardLine(response.optString("name", "이름 미입력"), "응답 ${response.optString("rsvp")} · ${if (response.isNull("memberId")) "회원 연결 확인 대기" else "회원 ID 연결됨"} · 서버 버전 ${response.optInt("version")}")
                    }
                    if (payments != null) for (i in 0 until payments.length()) {
                        val request = payments.optJSONObject(i) ?: continue
                        CardLine("입금 확인 요청 · ${request.optString("depositorName")}", paymentRequestSummary(request))
                    }
                    if (event.serverVersion > 0) {
                        Section("응답 링크")
                        val now = java.time.Instant.now()
                        val common = invites.firstOrNull { it.eventId == event.id && it.memberId.isBlank() && it.url.isNotBlank() && runCatching { java.time.Instant.parse(it.expiresAt).isAfter(now) }.getOrDefault(false) }
                        val canIssue = !event.cancelled && !event.serverDirty && event.pendingOperationId.isBlank()
                        val commonRevoke = pendingMutations.firstOrNull { pending -> pending.eventId == event.id && pending.kind == "revoke-link" && invites.any { it.id == pending.targetId && it.memberId.isBlank() } }
                        if (common == null) {
                            if (commonRevoke != null) CardLine("공통 링크 취소 ${commonRevoke.status}", commonRevoke.error.ifBlank { "서버 확인 대기 · 복사 차단" })
                            Action("공통 링크 발급 · 30일", enabled = canIssue && commonRevoke == null, onClick = { onLink(event, null) })
                        }
                        else {
                            val pending = revocation(common)
                            CardLine("공통 링크", if (pending == null) "만료 ${common.expiresAt}" else "취소 ${pending.status} · ${pending.error.ifBlank { "서버 확인 대기" }}")
                            if (pending == null) OutlinedButton(onClick = { copyInvite(common) }, modifier = Modifier.fillMaxWidth()) { Text("공통 링크 복사") }
                            OutlinedButton(onClick = { cancelInviteId = common.id }, enabled = pending == null, modifier = Modifier.fillMaxWidth()) { Text(if (pending == null) "공통 링크 취소" else "취소 결과 확인 중") }
                        }
                        members.filter { it.status == "활동" }.forEach { member ->
                            val personal = invites.firstOrNull { it.eventId == event.id && it.memberId == member.id && it.url.isNotBlank() && runCatching { java.time.Instant.parse(it.expiresAt).isAfter(now) }.getOrDefault(false) }
                            val memberRevocation = pendingMutations.firstOrNull { it.eventId == event.id && it.kind == "revoke-member" && it.targetId == member.id }
                            val linkRevocation = pendingMutations.firstOrNull { pending -> pending.eventId == event.id && pending.kind == "revoke-link" && invites.any { it.id == pending.targetId && it.memberId == member.id } }
                            if (personal == null) OutlinedButton(onClick = { onLink(event, member) }, enabled = canIssue && !member.optedOut && memberRevocation == null && linkRevocation == null, modifier = Modifier.fillMaxWidth()) { Text(if (memberRevocation != null || linkRevocation != null) "${member.name} 링크 취소 ${(memberRevocation ?: linkRevocation)?.status}" else "${member.name} 개인 링크 발급") }
                            else {
                                val pending = revocation(personal)
                                CardLine("${member.name} 개인 링크", if (pending == null) "만료 ${personal.expiresAt}" else "취소 ${pending.status} · ${pending.error.ifBlank { "서버 확인 대기" }}")
                                if (pending == null) OutlinedButton(onClick = { copyInvite(personal) }, modifier = Modifier.fillMaxWidth()) { Text("${member.name} 개인 링크 복사 · 해당 회원에게만 전달") }
                                OutlinedButton(onClick = { cancelInviteId = personal.id }, enabled = pending == null, modifier = Modifier.fillMaxWidth()) { Text(if (pending == null) "${member.name} 개인 링크 취소" else "취소 결과 확인 중") }
                            }
                        }
                        invites.filter { it.eventId == event.id && it.memberId.isNotBlank() && members.firstOrNull { member -> member.id == it.memberId }?.status == "탈퇴" && it.url.isNotBlank() }.forEach { invite ->
                            val pending = revocation(invite)
                            CardLine("탈퇴 회원 개인 링크", if (pending == null) "취소 확인 필요 · 복사 차단" else "취소 ${pending.status} · ${pending.error.ifBlank { "서버 확인 대기" }}")
                            if (pending == null) OutlinedButton(onClick = { cancelInviteId = invite.id }, modifier = Modifier.fillMaxWidth()) { Text("링크 취소") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editingId = event.id }, modifier = Modifier.fillMaxWidth()) { Text("행사 수정") }
            }
            if (detailTab == "참여자") {
            Section(if (event.serverVersion == 0) "참여자 · 운영자 수동 응답" else "게시 전 수동 응답 · 게시 후에는 서버 응답을 확인해 주세요")
            val onlineSubmissions = runCatching { JSONObject(event.responsesJson).optJSONArray("submissions") }.getOrNull()
            if (event.serverVersion > 0 && onlineSubmissions != null) for (index in 0 until onlineSubmissions.length()) {
                val item = onlineSubmissions.optJSONObject(index) ?: continue
                if (item.isNull("memberId") || item.optString("memberId").isBlank()) {
                    CardLine("확인 대기 · ${item.optString("name", "이름 미입력")}", "${item.optString("rsvp")} · 이름만으로 자동 연결하지 않아요")
                    OutlinedButton(onClick = { linkSubmissionId = item.optString("id") }, modifier = Modifier.fillMaxWidth()) { Text("기존 회원과 연결 검토") }
                }
            }
            members.filter { it.status != "탈퇴" }.forEach { member ->
                val record = rsvps.firstOrNull { it.eventId == event.id && it.memberId == member.id }
                val online = (0 until (onlineSubmissions?.length() ?: 0)).mapNotNull { onlineSubmissions?.optJSONObject(it) }.firstOrNull { it.optString("memberId") == member.id }
                val answer = if (event.serverVersion > 0) online?.optString("rsvp") ?: "미응답" else record?.response ?: "미응답"
                CardLine(member.name, "$answer · ${if (record?.attendance == true) "현장 출석 확인" else "현장 출석 미확인"}", onClick = if (!event.cancelled && event.pendingOperationId.isBlank()) ({ rsvpMemberId = member.id }) else null)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(record?.attendance == true, { checked -> runSyncAction({ if (record == null) dao.put(Rsvp(eventId = event.id, memberId = member.id, response = "미응답", attendance = checked)) else dao.updateRsvp(record.id, record.response, checked) }, "현장 출석을 이 기기에 기록했어요") })
                    Text("현장 출석 · 참석 응답과 별개")
                }
            }
            pendingMutations.filter { it.eventId == event.id }.forEach { pending ->
                CardLine("동기화 ${pending.kind} · ${pending.status}", pending.error.ifBlank { "작업 ID ${pending.operationId.take(8)}" })
                when {
                    pending.status == "error" -> OutlinedButton(onClick = { runSyncAction({ engine.retryMutation(pending.id); engine.syncAll() }, "재시도 결과를 확인했어요") }, modifier = Modifier.fillMaxWidth()) { Text("이 작업 다시 시도") }
                    pending.status == "conflict" && pending.kind in listOf("response", "link") -> {
                        OutlinedButton(onClick = { runSyncAction({ engine.resolveConflict(pending.id, true) }, "서버 최신 내용을 반영했어요") }, modifier = Modifier.fillMaxWidth()) { Text("서버 최신 기록 사용") }
                        OutlinedButton(onClick = { runSyncAction({ engine.resolveConflict(pending.id, false); engine.syncAll() }, "내 변경을 다시 제안했어요") }, modifier = Modifier.fillMaxWidth()) { Text("내 변경 다시 제안") }
                    }
                    pending.status == "conflict" && pending.kind == "payment-reject" -> OutlinedButton(onClick = { runSyncAction({ engine.resolveConflict(pending.id, true) }, "서버의 입금 요청 상태를 다시 확인했어요") }, modifier = Modifier.fillMaxWidth()) { Text("서버 최신 요청 확인") }
                    pending.status == "conflict" && pending.kind in listOf("revoke-link", "revoke-member") -> Hint("링크 취소 권한·서버 상태가 충돌했어요. 복사는 차단된 상태로 운영자 연결과 서버 기록을 검토해 주세요")
                    pending.status == "conflict" -> Hint("원장과 서버 영수증을 함께 검토해야 해요. 회비에서 입금 요청을 확인해 주세요")
                }
            }
            }
            if (detailTab == "준비·정산") {
            Section("준비 할 일")
            Action("행사 준비 할 일 추가", { addEventDuty = true })
            duties.filter { it.eventId == event.id }.forEach { CardLine(it.title, if (it.done) "완료" else "미완료") }
            Section("관련 회비")
            val linkedCharges = charges.filter { it.eventId == event.id }
            if (linkedCharges.isEmpty()) Hint("연결된 회비 항목이 없어요")
            linkedCharges.forEach { charge -> CardLine(charge.title, "원장 청구·확인 납부·잔액 %,d원".format(ledger.filter { it.chargeId == charge.id }.sumOf { it.deltaWon })) }
            Hint("행사 수입·지출 정산은 현재 회비 원장과 별개로 추가 구현이 필요해요")
            }
        }
    }
    val editingEvent = events.firstOrNull { it.id == editingId }
    if (adding || editingId != null && editingEvent != null) EventDialog(if (adding) null else editingEvent, onDismiss = { adding = false; editingId = null }, onSave = { onSave(it); selectedId = it.id })
    if (addEventDuty && selectedId != null) DutyDialog(onDismiss = { addEventDuty = false }, onSave = { duty -> onAddDuty(duty.copy(eventId = selectedId)) })
    if (confirmDate && selectedId != null) {
        val current = events.firstOrNull { it.id == selectedId }
        if (current != null) {
            val options = pollOptions(current)
            var chosen by remember(current.id) { mutableStateOf(options.firstOrNull()?.second.orEmpty()) }
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { if (!saving) confirmDate = false },
                title = { Text("행사 일시 확정") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Hint("투표 결과만으로 자동 확정하지 않아요. 운영자가 후보를 선택하고 저장합니다")
                        options.forEach { (_, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(chosen == label, { chosen = label })
                                Text(label)
                            }
                        }
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                    }
                },
                confirmButton = { TextButton(onClick = {
                    if (!saving) {
                        saving = true
                        scope.launch {
                            try { commitSave({ require(chosen.isNotBlank()) { "후보를 선택해 주세요" }; onSave(current.copy(whenText = chosen)) }, { confirmDate = false }, { error = it }) }
                            finally { saving = false }
                        }
                    }
                }, enabled = !saving && chosen.isNotBlank()) { Text("일시 저장") } },
                dismissButton = { TextButton(onClick = { confirmDate = false }, enabled = !saving) { Text("취소") } },
            )
        }
    }
    if (conflictDecision != null && selectedId != null) {
        val useServer = conflictDecision == true
        var resolving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!resolving) conflictDecision = null }, title = { Text("행사 충돌 해결") }, text = { Column { Text(if (useServer) "서버 최신 행사 정보를 이 기기에 반영합니다. 로컬 수정 내용은 버립니다" else "서버 버전을 기준으로 이 기기 수정을 다시 제안합니다"); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!resolving) { resolving = true; val eventId = selectedId!!; scope.launch { try { commitSave({ engine.resolveEventConflict(eventId, useServer) }, { conflictDecision = null; onMessage("충돌 선택을 저장했어요. 동기화 상태를 확인해 주세요") }, { error = it }) } finally { resolving = false } } } }, enabled = !resolving) { Text("확정") } }, dismissButton = { TextButton(onClick = { conflictDecision = null }, enabled = !resolving) { Text("취소") } })
    }
    if (cancelInviteId != null) {
        val invite = invites.firstOrNull { it.id == cancelInviteId }
        if (invite != null) {
            val target = if (invite.memberId.isBlank()) "공통 링크" else "${members.firstOrNull { it.id == invite.memberId }?.name ?: "회원 ID ${invite.memberId.take(8)}"} 개인 링크"
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { if (!saving) cancelInviteId = null }, title = { Text("링크 취소 확인") }, text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("$target 사용 권한을 취소합니다. 서버 확인 전에는 취소 대기 상태로 표시하고 복사를 막아요")
                    Hint("이미 제출된 회원 응답은 삭제하지 않습니다")
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; scope.launch { try { commitSave({
                engine.revokeInvite(invite.id)
                try { engine.syncAll() } catch (e: CancellationException) { throw e } catch (_: Exception) { }
                val pending = dao.pendingMutations().first().firstOrNull { it.kind == "revoke-link" && it.targetId == invite.id }
                onMessage(if (pending == null) "서버에서 링크 취소를 확인했어요" else "링크 취소 ${pending.status} · 복사는 차단했어요")
            }, { cancelInviteId = null }, { error = it }) } finally { saving = false } } } }, enabled = !saving) { Text("링크 취소") } }, dismissButton = { TextButton(onClick = { cancelInviteId = null }, enabled = !saving) { Text("유지") } })
        }
    }
    if (linkSubmissionId != null && selectedId != null) {
        val submission = events.firstOrNull { it.id == selectedId }?.let { event -> runCatching { JSONObject(event.responsesJson).optJSONArray("submissions") }.getOrNull() }?.let { rows -> (0 until rows.length()).mapNotNull(rows::optJSONObject).firstOrNull { it.optString("id") == linkSubmissionId } }
        if (submission != null) {
        var chosenId by remember(submission.optString("id")) { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) linkSubmissionId = null }, title = { Text("회원 연결 검토") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Hint("제출자 ${submission.optString("name")} · 응답 ${submission.optString("rsvp")}. 이름만으로 자동 연결하지 않습니다")
                members.forEach { member -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(chosenId == member.id, { chosenId = member.id }); Text("${member.name} · ${member.cohort} / ${member.groupName}") } }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val eventId = selectedId!!; val submissionId = submission.optString("id"); val version = submission.optInt("version"); scope.launch { try { commitSave({ engine.linkSubmission(eventId, submissionId, chosenId, version); try { engine.syncAll(); onMessage("회원 연결을 서버에서 확인했어요") } catch (e: CancellationException) { throw e } catch (_: Exception) { onMessage("회원 연결 작업을 대기함에 저장했어요") } }, { linkSubmissionId = null }, { error = it }) } finally { saving = false } } } }, enabled = chosenId.isNotBlank() && !saving) { Text("연결 저장") } }, dismissButton = { TextButton(onClick = { linkSubmissionId = null }, enabled = !saving) { Text("취소") } })
        }
    }
    if (rsvpMemberId != null && selectedId != null) {
        val member = members.firstOrNull { it.id == rsvpMemberId } ?: return
        val event = events.firstOrNull { it.id == selectedId } ?: return
        val prior = rsvps.firstOrNull { it.eventId == event.id && it.memberId == member.id }
        val onlineRows = responseRowsFromSnapshot(event.responsesJson)
        var response by rememberSaveable(member.id, event.id) { mutableStateOf(if (event.serverVersion > 0) onlineRows.firstOrNull { it.memberId == member.id }?.response ?: "미정" else prior?.response ?: "미정") }
        var attendance by rememberSaveable(member.id, event.id) { mutableStateOf(prior?.attendance ?: false) }
        var saving by remember(member.id, event.id) { mutableStateOf(false) }
        var saveError by remember(member.id, event.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) rsvpMemberId = null }, title = { Text("${member.name} · 수동 응답") }, text = {
            Column {
                listOf("참석", "불참", "미정").forEach { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = response == it, onClick = { response = it }); Text(it) } }
                if (event.serverVersion == 0) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(attendance, { attendance = it }); Text("현장 출석 확인") }
                else Hint("출석은 참여자 행에서 별도로 기록해요. 온라인 응답은 대기·충돌 상태를 확인해 주세요")
                if (saveError.isNotBlank()) Text(saveError, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val value = Rsvp(eventId = event.id, memberId = member.id, response = response, attendance = attendance); scope.launch { try { commitSave({ onRsvp(value) }, { rsvpMemberId = null }, { saveError = it }) } finally { saving = false } } } }, enabled = !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = { rsvpMemberId = null }, enabled = !saving) { Text("취소") } })
    }
}

@Composable private fun EventDialog(existing: Event?, onDismiss: () -> Unit, onSave: suspend (Event) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var whenText by rememberSaveable(existing?.id) { mutableStateOf(existing?.whenText ?: "") }
    var place by rememberSaveable(existing?.id) { mutableStateOf(existing?.place ?: "") }
    var details by rememberSaveable(existing?.id) { mutableStateOf(existing?.details ?: "") }
    var deadline by rememberSaveable(existing?.id) { mutableStateOf(existing?.deadline ?: "") }
    var capacity by rememberSaveable(existing?.id) { mutableStateOf(existing?.capacity?.toString() ?: "") }
    var fee by rememberSaveable(existing?.id) { mutableStateOf(existing?.feeWon?.takeIf { it > 0 }?.toString() ?: "") }
    var cancelled by rememberSaveable(existing?.id) { mutableStateOf(existing?.cancelled ?: false) }
    var pollText by rememberSaveable(existing?.id) { mutableStateOf(existing?.let { pollOptions(it).joinToString("\n") { option -> option.second } } ?: "") }
    var pollDeadline by rememberSaveable(existing?.id) { mutableStateOf(existing?.pollDeadline ?: "") }
    var step by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    fun requestDismiss() { if (existing != null || title.isNotBlank() || place.isNotBlank() || details.isNotBlank()) discard = true else onDismiss() }
    AlertDialog(onDismissRequest = { if (!saving) requestDismiss() }, title = { Text("${if (existing == null) "행사 만들기" else "행사 수정"} · ${listOf("기본 정보", "응답·투표", "검토")[step]}") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (step == 0) {
            OutlinedTextField(title, { title = it }, label = { Text("행사명 *") })
            OutlinedTextField(whenText, { whenText = it }, label = { Text("일시 · 미정 가능") })
            OutlinedTextField(place, { place = it }, label = { Text("장소 · 미정 가능") })
            OutlinedTextField(capacity, { capacity = it }, label = { Text("정원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(fee, { fee = it }, label = { Text("참가비 원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            if (step == 1) {
            Hint("온라인 게시 시 날짜는 시간대가 포함된 ISO 8601로 입력해 주세요")
            OutlinedTextField(deadline, { deadline = it }, label = { Text("응답 마감 · 미정 가능") })
            OutlinedTextField(details, { details = it }, label = { Text("안내사항") }, minLines = 2)
            OutlinedTextField(pollText, { pollText = it }, label = { Text("일정 후보 · 한 줄에 한 날짜") }, minLines = 3)
            OutlinedTextField(pollDeadline, { pollDeadline = it }, label = { Text("투표 마감 · 미정 가능") })
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cancelled, { cancelled = it }); Text("행사 취소 · 기존 응답 보존") }
            }
            if (step == 2) {
                CardLine(title.trim(), "${whenText.ifBlank { "일시 미정" }} · ${place.ifBlank { "장소 미정" }} · 참가비 ${fee.ifBlank { "0" }}원")
                Hint("응답 마감 ${deadline.ifBlank { "미정" }} · 일정 후보 ${pollText.lines().count(String::isNotBlank)}개 · 투표 마감 ${pollDeadline.ifBlank { "미정" }}")
                if (cancelled) Text("⚠ 취소 상태로 저장합니다. 기존 응답은 보존해요", color = MaterialTheme.colorScheme.error)
                Hint("온라인 링크는 행사 저장 후 별도로 게시해야 발급돼요")
            }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        error = when { title.isBlank() -> "행사명을 입력해 주세요"; capacity.isNotBlank() && (capacity.toIntOrNull() ?: 0) <= 0 -> "정원은 양수로 입력해 주세요"; fee.isNotBlank() && (fee.toLongOrNull() ?: -1) < 0 -> "참가비를 확인해 주세요"; else -> "" }
        if (error.isEmpty() && step == 1) {
            val candidates = pollText.lines().map(String::trim).filter(String::isNotBlank)
            error = when {
                candidates.size != candidates.distinct().size -> "중복된 일정 후보가 있어요"
                candidates.any { runCatching { java.time.OffsetDateTime.parse(it) }.isFailure } -> "일정 후보를 2026-11-01T15:00:00+09:00 형식으로 입력해 주세요"
                pollDeadline.isNotBlank() && runCatching { java.time.OffsetDateTime.parse(pollDeadline) }.isFailure -> "투표 마감의 날짜·시간대를 확인해 주세요"
                else -> ""
            }
        }
        if (error.isEmpty() && step < 2) step++
        else if (error.isEmpty() && !saving) {
            saving = true
            val oldOptions = existing?.let(::pollOptions).orEmpty()
            val newOptions = JSONArray().apply { pollText.lines().map(String::trim).filter(String::isNotBlank).forEach { label -> put(JSONObject().put("id", oldOptions.firstOrNull { it.second == label }?.first ?: newId()).put("label", label)) } }
            val event = (existing ?: Event(title = title.trim())).copy(title = title.trim(), whenText = whenText.trim(), place = place.trim(), details = details.trim(), deadline = deadline.trim(), capacity = capacity.toIntOrNull(), feeWon = fee.toLongOrNull() ?: 0, cancelled = cancelled, pollOptionsJson = newOptions.toString(), pollDeadline = pollDeadline.trim())
            scope.launch { try { commitSave({ onSave(event) }, onDismiss, { error = it }) } finally { saving = false } }
        }
    }, enabled = !saving) { Text(if (step == 2) "저장" else "다음") } }, dismissButton = { TextButton(onClick = { if (step > 0) step-- else requestDismiss() }, enabled = !saving) { Text(if (step > 0) "이전" else "취소") } })
    DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; onDismiss() })
}

@Composable private fun FeesScreen(charges: List<Charge>, members: List<Member>, ledger: List<LedgerEntry>, events: List<Event>, pendingMutations: List<PendingMutation>, clubConfig: ClubConfig?, discardEpoch: Int, onEditingChange: (Boolean) -> Unit, onMessage: (String) -> Unit, onCharge: suspend (Charge) -> Unit, onEntry: suspend (LedgerEntry) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val dao = remember(context) { AppDatabase.get(context).dao() }
    val engine = remember(context) { SyncEngine(context) }
    var addCharge by rememberSaveable { mutableStateOf(false) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var addEntry by rememberSaveable { mutableStateOf(false) }
    var listFilter by rememberSaveable { mutableStateOf("전체") }
    var statusFilter by rememberSaveable { mutableStateOf("전체") }
    var reviewingRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewMode by rememberSaveable { mutableStateOf("confirm") }
    var reversalId by rememberSaveable { mutableStateOf<String?>(null) }
    var receiptRequestId by rememberSaveable { mutableStateOf<String?>(null) }
    var chargeSaving by remember { mutableStateOf(false) }
    var chargeError by remember { mutableStateOf("") }
    DiscardOnEpoch(discardEpoch) { addCharge = false; addEntry = false; reviewingRequestId = null; reversalId = null; receiptRequestId = null }
    TrackEditor(addCharge || addEntry || reviewingRequestId != null || reversalId != null || receiptRequestId != null, onEditingChange)
    val selected = charges.firstOrNull { it.id == selectedId }
    val paymentRequests = selected?.eventId?.let { eventId -> events.firstOrNull { it.id == eventId } }?.let { event ->
        runCatching { JSONObject(event.responsesJson).optJSONArray("paymentRequests") }.getOrNull()?.let { values -> (0 until values.length()).mapNotNull(values::optJSONObject) }
    }.orEmpty()
    Page {
        Action("회비 항목 추가", { chargeError = ""; addCharge = true })
        if (charges.isEmpty()) Hint("회비 항목이 없어요")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("전체", "학기 회비", "행사 참가비").forEach { choice -> FilterChip(listFilter == choice, { listFilter = choice }, label = { Text(choice) }) } }
        charges.filter { listFilter == "전체" || if (listFilter == "학기 회비") it.eventId == null else it.eventId != null }.forEach { charge ->
            val entries = ledger.filter { it.chargeId == charge.id }
            val billed = entries.filter { it.kind == "청구" }.sumOf { it.deltaWon }
            val paid = -entries.filter { it.kind == "납부 확인" }.sumOf { it.deltaWon }
            CardLine(charge.title, "청구 %,d원 · 확인 납부 %,d원 · 남은 금액 %,d원".format(billed, paid, entries.sumOf { it.deltaWon }), onClick = { selectedId = charge.id })
        }
        if (selected != null) {
            val charge = selected
            Section(charge.title)
            Hint("입금 확인 요청은 납부 완료가 아니에요. 실제 입금을 확인한 뒤 별도 납부 기록을 남겨 주세요")
            charge.eventId?.let { eventId -> events.firstOrNull { it.id == eventId } }?.let { event -> Hint("온라인 입금 요청 마지막 확인 ${event.lastSyncAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)) } ?: "없음"} · 최신 확인은 설정의 지금 동기화") }
            Action("원장 기록 추가", { addEntry = true })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("전체", "입금 확인 대기", "미납", "일부 납부", "완납", "면제", "환불").forEach { choice -> FilterChip(statusFilter == choice, { statusFilter = choice }, label = { Text(choice) }) } }
            members.forEach { member ->
                val memberEntries = ledger.filter { it.chargeId == charge.id && it.memberId == member.id }
                val remaining = memberEntries.sumOf { it.deltaWon }
                val paid = -memberEntries.filter { it.kind == "납부 확인" }.sumOf { it.deltaWon }
                val claim = paymentRequests.any { it.optString("memberId") == member.id && it.optString("status") == "pending" }
                val state = when {
                    claim -> "입금 확인 대기"
                    memberEntries.any { it.kind == "환불" } -> "환불"
                    memberEntries.any { it.kind == "면제" } && remaining <= 0 -> "면제"
                    remaining <= 0 && memberEntries.any { it.kind == "청구" } -> "완납"
                    paid > 0 -> "일부 납부"
                    else -> "미납"
                }
                if (statusFilter == "전체" || statusFilter == state) CardLine(member.name, "청구 %,d원 · 확인 납부 %,d원 · 남음 %,d원 · $state".format(memberEntries.filter { it.kind == "청구" }.sumOf { it.deltaWon }, paid, remaining))
            }
            if (paymentRequests.isNotEmpty()) Section("입금 확인 요청 · 주장 기록")
            paymentRequests.forEach { request ->
                val member = members.firstOrNull { it.id == request.optString("memberId") }
                val review = request.optJSONObject("review")
                val reserved = review?.optString("state") == "reserved"
                CardLine("${member?.name ?: "회원 연결 미확인"} · ${request.optString("depositorName")}", paymentRequestSummary(request))
                if (request.optString("status") == "pending" && !reserved && member != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { reviewingRequestId = request.optString("id"); reviewMode = "confirm" }) { Text("입금 확인") }
                    TextButton(onClick = { reviewingRequestId = request.optString("id"); reviewMode = "reject" }) { Text("반려") }
                }
                val inProgress = pendingMutations.any { it.eventId == charge.eventId && it.targetId == request.optString("id") && it.kind.startsWith("payment") }
                if ((reserved || request.optString("status") == "confirmed") && (ledger.none { it.paymentRequestId == request.optString("id") } || inProgress)) TextButton(onClick = { receiptRequestId = request.optString("id") }) { Text(if (reserved) "예약 영수증 검토·복구" else "원장 영수증 대조") }
            }
            pendingMutations.filter { it.eventId == charge.eventId && it.kind.startsWith("payment") }.forEach { pending ->
                CardLine("납부 검토 ${pending.status}", pending.error.ifBlank { "요청 ${pending.targetId.take(8)} · 서버 확인 대기" })
                if (pending.status == "error") OutlinedButton(onClick = { scope.launch { try { engine.retryMutation(pending.id); engine.syncAll(); onMessage("납부 작업 재시도 결과를 확인했어요") } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage("원장과 서버 영수증을 함께 검토해 주세요 · ${e.message}") } } }, modifier = Modifier.fillMaxWidth()) { Text("대기 작업 다시 확인") }
                if (pending.status == "conflict") Hint("원장과 서버 영수증 함께 검토 · 자동 확정하지 않아요")
            }
            Section("변경 이력")
            ledger.filter { it.chargeId == charge.id }.forEach { entry ->
                val name = members.firstOrNull { it.id == entry.memberId }?.name ?: "회원 삭제됨"
                CardLine("$name · ${entry.kind}", "%+,d원 · ${entry.reason} · ${entry.actor}".format(entry.deltaWon), onClick = { reversalId = entry.id })
            }
        }
    }
    if (addCharge) {
        var title by rememberSaveable { mutableStateOf("") }
        var due by rememberSaveable { mutableStateOf("") }
        var linkedEventId by rememberSaveable { mutableStateOf<String?>(null) }
        var discard by remember { mutableStateOf(false) }
        fun requestDismiss() { if (title.isNotBlank() || due.isNotBlank() || linkedEventId != null) discard = true else addCharge = false }
        AlertDialog(onDismissRequest = { if (!chargeSaving) requestDismiss() }, title = { Text("회비 항목 추가") }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) { OutlinedTextField(title, { title = it }, label = { Text("항목명 *") }); OutlinedTextField(due, { due = it }, label = { Text("기한 · 선택") }); Hint("관련 행사 · 선택") ; Row(Modifier.horizontalScroll(rememberScrollState())) { FilterChip(linkedEventId == null, { linkedEventId = null }, label = { Text("학기 회비") }); events.forEach { event -> FilterChip(linkedEventId == event.id, { linkedEventId = event.id }, label = { Text(event.title) }) } }; if (chargeError.isNotBlank()) Text(chargeError, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!chargeSaving) { chargeSaving = true; val charge = Charge(title = title.trim(), dueDate = due.trim(), eventId = linkedEventId); scope.launch { try { commitSave({ onCharge(charge) }, { addCharge = false }, { chargeError = it }) } finally { chargeSaving = false } } } }, enabled = title.isNotBlank() && !chargeSaving) { Text("저장") } }, dismissButton = { TextButton(onClick = ::requestDismiss, enabled = !chargeSaving) { Text("취소") } })
        DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; addCharge = false })
    }
    if (addEntry && selected != null) LedgerDialog(selected!!, members, ledger, onDismiss = { addEntry = false }, onSave = onEntry)
    if (reversalId != null) {
        val entry = ledger.firstOrNull { it.id == reversalId }
        var reason by rememberSaveable { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var discard by remember { mutableStateOf(false) }
        fun requestDismiss() { if (reason.isNotBlank()) discard = true else reversalId = null }
        if (entry != null) AlertDialog(onDismissRequest = { if (!saving) requestDismiss() }, title = { Text("원장 기록 되돌리기") }, text = { Column { Text("${entry.kind} %,d원 기록을 반대 원장 기록으로 정정합니다. 원본은 보존돼요".format(entry.deltaWon)); OutlinedTextField(reason, { reason = it }, label = { Text("정정 사유 *") }); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; scope.launch { try { commitSave({ dao.appendReversal(entry.id, reason.trim()) }, { reversalId = null; onMessage("원본을 보존하고 정정 원장을 추가했어요") }, { error = it }) } finally { saving = false } } } }, enabled = !saving && reason.isNotBlank()) { Text("정정 기록") } }, dismissButton = { TextButton(onClick = ::requestDismiss, enabled = !saving) { Text("취소") } })
        DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; reversalId = null })
    }
    if (reviewingRequestId != null && selected != null) {
        val request = paymentRequests.firstOrNull { it.optString("id") == reviewingRequestId }
        if (request != null) {
        val charge = selected
        val member = members.firstOrNull { it.id == request.optString("memberId") }
        val eventId = charge.eventId
        var amountText by rememberSaveable(request.optString("id")) { mutableStateOf(request.optLong("amount").toString()) }
        var reason by rememberSaveable(request.optString("id")) { mutableStateOf("") }
        var bankChecked by rememberSaveable(request.optString("id")) { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) reviewingRequestId = null }, title = { Text(if (reviewMode == "confirm") "실제 입금 확인" else "입금 요청 반려") }, text = { Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
            CardLine("${member?.name ?: "회원 연결 미확인"} · ${charge.title}", "요청 %,d원 · 입금자 ${request.optString("depositorName")} · 시각 ${request.optString("depositedAt")}".format(request.optLong("amount")))
            if (reviewMode == "confirm") { OutlinedTextField(amountText, { amountText = it }, label = { Text("은행에서 확인한 금액 원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)); Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(bankChecked, { bankChecked = it }); Text("은행 입금 내역을 직접 확인했어요") } }
            OutlinedTextField(reason, { reason = it }, label = { Text(if (reviewMode == "confirm") "확인 근거·메모 *" else "반려 사유 *") })
            Hint("회원 요청만으로 납부 완료가 되지 않아요. 확인 결과와 원장 기록을 별도로 남깁니다")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        } }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val requestId = request.optString("id"); scope.launch { try { commitSave({
            require(eventId != null && member != null) { "행사와 연결된 회원을 확인해 주세요" }
            if (reviewMode == "confirm") {
                require(bankChecked) { "실제 은행 입금을 확인해 주세요" }
                val amount = amountText.toLongOrNull() ?: 0
                requireLedgerEntry("납부 확인", amount, reason.trim())
                try { engine.confirmPayment(eventId, requestId, charge.id, amount, reason.trim()); onMessage("은행 확인 결과를 원장과 서버에 기록했어요") }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    val queued = dao.mutationCount(eventId, "payment-prepare", requestId) + dao.mutationCount(eventId, "payment-confirm", requestId) > 0 || dao.ledgerForRequest(requestId) != null
                    if (!queued) throw e
                    onMessage("납부 검토가 대기 중이에요. 원장과 서버 영수증을 확인해 주세요")
                }
            } else { engine.rejectPayment(eventId, requestId, reason.trim()); try { engine.syncAll(); onMessage("반려 결과를 서버에서 확인했어요") } catch (e: CancellationException) { throw e } catch (_: Exception) { onMessage("반려 작업을 대기함에 저장했어요") } }
        }, { reviewingRequestId = null }, { error = it }) } finally { saving = false } } } }, enabled = !saving && reason.isNotBlank() && (reviewMode != "confirm" || bankChecked)) { Text(if (reviewMode == "confirm") "확인 기록" else "반려 기록") } }, dismissButton = { TextButton(onClick = { reviewingRequestId = null }, enabled = !saving) { Text("취소") } })
        }
    }
    if (receiptRequestId != null && selected != null) {
        val request = paymentRequests.firstOrNull { it.optString("id") == receiptRequestId }
        val review = request?.optJSONObject("review")
        if (request != null && review != null) {
            val linkedCharge = charges.firstOrNull { it.id == review.optString("chargeId") }
            val member = members.firstOrNull { it.id == request.optString("memberId") }
            val ownerMatches = review.optString("ownerId") == clubConfig?.ownerId
            var saving by remember { mutableStateOf(false) }
            var error by remember { mutableStateOf("") }
            AlertDialog(onDismissRequest = { if (!saving) receiptRequestId = null }, title = { Text("원장·서버 영수증 검토") }, text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardLine("${member?.name ?: "회원 연결 미확인"} · ${linkedCharge?.title ?: "회비 항목 미확인"}", "서버 상태 ${review.optString("state")} · 확인 금액 %,d원".format(review.optLong("confirmedAmount")))
                    Hint("영수증 원장 ID ${review.optString("ledgerEntryId").take(12)} · 사유 ${review.optString("reason")}")
                    if (!ownerMatches) Text("⚠ 이 기기 운영자 ID와 영수증 소유자가 다릅니다. 복구할 수 없어요", color = MaterialTheme.colorScheme.error)
                    Hint("예약 영수증은 서버 확정 전일 수 있어요. 같은 원장 ID를 검증하고 누락된 기록만 복구합니다")
                    if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
                }
            }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val eventId = selected.eventId; val requestId = request.optString("id"); scope.launch { try { commitSave({ require(eventId != null) { "행사 연결을 확인해 주세요" }; engine.reconcilePayment(eventId, requestId) }, { receiptRequestId = null; onMessage("영수증과 원장을 다시 확인했어요. 대기 작업 상태를 확인해 주세요") }, { error = it }) } finally { saving = false } } } }, enabled = !saving && ownerMatches && member != null && linkedCharge != null && review.optLong("confirmedAmount") > 0) { Text("검증 후 복구") } }, dismissButton = { TextButton(onClick = { receiptRequestId = null }, enabled = !saving) { Text("취소") } })
        }
    }
}

@Composable private fun LedgerDialog(charge: Charge, members: List<Member>, ledger: List<LedgerEntry>, onDismiss: () -> Unit, onSave: suspend (LedgerEntry) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var memberId by rememberSaveable { mutableStateOf(members.firstOrNull()?.id ?: "") }
    var kind by rememberSaveable { mutableStateOf("청구") }
    var amount by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var bankChecked by rememberSaveable { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    fun requestDismiss() { if (amount.isNotBlank() || reason.isNotBlank()) discard = true else onDismiss() }
    AlertDialog(onDismissRequest = { if (!saving) requestDismiss() }, title = { Text("${charge.title} · 원장 기록") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("회원 선택")
            Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) { members.forEach { member -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(memberId == member.id, { memberId = member.id }); Text(member.name) } } }
            Hint("유형")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("청구", "납부 확인", "면제").forEach { FilterChip(kind == it, { kind = it }, label = { Text(it, fontSize = 12.sp) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("환불", "정정 증가", "정정 감소").forEach { FilterChip(kind == it, { kind = it }, label = { Text(it, fontSize = 12.sp) }) } }
            OutlinedTextField(amount, { amount = it }, label = { Text("금액 원 *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(reason, { reason = it }, label = { Text("이유 *") })
            if (kind == "납부 확인") Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(bankChecked, { bankChecked = it }); Text("실제 은행 입금을 확인했어요") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        if (saving) return@TextButton
        try {
            require(memberId.isNotBlank()) { "먼저 회원을 등록해 주세요" }
            val delta = requireLedgerEntry(kind, amount.toLongOrNull() ?: 0, reason.trim())
            require(kind != "납부 확인" || bankChecked) { "실제 은행 입금 내역을 확인해 주세요" }
            val current = balance(charge.id, memberId, ledger)
            require(kind !in listOf("납부 확인", "면제") || -delta <= current) { "현재 남은 금액보다 클 수 없어요. 정정 기록으로 차이를 남겨 주세요" }
            val previous = ledger.filter { it.chargeId == charge.id && it.memberId == memberId }
            require(kind != "환불" || delta <= -previous.filter { it.kind == "납부 확인" }.sumOf { it.deltaWon } - previous.filter { it.kind == "환불" }.sumOf { it.deltaWon }) { "확인 납부액보다 환불액이 클 수 없어요" }
            val entry = LedgerEntry(chargeId = charge.id, memberId = memberId, kind = kind, deltaWon = delta, reason = reason.trim())
            saving = true
            scope.launch { try { commitSave({ onSave(entry) }, onDismiss, { error = it }) } finally { saving = false } }
        } catch (e: IllegalArgumentException) { error = e.message ?: "입력을 확인해 주세요" }
    }, enabled = !saving) { Text("원장에 기록") } }, dismissButton = { TextButton(onClick = ::requestDismiss, enabled = !saving) { Text("취소") } })
    DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; onDismiss() })
}

private val builtInTemplates = listOf(
    MessageTemplate(id = "event-formal", title = "행사 안내 · 정중", body = "{이름}님, {행사명} 행사 안내입니다. 일시: {일시}, 장소: {장소}."),
    MessageTemplate(id = "event-friendly", title = "행사 안내 · 친근", body = "{이름}님! {행사명}에 함께해요. {일시}, {장소}에서 만나요."),
    MessageTemplate(id = "fee-formal", title = "회비 안내 · 정중", body = "{이름}님, {납부항목}의 남은 금액은 {남은금액}입니다. 확인 부탁드립니다."),
    MessageTemplate(id = "fee-friendly", title = "회비 안내 · 친근", body = "{이름}님, {납부항목} 남은 금액 {남은금액} 확인 부탁드려요!"),
    MessageTemplate(id = "deadline-formal", title = "신청 마감 · 정중", body = "{이름}님, {행사명} 신청 마감은 {마감}입니다. 확인 부탁드립니다."),
    MessageTemplate(id = "deadline-friendly", title = "신청 마감 · 친근", body = "{이름}님, {행사명} 신청이 {마감}에 마감돼요. 함께해요!"),
    MessageTemplate(id = "reply-formal", title = "미응답 안내 · 정중", body = "{이름}님, {행사명} 참석 여부를 확인 부탁드립니다."),
    MessageTemplate(id = "reply-friendly", title = "미응답 안내 · 친근", body = "{이름}님, {행사명} 참석 여부 알려 주실 수 있나요?"),
    MessageTemplate(id = "interview-formal", title = "면접 안내 · 정중", body = "{이름}님, 면접 일정은 {일시}, 장소는 {장소}입니다. 확인 부탁드립니다."),
    MessageTemplate(id = "interview-friendly", title = "면접 안내 · 친근", body = "{이름}님, {일시} {장소}에서 면접으로 만나요!"),
    MessageTemplate(id = "change-formal", title = "일정 변경 · 정중", body = "{이름}님, {행사명} 일정이 {일시}로 변경됐습니다. 확인 부탁드립니다."),
    MessageTemplate(id = "change-friendly", title = "일정 변경 · 친근", body = "{이름}님, {행사명} 일정이 {일시}로 바뀌었어요. 확인해 주세요!"),
    MessageTemplate(id = "thanks-formal", title = "감사 안내 · 정중", body = "{이름}님, 함께해 주셔서 감사합니다."),
    MessageTemplate(id = "thanks-friendly", title = "감사 안내 · 친근", body = "{이름}님, 함께해 주셔서 정말 고마워요!"),
    MessageTemplate(id = "preparation-formal", title = "준비물 안내 · 정중", body = "{이름}님, {행사명} 준비물을 확인해 주시기 바랍니다."),
    MessageTemplate(id = "preparation-friendly", title = "준비물 안내 · 친근", body = "{이름}님, {행사명} 준비물 확인해 주세요!"),
)

private fun responseRowsFromSnapshot(json: String): List<Rsvp> = runCatching {
    val rows = JSONObject(json).optJSONArray("submissions") ?: return@runCatching emptyList()
    (0 until rows.length()).mapNotNull { index ->
        val item = rows.optJSONObject(index) ?: return@mapNotNull null
        val memberId = item.optString("memberId")
        if (memberId.isBlank() || memberId == "null") null else Rsvp(
            eventId = item.optString("eventId"), memberId = memberId,
            response = when (item.optString("rsvp")) { "attending" -> "참석"; "declined" -> "불참"; else -> "미정" },
        )
    }
}.getOrDefault(emptyList())

private fun idsJson(ids: List<String>): String = JSONArray(ids).toString()
private fun idsFromJson(json: String): List<String> = runCatching {
    val array = JSONArray(json)
    (0 until array.length()).map { array.getString(it) }
}.getOrDefault(emptyList())

private suspend fun currentCommonUrls(dao: AppDao): Set<String> {
    val pending = dao.pendingMutations().first()
    return dao.invites().first().filter { invite ->
        invite.memberId.isBlank() && invite.url.startsWith("https://") &&
            runCatching { java.time.Instant.parse(invite.expiresAt).isAfter(java.time.Instant.now()) }.getOrDefault(false) &&
            pending.none { it.kind == "revoke-link" && it.targetId == invite.id }
    }.map { it.url }.toSet()
}

@Composable private fun ContactScreen(members: List<Member>, events: List<Event>, charges: List<Charge>, ledger: List<LedgerEntry>, rsvps: List<Rsvp>, invites: List<Invite>, pendingMutations: List<PendingMutation>, templates: List<MessageTemplate>, contacts: List<ContactRecord>, reminders: List<Reminder>, drafts: List<ContactDraft>, clubConfig: ClubConfig?, semester: String, dao: AppDao, preselectedIds: List<String>, requestedDraftId: String?, discardEpoch: Int, onEditingChange: (Boolean) -> Unit, onSelectionConsumed: () -> Unit, onRequestedDraftConsumed: () -> Unit, onTemplate: suspend (MessageTemplate) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var section by rememberSaveable { mutableStateOf("준비 중") }
    var selectedTemplate by remember { mutableStateOf<MessageTemplate?>(null) }
    var selectedEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedChargeId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedCohort by rememberSaveable { mutableStateOf<String?>(null) }
    var targetPolicy by rememberSaveable { mutableStateOf("all") }
    var selectedMemberIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var excludedMemberIds by rememberSaveable { mutableStateOf(listOf<String>()) }
    var purpose by rememberSaveable { mutableStateOf("직접 작성") }
    var wizardStep by rememberSaveable { mutableIntStateOf(0) }
    var body by rememberSaveable { mutableStateOf("") }
    var publicBody by rememberSaveable { mutableStateOf("") }
    var currentIndex by rememberSaveable { mutableIntStateOf(0) }
    var editTemplateId by rememberSaveable { mutableStateOf<String?>(null) }
    var newTemplate by rememberSaveable { mutableStateOf(false) }
    var newReminder by rememberSaveable { mutableStateOf(false) }
    var draftTitle by rememberSaveable { mutableStateOf("") }
    var activeDraftId by rememberSaveable { mutableStateOf<String?>(null) }
    var allowRepeat by remember { mutableStateOf(false) }
    var smsBusy by remember { mutableStateOf(false) }
    var kakaoFallback by remember { mutableStateOf<String?>(null) }
    var kakaoError by remember { mutableStateOf("") }
    var draftSaving by remember { mutableStateOf(false) }
    var draftError by remember { mutableStateOf("") }
    var handedOffRecordId by remember { mutableStateOf<String?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) onMessage("알림 권한이 없어도 앱의 연락 알림 목록에서 확인할 수 있어요") }
    val selectedEvent = events.firstOrNull { it.id == selectedEventId }
    val selectedCharge = charges.firstOrNull { it.id == selectedChargeId }
    val activeDraft = drafts.firstOrNull { it.id == activeDraftId }
    val responseRows = if ((selectedEvent?.serverVersion ?: 0) > 0) responseRowsFromSnapshot(selectedEvent?.responsesJson.orEmpty()) else rsvps
    val candidates = eligibleMembers(members, ledger, selectedChargeId, selectedGroup, selectedCohort, targetPolicy, selectedEventId, responseRows, semester, selectedMemberIds.toSet(), excludedMemberIds.toSet())
    fun linkFor(memberId: String): String = invites.firstOrNull { invite -> invite.eventId == selectedEventId && invite.memberId == memberId && invite.url.isNotBlank() && runCatching { java.time.Instant.parse(invite.expiresAt).isAfter(java.time.Instant.now()) }.getOrDefault(false) && pendingMutations.none { it.eventId == invite.eventId && (it.kind == "revoke-link" && it.targetId == invite.id || it.kind == "revoke-member" && it.targetId == memberId) } && members.firstOrNull { it.id == memberId }?.status != "탈퇴" }?.url.orEmpty()
    val missingTargets = candidates.count { renderTemplate(body, it, selectedEvent, selectedCharge, ledger, clubConfig?.accountGuide.orEmpty(), linkFor(it.id)).missing.isNotEmpty() }
    val shownIndex = currentIndex.coerceAtMost((candidates.size - 1).coerceAtLeast(0))
    val target = candidates.getOrNull(shownIndex)
    val personalLink = target?.let { linkFor(it.id) }.orEmpty()
    val rendered = target?.let { renderTemplate(body, it, selectedEvent, selectedCharge, ledger, clubConfig?.accountGuide.orEmpty(), personalLink) }
    val draftDirty = body != activeDraft?.body.orEmpty() || draftTitle != activeDraft?.title.orEmpty()
    DiscardOnEpoch(discardEpoch) { editTemplateId = null; newTemplate = false; newReminder = false; kakaoFallback = null; wizardStep = 0; if (draftDirty) { body = activeDraft?.body.orEmpty(); draftTitle = activeDraft?.title.orEmpty(); publicBody = "" } }
    TrackEditor(editTemplateId != null || newTemplate || newReminder || kakaoFallback != null || draftDirty || publicBody.isNotBlank(), onEditingChange)
    val allowedCommonUrls = invites.filter { invite -> invite.memberId.isBlank() && invite.url.startsWith("https://") && runCatching { java.time.Instant.parse(invite.expiresAt).isAfter(java.time.Instant.now()) }.getOrDefault(false) && pendingMutations.none { it.kind == "revoke-link" && it.targetId == invite.id } }.map { it.url }.toSet()
    LaunchedEffect(target?.id, body) { allowRepeat = false }
    LaunchedEffect(preselectedIds) { if (preselectedIds.isNotEmpty()) { selectedMemberIds = preselectedIds; targetPolicy = "selected"; currentIndex = 0; section = "준비 중"; wizardStep = 1; onSelectionConsumed() } }
    LaunchedEffect(requestedDraftId, drafts) {
        val requested = drafts.firstOrNull { it.id == requestedDraftId }
        if (requested != null) {
            activeDraftId = requested.id; draftTitle = requested.title; body = requested.body
            selectedEventId = requested.eventId; selectedChargeId = requested.chargeId; selectedGroup = requested.groupName
            targetPolicy = requested.targetPolicy; selectedCohort = requested.cohortFilter; selectedMemberIds = idsFromJson(requested.selectedMemberIdsJson)
            excludedMemberIds = idsFromJson(requested.excludedMemberIdsJson); purpose = requested.purpose
            currentIndex = requested.currentIndex; section = "준비 중"; wizardStep = 3; onRequestedDraftConsumed()
        }
    }
    Page {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("준비 중", "연락 알림", "기록", "템플릿").forEach { FilterChip(section == it, { section = it }, label = { Text(it, fontSize = 12.sp) }) } }
        when (section) {
            "연락 알림" -> {
                Section("연락 알림")
                Hint("정확한 시간이나 실제 발송을 보장하지 않아요. 알림은 운영자에게만 표시돼요")
                Action("현재 초안 알림 추가", { newReminder = true }, enabled = activeDraftId != null)
                if (activeDraftId == null) Hint("먼저 준비 중에서 연락 초안을 저장해 주세요")
                if (reminders.isEmpty()) Hint("예약한 알림이 없어요")
                reminders.forEach { reminder ->
                    val state = if (reminder.state == "대기" && reminder.atMillis < System.currentTimeMillis()) "시간 지남 · 확인 필요" else reminder.state
                    CardLine(reminder.title, "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(reminder.atMillis))} · $state", onClick = reminder.draftId?.let { id -> ({ activeDraftId = id; section = "준비 중" }) })
                }
            }
            "기록" -> {
                Section("연락 기록")
                Hint("앱 전달과 사용자 발송 확인은 실제 전달·수신 확인과 다릅니다")
                if (contacts.isEmpty()) Hint("연락 기록이 없어요")
                contacts.forEach { record ->
                    CardLine("${record.memberName} · ${record.channel}", "${record.state} · ${record.bodySnapshot.take(70)}")
                    if (record.state == "앱 전달됨") TextButton(onClick = { scope.launch { try { dao.updateContact(record.id, "사용자 발송 확인") } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage("연락 상태 저장 실패 · ${e.message}") } } }) { Text("발송했다고 표시") }
                }
            }
            "템플릿" -> {
                Section("템플릿")
                Hint("AI 호출 없음 · 저장한 문구와 실제 데이터로 개인별 내용을 만듭니다")
                Action("내 템플릿 추가", { newTemplate = true })
                (builtInTemplates + templates).forEach { template -> CardLine(template.title, template.body, onClick = { if (template.id in builtInTemplates.map { it.id }) { selectedTemplate = template; body = template.body; section = "준비 중" } else editTemplateId = template.id }) }
            }
            else -> {
                Section("연락 준비")
                Hint("현재 활동·수신 설정·번호·회비 잔액을 기준으로 대상자를 계산합니다. 문자 앱에서 직접 전송해 주세요")
                Hint("${listOf("1 목적", "2 대상", "3 문구", "4 최종 검토")[wizardStep]} · 현재 자료로 대상과 문구를 다시 확인합니다")
                if (wizardStep > 0) TextButton(onClick = { wizardStep-- }) { Text("이전 단계") }
                Section("목적")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("행사 안내", "회비 안내", "미응답 안내", "직접 작성").forEach { choice -> FilterChip(purpose == choice, { purpose = choice }, label = { Text(choice) }) }
                }
                if (drafts.isNotEmpty()) {
                    Section("저장한 초안")
                    TextButton(onClick = { activeDraftId = null; draftTitle = ""; body = ""; selectedChargeId = null; selectedEventId = null; selectedGroup = null; selectedCohort = null; targetPolicy = "all"; selectedMemberIds = emptyList(); excludedMemberIds = emptyList(); currentIndex = 0; wizardStep = 0 }) { Text("새 초안") }
                    drafts.forEach { draft -> CardLine(draft.title, "대상은 열 때 다시 계산 · ${draft.body.take(40)}", onClick = {
                        activeDraftId = draft.id; draftTitle = draft.title; body = draft.body
                        selectedEventId = draft.eventId; selectedChargeId = draft.chargeId
                        selectedGroup = draft.groupName; currentIndex = draft.currentIndex
                        targetPolicy = draft.targetPolicy; selectedMemberIds = idsFromJson(draft.selectedMemberIdsJson)
                        excludedMemberIds = idsFromJson(draft.excludedMemberIdsJson); purpose = draft.purpose; selectedCohort = draft.cohortFilter
                        wizardStep = 3
                    }) }
                }
                OutlinedTextField(draftTitle, { draftTitle = it }, label = { Text("초안 제목") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    if (draftSaving) return@OutlinedButton
                    draftSaving = true
                    val existingDraft = activeDraft
                    val draft = ContactDraft(id = existingDraft?.id ?: newId(), title = draftTitle.trim(), body = body, eventId = selectedEventId, chargeId = selectedChargeId, groupName = selectedGroup, currentIndex = currentIndex, targetPolicy = targetPolicy, selectedMemberIdsJson = idsJson(selectedMemberIds), excludedMemberIdsJson = idsJson(excludedMemberIds), purpose = purpose, cohortFilter = selectedCohort)
                    scope.launch { try { commitSave({ if (existingDraft == null) dao.put(draft) else dao.updateDraftTargeted(draft.id, draft.title, draft.body, draft.eventId, draft.chargeId, draft.groupName, draft.currentIndex, draft.targetPolicy, draft.selectedMemberIdsJson, draft.excludedMemberIdsJson, draft.purpose, cohortFilter = draft.cohortFilter) }, { activeDraftId = draft.id; draftError = ""; onMessage("초안을 이 기기에 저장했어요") }, { draftError = it }) } finally { draftSaving = false } }
                }, enabled = draftTitle.isNotBlank() && body.isNotBlank() && !draftSaving, modifier = Modifier.fillMaxWidth()) { Text("초안 저장") }
                if (draftError.isNotBlank()) Text(draftError, color = MaterialTheme.colorScheme.error)
                if (wizardStep == 0) Action("대상 선택", { wizardStep = 1 })
                if (wizardStep >= 1) {
                Section("대상 조건")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("all" to "전체 활동", "unpaid" to "미납", "unanswered" to "미응답", "new" to "신입", "selected" to "직접 선택").forEach { (policy, label) ->
                        FilterChip(targetPolicy == policy, { targetPolicy = policy; if (policy != "selected") selectedMemberIds = emptyList(); currentIndex = 0 }, label = { Text(label) })
                    }
                }
                if (targetPolicy == "selected") {
                    Hint("선택 ${selectedMemberIds.size}명 · 선택한 범위 밖으로 자동 확대하지 않아요")
                    members.filter { it.status == "활동" }.forEach { member -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(member.id in selectedMemberIds, { checked -> selectedMemberIds = if (checked) selectedMemberIds + member.id else selectedMemberIds - member.id })
                        Text(member.name)
                    } }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selectedChargeId == null, { selectedChargeId = null; currentIndex = 0 }, label = { Text("전체") })
                    charges.forEach { charge -> FilterChip(selectedChargeId == charge.id, { selectedChargeId = charge.id; currentIndex = 0 }, label = { Text(charge.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
                val groups = members.map { it.groupName }.filter(String::isNotBlank).distinct()
                if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selectedGroup == null, { selectedGroup = null; currentIndex = 0 }, label = { Text("모든 소모임") })
                    groups.forEach { group -> FilterChip(selectedGroup == group, { selectedGroup = group; currentIndex = 0 }, label = { Text(group) }) }
                }
                val cohorts = members.map(Member::cohort).filter(String::isNotBlank).distinct()
                if (cohorts.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selectedCohort == null, { selectedCohort = null; currentIndex = 0 }, label = { Text("모든 기수") })
                    cohorts.forEach { value -> FilterChip(selectedCohort == value, { selectedCohort = value; currentIndex = 0 }, label = { Text(value) }) }
                }
                Hint("대상 ${candidates.size}명 · 제외 ${members.size - candidates.size}명 (탈퇴·수신 제외·번호 오류·중복 번호${if (selectedCharge != null) "·완납" else ""})")
                if (targetPolicy == "unanswered" && (selectedEvent?.serverVersion ?: 0) > 0) Hint("서버 응답 마지막 확인: ${selectedEvent?.lastSyncAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)) } ?: "없음"} · 문자 앱을 열기 전에 최신 응답을 다시 확인합니다")
                if (missingTargets > 0) Text("⚠ $missingTargets 명의 템플릿 정보가 부족해요. 변수·개인 링크를 확인하거나 대상을 제외해 주세요", color = MaterialTheme.colorScheme.error)
                if (excludedMemberIds.isNotEmpty()) TextButton(onClick = { excludedMemberIds = emptyList() }) { Text("개별 제외 ${excludedMemberIds.size}명 되돌리기") }
                if (events.isNotEmpty()) {
                    Section("관련 행사")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selectedEventId == null, { selectedEventId = null }, label = { Text("없음") })
                        events.forEach { event -> FilterChip(selectedEventId == event.id, { selectedEventId = event.id }, label = { Text(event.title) }) }
                    }
                }
                if (wizardStep == 1) Action("문구 작성", { wizardStep = 2 }, enabled = candidates.isNotEmpty())
                }
                if (wizardStep >= 2) {
                Section("문구")
                (builtInTemplates + templates).take(8).forEach { template -> if (selectedTemplate?.id == template.id) Hint("선택: ${template.title}") }
                OutlinedButton(onClick = { section = "템플릿" }, modifier = Modifier.fillMaxWidth()) { Text("템플릿 선택") }
                OutlinedTextField(body, { body = it; selectedTemplate = null }, label = { Text("개인 안내 문구") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                if (wizardStep == 2) Action("최종 검토", { wizardStep = 3 }, enabled = body.isNotBlank() && candidates.isNotEmpty() && missingTargets == 0)
                }
                if (wizardStep >= 3) {
                if (target == null) Hint("연락 가능한 회원을 등록하거나 대상 조건을 바꿔 주세요") else {
                    Section("개인별 최종 미리보기")
                    Hint("${shownIndex + 1}/${candidates.size} ${target.name} · 문자 대상 ${target.phone}")
                    CardLine("현재 문구", rendered?.text ?: "")
                    if (rendered?.missing?.isNotEmpty() == true) Text("⚠ 미치환 정보: ${rendered.missing.joinToString()}", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { excludedMemberIds = excludedMemberIds + target.id; currentIndex = 0 }) { Text("이 대상 제외") }
                    val sameMessage = contacts.any { it.memberId == target.id && it.bodySnapshot == rendered?.text && it.channel == "문자 앱" }
                    if (sameMessage) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowRepeat, { allowRepeat = it })
                        Text("같은 문구를 다시 준비하기", fontSize = 14.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { currentIndex = (currentIndex - 1).coerceAtLeast(0) }) { Text("이전") }
                        TextButton(onClick = { currentIndex = (currentIndex + 1).coerceAtMost(candidates.lastIndex) }) { Text("다음 대상") }
                    }
                    Action("문자 앱에서 보내기", enabled = !smsBusy && body.isNotBlank() && missingTargets == 0 && rendered?.missing?.isEmpty() == true, onClick = {
                        if (smsBusy) return@Action
                        smsBusy = true
                        val expectedId = target.id
                        val draftBody = body
                        val chargeId = selectedChargeId
                        val group = selectedGroup
                        val cohort = selectedCohort
                        val eventId = selectedEventId
                        val policy = targetPolicy
                        val scopedIds = selectedMemberIds.toSet()
                        val excludedIds = excludedMemberIds.toSet()
                        val previewText = rendered?.text
                        val repeatAllowed = allowRepeat
                        scope.launch {
                            var handedOff = false
                            try {
                                val liveEvent = eventId?.let { dao.eventById(it) }
                                if (policy == "unanswered" && (liveEvent?.serverVersion ?: 0) > 0) {
                                    try { SyncEngine(context).syncAll() }
                                    catch (e: CancellationException) { throw e }
                                    catch (_: Exception) { onMessage("최신 참석 응답을 확인하지 못했어요. 동기화 후 다시 준비해 주세요"); return@launch }
                                }
                                val event = eventId?.let { dao.eventById(it) }
                                val currentLedger = dao.ledgerSnapshot()
                                val currentRsvps = if ((event?.serverVersion ?: 0) > 0) responseRowsFromSnapshot(event?.responsesJson.orEmpty()) else eventId?.let { dao.rsvpsFor(it) }.orEmpty()
                                val fresh = eligibleMembers(dao.memberSnapshot(), currentLedger, chargeId, group, cohort, policy, eventId, currentRsvps, semester, scopedIds, excludedIds).firstOrNull { it.id == expectedId }
                                if (fresh == null) { onMessage("대상 상태가 바뀌었어요. 목록을 다시 확인해 주세요"); return@launch }
                                val charge = chargeId?.let { dao.chargeById(it) }
                                val currentInvite = eventId?.let { dao.inviteFor(it, fresh.id) }
                                val currentMutations = dao.pendingMutations().first()
                                val link = currentInvite?.takeIf { invite -> invite.url.isNotBlank() && java.time.Instant.parse(invite.expiresAt).isAfter(java.time.Instant.now()) && currentMutations.none { it.eventId == invite.eventId && (it.kind == "revoke-link" && it.targetId == invite.id || it.kind == "revoke-member" && it.targetId == fresh.id) } }?.url.orEmpty()
                                val final = renderTemplate(draftBody, fresh, event, charge, currentLedger, dao.clubConfigSnapshot()?.accountGuide.orEmpty(), link)
                                if (final.missing.isNotEmpty()) { onMessage("누락 정보: ${final.missing.joinToString()}"); return@launch }
                                if (final.text != previewText) { onMessage("회원 정보나 문구가 바뀌었어요. 갱신된 미리보기를 확인한 뒤 다시 열어 주세요"); return@launch }
                                if (!repeatAllowed && dao.contactSnapshot().any { it.memberId == fresh.id && it.bodySnapshot == final.text && it.channel == "문자 앱" }) { onMessage("같은 문구를 이미 전달했어요. 재준비하려면 체크해 주세요"); return@launch }
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", normalizePhone(fresh.phone), null)).putExtra("sms_body", final.text))
                                handedOff = true
                                val record = ContactRecord(memberId = fresh.id, memberName = fresh.name, phoneSnapshot = fresh.phone, bodySnapshot = final.text, channel = "문자 앱", state = "앱 전달됨")
                                dao.put(record)
                                handedOffRecordId = record.id
                                allowRepeat = false
                                onMessage("문자 앱을 열었어요. 실제 전송 여부는 확인되지 않았어요")
                            } catch (_: ActivityNotFoundException) { onMessage("문자 앱을 찾지 못했어요. 내용을 복사해 사용해 주세요") }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { onMessage(if (handedOff) "문자 앱은 열렸지만 연락 기록 저장에 실패했어요. 재시도 전 실제 발송 여부를 확인해 주세요" else "문자 준비 오류 · ${e.message ?: "다시 시도해 주세요"}") }
                            finally { smsBusy = false }
                        }
                    })
                    val handoff = contacts.firstOrNull { it.id == handedOffRecordId }
                    if (handoff != null) {
                        Hint("${handoff.memberName} · ${handoff.state}. 문자 앱에서 실제 발송했는지는 앱이 확인할 수 없어요")
                        if (handoff.state == "앱 전달됨") OutlinedButton(onClick = { scope.launch { try { dao.updateContact(handoff.id, "사용자 발송 확인") } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage("확인 기록 저장 실패 · ${e.message}") } } }, modifier = Modifier.fillMaxWidth()) { Text("발송했다고 표시") }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { handedOffRecordId = null }) { Text("나중에") }
                            TextButton(onClick = { currentIndex = (shownIndex + 1).coerceAtMost(candidates.lastIndex); handedOffRecordId = null }) { Text("다음 대상") }
                        }
                    }
                    OutlinedButton(onClick = { copyText(context, rendered?.text ?: ""); onMessage("개인 안내 문구를 복사했어요. 발송 여부는 확인되지 않았어요") }, enabled = rendered?.missing?.isEmpty() == true, modifier = Modifier.fillMaxWidth()) { Text("개인 문구 복사") }
                }
                Section("공통 공지")
                Hint("카카오톡·일반 공유는 공통 문구만 사용해요. 이름·개인 잔액·전화번호·개인 링크를 넣지 마세요")
                OutlinedTextField(publicBody, { publicBody = it }, label = { Text("공통 안내 문구") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                val publicSafe = publicShareSafe(publicBody, members, allowedCommonUrls)
                if (publicBody.isNotBlank() && !publicSafe) Text("⚠ 이름·금액·전화번호·개인 링크를 제거해 주세요", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    val snapshot = publicBody
                    scope.launch {
                    try {
                    val currentUrls = currentCommonUrls(dao)
                    if (!publicShareSafe(snapshot, dao.memberSnapshot(), currentUrls)) { onMessage("공통 링크가 취소·만료됐거나 개인정보가 있어 공유하지 않았어요"); return@launch }
                    if (BuildConfig.KAKAO_APP_KEY.isBlank() || BuildConfig.KAKAO_SHARE_URL.isBlank()) shareCommonWithAndroid(context, snapshot, dao, scope, onMessage)
                    else try {
                        if (!ShareClient.instance.isKakaoTalkSharingAvailable(context)) { kakaoError = "카카오톡 공유를 사용할 수 없어요"; kakaoFallback = snapshot }
                        else {
                            val shareUrl = kakaoShareLink(snapshot, currentUrls, BuildConfig.KAKAO_SHARE_URL)
                            ShareClient.instance.shareDefault(context, TextTemplate(text = snapshot, link = Link(webUrl = shareUrl, mobileWebUrl = shareUrl))) { result, error ->
                                if (error != null || result == null) { kakaoError = error?.message ?: "카카오톡 공유 결과를 받지 못했어요"; kakaoFallback = snapshot }
                                else try {
                                    context.startActivity(result.intent)
                                    scope.launch {
                                        try { dao.put(ContactRecord(memberId = "", memberName = "공통 공지", phoneSnapshot = "", bodySnapshot = snapshot, channel = "카카오톡 공유", state = "앱 전달됨")) }
                                        catch (e: CancellationException) { throw e } catch (_: Exception) { onMessage("카카오톡 화면은 열렸지만 연락 기록 저장에 실패했어요") }
                                    }
                                    onMessage("카카오톡 공유 화면을 열었어요. 실제 전송 여부는 확인되지 않았어요")
                                } catch (e: ActivityNotFoundException) { kakaoError = e.message ?: "카카오톡 공유 화면을 열지 못했어요"; kakaoFallback = snapshot }
                            }
                        }
                    } catch (e: Exception) { kakaoError = e.message ?: "카카오톡 공유를 시작하지 못했어요"; kakaoFallback = snapshot }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { onMessage("공유 전 대상 확인 실패 · ${e.message}") }
                    }
                }, enabled = publicSafe, modifier = Modifier.fillMaxWidth()) { Text(if (BuildConfig.KAKAO_APP_KEY.isNotBlank() && BuildConfig.KAKAO_SHARE_URL.isNotBlank()) "카카오톡으로 공유" else "공통 문구 공유") }
                OutlinedButton(onClick = { val snapshot = publicBody; scope.launch { try { if (publicShareSafe(snapshot, dao.memberSnapshot(), currentCommonUrls(dao))) { copyText(context, snapshot); onMessage("공통 공지를 복사했어요") } else onMessage("공통 링크가 취소·만료됐거나 개인정보가 있어 복사하지 않았어요") } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage("복사 전 링크 확인 실패 · ${e.message}") } } }, enabled = publicSafe, modifier = Modifier.fillMaxWidth()) { Text("공통 문구 복사") }
                }
            }
        }
    }
    val editingTemplate = templates.firstOrNull { it.id == editTemplateId }
    if (newTemplate || editTemplateId != null && editingTemplate != null) TemplateDialog(if (newTemplate) null else editingTemplate, onDismiss = { editTemplateId = null; newTemplate = false }, onSave = onTemplate)
    if (newReminder && activeDraftId != null) ReminderDialog(activeDraftId!!, onDismiss = { newReminder = false }, onSave = { reminder ->
        dao.put(reminder)
        scheduleReminder(context, reminder)
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        onMessage("연락 알림을 저장했어요. 실제 발송은 하지 않아요")
    })
    if (kakaoFallback != null) {
        val snapshot = kakaoFallback!!
        AlertDialog(onDismissRequest = { kakaoFallback = null }, title = { Text("카카오톡 공유 오류") }, text = { Text("$kakaoError\n\n일반 Android 공유로 계속할 수 있어요. 실제 전송은 공유 앱에서 직접 확인해 주세요") }, confirmButton = { TextButton(onClick = { kakaoFallback = null; scope.launch { try { if (publicShareSafe(snapshot, dao.memberSnapshot(), currentCommonUrls(dao))) shareCommonWithAndroid(context, snapshot, dao, scope, onMessage) else onMessage("공통 링크가 취소·만료됐거나 개인정보가 있어 공유하지 않았어요") } catch (e: CancellationException) { throw e } catch (e: Exception) { onMessage("공유 전 링크 확인 실패 · ${e.message}") } } }) { Text("일반 공유로 계속") } }, dismissButton = { TextButton(onClick = { kakaoFallback = null }) { Text("닫기") } })
    }
}

@Composable private fun ReminderDialog(draftId: String, onDismiss: () -> Unit, onSave: suspend (Reminder) -> Unit) {
    val scope = rememberCoroutineScope()
    val reminderId = rememberSaveable { newId() }
    var saving by remember { mutableStateOf(false) }
    var title by rememberSaveable { mutableStateOf("") }
    var whenText by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var discard by remember { mutableStateOf(false) }
    fun requestDismiss() { if (title.isNotBlank() || whenText.isNotBlank()) discard = true else onDismiss() }
    AlertDialog(onDismissRequest = { if (!saving) requestDismiss() }, title = { Text("연락 알림") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("내부 제목") })
            OutlinedTextField(whenText, { whenText = it }, label = { Text("시간 YYYY-MM-DD HH:mm") })
            Hint("잠금 화면에 개인 이름·회비·전화번호를 표시하지 않아요")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).apply { isLenient = false }
        val time = runCatching { parser.parse(whenText)?.time }.getOrNull()
        error = when { title.isBlank() -> "제목을 입력해 주세요"; time == null || time <= System.currentTimeMillis() -> "미래 시간을 정확히 입력해 주세요"; else -> "" }
        if (error.isEmpty() && !saving) {
            saving = true
            val reminder = Reminder(id = reminderId, title = title.trim(), atMillis = time!!, draftId = draftId)
            scope.launch { try { commitSave({ onSave(reminder) }, onDismiss, { error = it }) } finally { saving = false } }
        }
    }, enabled = !saving) { Text("알림 저장") } }, dismissButton = { TextButton(onClick = ::requestDismiss, enabled = !saving) { Text("취소") } })
    DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; onDismiss() })
}

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("COM’s Companion 안내", text))
}

private fun shareCommonWithAndroid(context: Context, text: String, dao: AppDao, scope: CoroutineScope, onMessage: (String) -> Unit) {
    try {
        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "공통 공지 공유"))
        scope.launch {
            try { dao.put(ContactRecord(memberId = "", memberName = "공통 공지", phoneSnapshot = "", bodySnapshot = text, channel = "일반 공유", state = "앱 전달됨")) }
            catch (e: CancellationException) { throw e } catch (_: Exception) { onMessage("공유 화면은 열렸지만 연락 기록 저장에 실패했어요") }
        }
        onMessage("공유 화면을 열었어요. 실제 전송 여부는 확인되지 않았어요")
    } catch (_: ActivityNotFoundException) { onMessage("공유 앱이 없어요. 내용을 복사해 사용해 주세요") }
}

@Composable private fun TemplateDialog(existing: MessageTemplate?, onDismiss: () -> Unit, onSave: suspend (MessageTemplate) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var title by rememberSaveable(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var body by rememberSaveable(existing?.id) { mutableStateOf(existing?.body ?: "") }
    var discard by remember { mutableStateOf(false) }
    fun requestDismiss() { if (existing != null || title.isNotBlank() || body.isNotBlank()) discard = true else onDismiss() }
    AlertDialog(onDismissRequest = { if (!saving) requestDismiss() }, title = { Text(if (existing == null) "내 템플릿 추가" else "템플릿 수정") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("제목") })
            OutlinedTextField(body, { body = it }, label = { Text("본문") }, minLines = 4)
            Hint("지원 변수: {이름}, {행사명}, {일시}, {장소}, {납부항목}, {남은금액}, {마감}")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val template = MessageTemplate(id = existing?.id ?: newId(), title = title.trim(), body = body.trim()); scope.launch { try { commitSave({ onSave(template) }, onDismiss, { error = it }) } finally { saving = false } } } }, enabled = title.isNotBlank() && body.isNotBlank() && !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = ::requestDismiss, enabled = !saving) { Text("취소") } })
    DiscardPrompt(discard, onKeep = { discard = false }, onDiscard = { discard = false; onDismiss() })
}

@Composable private fun RosterScreen(database: AppDatabase, serverUrl: String, adminToken: String, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(serverUrl, adminToken) { mutableStateOf<RosterSnapshot?>(null) }
    var preview by remember(serverUrl, adminToken) { mutableStateOf<RosterPreview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    Page {
        TextButton(onClick = onBack, enabled = !busy) { Text("← 설정") }
        Section("COMS 회원 명부 확인")
        Hint("연결된 서버의 회원 명부를 확인한 뒤 이 기기에 적용해요. 이름·번호·기수만 갱신하며 기존 회원의 상태·수신 설정·소모임·학기·역할과 회비 기록은 유지해요")
        OutlinedButton(onClick = {
            if (!busy) {
                busy = true
                scope.launch {
                    try {
                        val fetched = RemoteApi(checkedBaseUrl(serverUrl, BuildConfig.DEBUG), adminToken).roster()
                        val freshPreview = previewRoster(fetched, database.dao().memberSnapshot())
                        snapshot = fetched
                        preview = freshPreview
                        error = ""
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { snapshot = null; preview = null; error = e.message ?: "회원 명부를 확인하지 못했어요" }
                    finally { busy = false }
                }
            }
        }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "확인 중…" else "서버 명부 불러오기") }
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        val loaded = snapshot
        val counts = preview
        if (loaded != null && counts != null) {
            Hint("명부 기준 ${loaded.updatedAt} · ${loaded.members.size}명")
            CardLine("적용 미리보기", "새 회원 ${counts.newCount}명 · 정보 갱신 ${counts.updatedCount}명 · 이 기기에만 있는 회원 ${counts.localOnlyCount}명")
            Hint("번호 누락·형식 오류 ${counts.missingOrInvalidPhoneCount}명 · 중복 번호 ${counts.duplicatePhoneCount}명. 해당 회원은 현재 연락 대상 계산에서 제외돼요. 명부의 번호는 발송 동의를 뜻하지 않아요")
            OutlinedButton(onClick = {
                if (!busy) {
                    busy = true
                    scope.launch {
                        try {
                            val applied = applyRoster(database, loaded)
                            snapshot = null
                            preview = null
                            error = ""
                            onMessage("회원 명부 적용 완료 · 신규 ${applied.newCount}명, 갱신 ${applied.updatedCount}명. 이 기기에만 있는 회원은 유지했어요")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "회원 명부 적용에 실패했어요" }
                        finally { busy = false }
                    }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "적용 중…" else "이 기기에 적용") }
        }
    }
}

@Composable private fun SettingsScreen(database: AppDatabase, config: ClubConfig?, members: List<Member>, events: List<Event>, charges: List<Charge>, ledger: List<LedgerEntry>, pendingMutations: List<PendingMutation>, theme: String, semester: String, serverUrl: String, adminToken: String, syncEnabled: Boolean, onlineStateExists: Boolean, discardEpoch: Int, onEditingChange: (Boolean) -> Unit, onMessage: (String) -> Unit, onOpenTemplates: () -> Unit, onSaveClub: suspend (String, String, String) -> Unit, onManualSync: suspend () -> Unit, onSyncEnabled: suspend (Boolean) -> Unit, onTheme: suspend (String) -> Unit, onSemester: suspend (String) -> Unit, onConnection: suspend (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val engine = remember(context) { SyncEngine(context) }
    var editingSemester by rememberSaveable { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    var editingClub by rememberSaveable { mutableStateOf(false) }
    var recoveryId by rememberSaveable { mutableStateOf<String?>(null) }
    var settingsSection by rememberSaveable { mutableStateOf("main") }
    DiscardOnEpoch(discardEpoch) { editingClub = false; editingSemester = false; editingConnection = false; recoveryId = null; if (settingsSection != "main") settingsSection = "main" }
    TrackEditor(editingClub || editingSemester || editingConnection || recoveryId != null || settingsSection != "main", onEditingChange)
    var settingsError by remember { mutableStateOf("") }
    var themeSaving by remember { mutableStateOf(false) }
    var syncSaving by remember { mutableStateOf(false) }
    if (settingsSection == "backup") { BackupScreen(database, onBack = { settingsSection = "main" }, onMessage = onMessage); return }
    if (settingsSection == "lock") {
        Page { TextButton(onClick = { settingsSection = "main" }) { Text("← 설정") }; AppLockSettings() }
        return
    }
    if (settingsSection == "operations") { OperationsScreen(database, members, events, charges, ledger, onBack = { settingsSection = "main" }, onMessage = onMessage); return }
    if (settingsSection == "roster") { RosterScreen(database, serverUrl, adminToken, onBack = { settingsSection = "main" }, onMessage = onMessage); return }
    Page {
        Section("동아리 정보")
        CardLine(config?.clubName ?: "COM’s", "담당자 ${config?.actor ?: "운영자"} · 계좌 안내 ${if (config?.accountGuide.isNullOrBlank()) "미입력" else "저장됨"}", onClick = { editingClub = true })
        Hint("운영 데이터는 이 기기의 비공개 저장소에 보관돼요")
        Section("학기")
        CardLine("현재 학기", semester, onClick = { editingSemester = true })
        Section("서식·템플릿")
        OutlinedButton(onClick = onOpenTemplates, modifier = Modifier.fillMaxWidth()) { Text("연락 템플릿 열기") }
        Section("화면 설정")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("밝게", "어둡게", "시스템").forEach { choice -> FilterChip(theme == choice, { if (!themeSaving) { themeSaving = true; scope.launch { try { commitSave({ onTheme(choice) }, { settingsError = "" }, { settingsError = it }) } finally { themeSaving = false } } } }, label = { Text(choice) }, enabled = !themeSaving) } }
        if (settingsError.isNotBlank()) Text(settingsError, color = MaterialTheme.colorScheme.error)
        Section("문자·카카오 연결")
        Hint("문자는 기본 문자 앱에서 직접 전송 · 통신사 요금제 적용")
        Hint(if (BuildConfig.KAKAO_APP_KEY.isNotBlank() && BuildConfig.KAKAO_SHARE_URL.isNotBlank()) "카카오 SDK 설정 있음 · 실제 공유 성공은 기기에서 확인" else "카카오 개발자 키·도메인 미설정 · Android 일반 공유/복사 사용")
        Section("온라인 응답 연결")
        CardLine("서버 설정", if (serverUrl.isBlank() || adminToken.isBlank()) "연결 미설정" else "$serverUrl · 운영자 토큰 저장됨", onClick = { editingConnection = true })
        OutlinedButton(onClick = { settingsSection = "roster" }, enabled = serverUrl.isNotBlank() && adminToken.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("COMS 회원 명부 확인") }
        if (onlineStateExists || !config?.serverOrigin.isNullOrBlank()) Hint("온라인 기록이 있어 연결된 서버 주소만 사용할 수 있어요. 운영자 토큰은 교체할 수 있어요")
        Hint("온라인 설정만으로 배포가 완료되지는 않아요. 응답은 행사별 게시와 링크 발급 후 사용합니다")
        Hint("마지막 동기화 ${config?.lastSyncAt?.let { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(it)) } ?: "없음"} · ${config?.lastError?.ifBlank { "오류 없음" } ?: "오류 없음"}")
        pendingMutations.filter { it.kind == "event-recover" && it.status == "conflict" }.forEach { recovery ->
            val payload = runCatching { JSONObject(recovery.payload) }.getOrNull()
            val snapshot = payload?.optJSONObject("snapshot")
            val serverEvent = snapshot?.optJSONObject("event")
            val title = serverEvent.displayText("title", "서버 행사 ${recovery.eventId.take(8)}")
            val date = serverEvent.displayText("startsAt", "일시 미정")
            CardLine("서버 행사 복구 검토 · $title", "$date · 서버 버전 ${serverEvent?.optInt("version", recovery.baseVersion) ?: recovery.baseVersion} · 응답 ${snapshot?.optJSONArray("submissions")?.length() ?: 0}건 · 마지막 저장 스냅샷")
            OutlinedButton(onClick = { recoveryId = recovery.id }, modifier = Modifier.fillMaxWidth()) { Text("서버 행사 복구 검토") }
        }
        OutlinedButton(onClick = { if (!syncSaving) { syncSaving = true; scope.launch { try { commitSave(onManualSync, { settingsError = "" }, { settingsError = it }) } finally { syncSaving = false } } } }, enabled = serverUrl.isNotBlank() && adminToken.isNotBlank() && !syncSaving, modifier = Modifier.fillMaxWidth()) { Text(if (syncSaving) "동기화 중…" else "지금 동기화") }
        Row(verticalAlignment = Alignment.CenterVertically) { Switch(syncEnabled, { enabled -> if (!syncSaving) { syncSaving = true; scope.launch { try { commitSave({ onSyncEnabled(enabled) }, { settingsError = "" }, { settingsError = it }) } finally { syncSaving = false } } } }, enabled = serverUrl.isNotBlank() && adminToken.isNotBlank() && !syncSaving); Text("연결 시 15분 간격 자동 동기화") }
        Section("데이터 가져오기·백업·복구")
        Hint("회원 탭에서 CSV를 가져올 수 있어요. 비공개 파일 백업·복원은 내용을 검토한 후 진행합니다")
        OutlinedButton(onClick = { settingsSection = "backup" }, modifier = Modifier.fillMaxWidth()) { Text("백업·복원 열기") }
        Section("앱 잠금")
        OutlinedButton(onClick = { settingsSection = "lock" }, modifier = Modifier.fillMaxWidth()) { Text("앱 잠금 설정") }
        Section("알림")
        Hint("연락 탭에서 운영자 연락 알림을 예약할 수 있어요")
        Section("비용·선택 기능")
        Hint("AI 호출 없음 · 유료 LLM 꺼짐 · 유료 문자 API 꺼짐 · 알림톡 꺼짐")
        Hint("단말 문자: SKT 요금제 적용 · 카카오 공유: 제공 쿼터 내 이용 · 서버: 실제 배포 환경 비용")
        Section("운영 도구")
        OutlinedButton(onClick = { settingsSection = "operations" }, modifier = Modifier.fillMaxWidth()) { Text("운영 도구 열기") }
        Section("앱 정보")
        Hint("COM’s Companion 0.1.0 · 앱 중심 로컬 원장과 선택형 온라인 응답")
    }
    val recovery = pendingMutations.firstOrNull { it.id == recoveryId && it.kind == "event-recover" && it.status == "conflict" }
    if (recovery != null) {
        val payload = runCatching { JSONObject(recovery.payload) }.getOrNull()
        val snapshot = payload?.optJSONObject("snapshot")
        val serverEvent = snapshot?.optJSONObject("event")
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) recoveryId = null }, title = { Text("서버 행사만 복구") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CardLine(serverEvent.displayText("title", "서버 행사"), "일시 ${serverEvent.displayText("startsAt", "미정")} · 장소 ${serverEvent.displayText("location", "미정")} · 버전 ${serverEvent?.optInt("version", recovery.baseVersion) ?: recovery.baseVersion}")
                Hint("연결 서버 ${payload?.optString("serverOrigin") ?: "확인 필요"} · 응답 ${snapshot?.optJSONArray("submissions")?.length() ?: 0}건 · 입금 요청 ${snapshot?.optJSONArray("paymentRequests")?.length() ?: 0}건")
                Text("이 선택은 서버의 실제 행사 정보와 응답 스냅샷만 이 기기에 복구합니다. 회원·회비 항목·현장 출석·납부 원장은 다시 만들거나 확인 완료로 표시하지 않습니다")
                Hint("복구할 때 서버에서 최신 자료를 다시 가져와 행사 ID·버전·연결 주소를 확인해요. 달라졌다면 이 화면에서 다시 검토합니다")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val mutationId = recovery.id; scope.launch { var synced = false; try { commitSave({
            engine.recoverServerEvent(mutationId)
            try { engine.syncAll(); synced = true } catch (e: CancellationException) { throw e } catch (_: Exception) { }
        }, { recoveryId = null; onMessage(if (synced) "서버 행사와 응답 스냅샷을 복구했어요. 회원·회비 원장은 별도로 확인해 주세요" else "서버 행사만 복구했어요. 후속 동기화는 설정에서 다시 시도해 주세요") }, { error = it }) } finally { saving = false } } } }, enabled = !saving && serverEvent != null) { Text("서버 행사만 복구") } }, dismissButton = { TextButton(onClick = { recoveryId = null }, enabled = !saving) { Text("취소") } })
    }
    if (editingClub) {
        var clubName by rememberSaveable { mutableStateOf(config?.clubName ?: "COM’s") }
        var accountGuide by rememberSaveable { mutableStateOf(config?.accountGuide ?: "") }
        var actor by rememberSaveable { mutableStateOf(config?.actor ?: "운영자") }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) editingClub = false }, title = { Text("동아리 정보") }, text = {
            Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(clubName, { clubName = it }, label = { Text("동아리명 *") })
                OutlinedTextField(actor, { actor = it }, label = { Text("원장 담당자 *") })
                OutlinedTextField(accountGuide, { accountGuide = it }, label = { Text("개인 안내용 계좌 문구") }, minLines = 2)
                Hint("계좌 문구는 개인별 미리보기에서만 치환됩니다. 공통 공유에는 넣지 않아요")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; scope.launch { try { commitSave({ onSaveClub(clubName, accountGuide, actor) }, { editingClub = false }, { error = it }) } finally { saving = false } } } }, enabled = !saving && clubName.isNotBlank() && actor.isNotBlank()) { Text("저장") } }, dismissButton = { TextButton(onClick = { editingClub = false }, enabled = !saving) { Text("취소") } })
    }
    if (editingSemester) {
        var value by rememberSaveable { mutableStateOf(semester) }
        var saving by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) editingSemester = false }, title = { Text("학기") }, text = { Column { OutlinedTextField(value, { value = it }, label = { Text("현재 학기") }); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; scope.launch { try { commitSave({ onSemester(value.trim()) }, { editingSemester = false }, { error = it }) } finally { saving = false } } } }, enabled = value.isNotBlank() && !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = { editingSemester = false }, enabled = !saving) { Text("취소") } })
    }
    if (editingConnection) {
        var url by remember { mutableStateOf(serverUrl) }
        var token by remember { mutableStateOf(adminToken) }
        var error by remember { mutableStateOf("") }
        var saving by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!saving) editingConnection = false }, title = { Text("온라인 응답 연결") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(url, { url = it }, label = { Text("서버 기본 주소") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(token, { token = it }, label = { Text("운영자 토큰") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Hint("HTTPS만 허용해요. 디버그 에뮬레이터는 http://10.0.2.2 예외가 있어요")
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            try {
                val checked = if (url.isBlank() && token.isBlank()) "" else checkedBaseUrl(url, BuildConfig.DEBUG)
                require(checked.isBlank() || token.isNotBlank()) { "운영자 토큰을 입력해 주세요" }
                require((!onlineStateExists && config?.serverOrigin.isNullOrBlank()) || checked == (config?.serverOrigin?.takeIf(String::isNotBlank) ?: serverUrl)) { "기존 온라인 기록이 연결된 서버 주소만 사용할 수 있어요" }
                if (!saving) { saving = true; scope.launch { try { commitSave({ onConnection(checked, token.trim()) }, { editingConnection = false }, { error = it }) } finally { saving = false } } }
            } catch (e: Exception) { error = e.message ?: "연결 정보를 확인해 주세요" }
        }, enabled = !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = { editingConnection = false }, enabled = !saving) { Text("취소") } })
    }
}
