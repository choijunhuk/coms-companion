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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

private val Context.settings by preferencesDataStore("operator-settings")
private val themeKey = stringPreferencesKey("theme")
private val semesterKey = stringPreferencesKey("semester")
private val serverUrlKey = stringPreferencesKey("server-url")
private val adminTokenKey = stringPreferencesKey("admin-token")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BuildConfig.KAKAO_APP_KEY.isNotBlank()) KakaoSdk.init(this, BuildConfig.KAKAO_APP_KEY)
        val dao = AppDatabase.get(this).dao()
        setContent { CompanionApp(dao) }
    }
}

private val light = lightColorScheme(
    background = Color(0xFFF6F7F9), surface = Color.White, onSurface = Color(0xFF17212B),
    onBackground = Color(0xFF17212B), primary = Color(0xFF0F766E), onPrimary = Color.White,
    surfaceVariant = Color(0xFFE8F5F2), onSurfaceVariant = Color(0xFF536171), outline = Color(0xFFDCE2E8),
)
private val dark = darkColorScheme(
    background = Color(0xFF10161C), surface = Color(0xFF18212A), onSurface = Color(0xFFECF2F7),
    onBackground = Color(0xFFECF2F7), primary = Color(0xFF67D5C5), onPrimary = Color(0xFF10201F),
    surfaceVariant = Color(0xFF193B38), onSurfaceVariant = Color(0xFFADBCCA), outline = Color(0xFF344451),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CompanionApp(dao: AppDao) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences by remember(context) { context.settings.data }.collectAsState(emptyPreferences())
    val theme = preferences[themeKey] ?: "밝게"
    val semester = preferences[semesterKey] ?: "학기 미설정"
    val serverUrl = preferences[serverUrlKey] ?: ""
    val adminToken = preferences[adminTokenKey] ?: ""
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
    val onlineStateExists = events.any { it.serverVersion > 0 || it.pendingOperationId.isNotBlank() } || invites.isNotEmpty()
    var tab by remember { mutableIntStateOf(0) }
    var settings by remember { mutableStateOf(false) }
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
                }, navigationIcon = { if (settings) IconButton(onClick = { settings = false }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로") } }, actions = {
                    if (!settings) IconButton(onClick = { settings = true }) { Icon(Icons.Default.Settings, "설정") }
                })
            },
            bottomBar = {
                if (!settings) NavigationBar {
                    val icons = listOf(Icons.Default.Home, Icons.Default.People, Icons.Default.Event, Icons.Default.Payments, Icons.Default.Sms)
                    listOf("홈", "회원", "행사", "회비", "연락").forEachIndexed { index, label ->
                        NavigationBarItem(selected = tab == index, onClick = { tab = index }, icon = { Icon(icons[index], label) }, label = { Text(label) }, alwaysShowLabel = true)
                    }
                }
            },
        ) { inset ->
            Box(Modifier.padding(inset).fillMaxSize()) {
                if (settings) SettingsScreen(theme, semester, serverUrl, adminToken, onlineStateExists, onTheme = { context.settings.edit { preferences -> preferences[themeKey] = it } }, onSemester = { context.settings.edit { preferences -> preferences[semesterKey] = it } }, onConnection = { url, token ->
                    val storedUrl = context.settings.data.first()[serverUrlKey] ?: ""
                    require(dao.onlineStateCount() == 0 || url == storedUrl) { "게시·초대 기록이 있어 서버 주소를 변경할 수 없어요. 운영자 토큰은 교체할 수 있어요" }
                    context.settings.edit { preferences -> preferences[serverUrlKey] = url; preferences[adminTokenKey] = token }
                    message = "온라인 연결 설정을 이 기기에 저장했어요"
                })
                else when (tab) {
                    0 -> HomeScreen(members, events, charges, ledger, duties, onTab = { tab = it }, onAddDuty = { dao.put(it); message = "할 일을 저장했어요" }, onDutyDone = { scope.launch { try { dao.updateDuty(it.id, !it.done) } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "할 일 변경 실패 · ${e.message}" } } })
                    1 -> MembersScreen(members, onSave = { member ->
                        val currentMembers = dao.memberSnapshot()
                        val duplicate = currentMembers.any { it.id != member.id && normalizePhone(it.phone).isNotEmpty() && normalizePhone(it.phone) == normalizePhone(member.phone) }
                        if (currentMembers.any { it.id == member.id }) dao.updateMember(member.id, member.name, member.phone, member.cohort, member.groupName, member.status, member.optedOut) else dao.put(member)
                        message = if (duplicate) "저장했어요. 같은 번호의 회원이 있어요. 자동 병합하지 않았어요" else "회원 정보를 저장했어요"
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
                    })
                    2 -> EventsScreen(events, members, rsvps, duties, invites, onlineReady = serverUrl.isNotBlank() && adminToken.isNotBlank(), onSave = { event ->
                        if (events.any { it.id == event.id }) dao.updateEvent(event.id, event.title, event.whenText, event.place, event.details, event.deadline, event.capacity, event.feeWon, event.cancelled) else dao.put(event)
                        message = "행사를 저장했어요"
                    }, onRsvp = { rsvp ->
                        val eventState = dao.eventById(rsvp.eventId)
                        require((eventState?.serverVersion ?: 0) == 0 && eventState?.pendingOperationId?.isBlank() == true && eventState?.cancelled == false) { "취소·게시 중이거나 게시된 행사 응답은 수정할 수 없어요" }
                        val prior = rsvps.firstOrNull { it.eventId == rsvp.eventId && it.memberId == rsvp.memberId }
                        if (prior == null) dao.put(rsvp) else dao.updateRsvp(prior.id, rsvp.response, rsvp.attendance)
                        message = "운영자 수동 응답을 저장했어요"
                    }, onPublish = { event -> scope.launch {
                        try {
                            val current = dao.eventById(event.id) ?: error("행사를 찾지 못했어요")
                            require(dao.rsvpCount(event.id) == 0) { "수동 참석 응답이 있어요. 최초 게시 이관이 구현될 때까지 이 행사는 게시할 수 없어요" }
                            val op = current.pendingOperationId.ifBlank { newId() }
                            val payload = if (current.pendingPayload.isBlank()) {
                                val prepared = publishPayload(current, op).toString()
                                dao.markPublishPending(current.id, op, prepared)
                                prepared
                            } else current.pendingPayload
                            val version = RemoteApi(checkedBaseUrl(serverUrl, BuildConfig.DEBUG), adminToken).publish(payload)
                            val latest = dao.eventById(event.id) ?: current
                            val dirty = publishPayload(latest, op).toString() != payload
                            dao.markPublished(event.id, version, dirty)
                            message = if (dirty) "이전 초안을 게시했어요. 이후 로컬 수정사항은 다시 게시해 주세요" else "서버 게시를 확인했어요"
                        } catch (e: ApiFailure) {
                            if (e.definitiveNoSave) {
                                dao.clearPublishPending(event.id)
                                message = "서버가 게시 요청을 거부했어요 · ${e.message}. 입력을 고친 뒤 다시 게시해 주세요"
                            } else message = "게시 결과 미확인 · ${e.message}. 같은 작업으로 재시도해 주세요"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "게시 결과 미확인 · ${e.message ?: "연결을 확인하고 같은 작업으로 재시도해 주세요"}" }
                    } }, onLink = { event, member -> scope.launch {
                        try {
                            val current = dao.eventById(event.id) ?: error("행사를 찾지 못했어요")
                            require(current.serverVersion > 0 && !current.serverDirty && current.pendingOperationId.isBlank()) { "현재 행사 내용을 먼저 서버에 게시해 주세요" }
                            require(!current.cancelled) { "취소한 행사에는 새 초대를 발급할 수 없어요" }
                            val memberId = member?.id ?: ""
                            val existingInvite = dao.inviteFor(event.id, memberId)
                            val validExisting = existingInvite?.takeIf { java.time.Instant.parse(it.expiresAt).isAfter(java.time.Instant.now()) }
                            val invite = if (validExisting != null) validExisting else {
                                val created = Invite(eventId = event.id, memberId = memberId, expiresAt = java.time.Instant.now().plusSeconds(30L * 86400).toString())
                                dao.put(created)
                                created
                            }
                            if (invite.url.isBlank()) {
                                val prepared = RemoteApi(checkedBaseUrl(serverUrl, BuildConfig.DEBUG), adminToken).createLink(event.id, invite)
                                dao.setInviteUrl(invite.id, prepared)
                            }
                            message = "링크가 준비됐어요. 개인 링크는 해당 회원에게만 전달해 주세요"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "링크 실패 · ${e.message ?: "연결을 확인하고 다시 시도해 주세요"}" }
                    } }, onRefresh = { event -> scope.launch {
                        try {
                            val json = RemoteApi(checkedBaseUrl(serverUrl, BuildConfig.DEBUG), adminToken).responses(event.id)
                            dao.setResponses(event.id, json, System.currentTimeMillis())
                            message = "서버 응답을 가져왔어요. 납부는 별도 확인이 필요해요"
                        } catch (e: CancellationException) { throw e } catch (e: Exception) { message = "응답 가져오기 실패 · ${e.message ?: "연결을 확인하고 다시 시도해 주세요"}" }
                    } })
                    3 -> FeesScreen(charges, members, ledger, onCharge = { dao.put(it); message = "회비 항목을 저장했어요" }, onEntry = { dao.put(it); message = "원장 기록을 저장했어요" })
                    4 -> ContactScreen(members, events, charges, ledger, templates, contacts, reminders, drafts, dao, onTemplate = { template -> if (templates.any { it.id == template.id }) dao.updateTemplate(template.id, template.title, template.body) else dao.put(template); message = "템플릿을 저장했어요" }, onMessage = { message = it })
                }
            }
        }
    }
}

@Composable private fun Page(content: @Composable ColumnScope.() -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Column(verticalArrangement = Arrangement.spacedBy(16.dp), content = content) }
    }
}

@Composable private fun Section(title: String) { Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold) }
@Composable private fun Hint(text: String) { Text(text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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

@Composable private fun HomeScreen(members: List<Member>, events: List<Event>, charges: List<Charge>, ledger: List<LedgerEntry>, duties: List<Duty>, onTab: (Int) -> Unit, onAddDuty: suspend (Duty) -> Unit, onDutyDone: (Duty) -> Unit) {
    var addDuty by remember { mutableStateOf(false) }
    Page {
        Section("오늘 처리할 일")
        val pending = duties.filterNot(Duty::done)
        if (pending.isEmpty()) Hint("오늘 확인할 일이 없어요") else pending.take(3).forEach { duty -> CardLine(duty.title, "할 일 · 완료하려면 탭", onClick = { onDutyDone(duty) }) }
        if (pending.size > 3) TextButton(onClick = { addDuty = true }) { Text("전체 ${pending.size}건 보기") }
        if (members.isEmpty() && events.isEmpty()) {
            Hint("이 기기에서 첫 업무를 시작해 보세요")
            Action("첫 회원 추가", { onTab(1) })
            OutlinedButton(onClick = { onTab(2) }, modifier = Modifier.fillMaxWidth()) { Text("행사 만들기") }
        } else {
            Section("업무 현황")
            Hint("활동 회원 ${members.count { it.status == "활동" }}명 · 회비 항목 ${charges.size}개")
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
    var title by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("할 일 추가") }, text = { Column { OutlinedTextField(title, { title = it }, label = { Text("할 일") }); if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; scope.launch { try { commitSave({ onSave(Duty(title = title.trim())) }, onDismiss, { error = it }) } finally { saving = false } } } }, enabled = title.isNotBlank() && !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } })
}

@Composable private fun MembersScreen(members: List<Member>, onSave: suspend (Member) -> Unit, onImport: suspend (List<ImportRow>) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("전체") }
    var groupFilter by remember { mutableStateOf<String?>(null) }
    var cohortFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Member?>(null) }
    var adding by remember { mutableStateOf(false) }
    var imported by remember { mutableStateOf<List<ImportRow>?>(null) }
    var importError by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
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
        }.onSuccess { imported = it; importError = "" }.onFailure { importError = it.message ?: "CSV를 읽지 못했어요" }
    }
    Page {
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
        if (importError.isNotBlank()) Text("⚠ $importError", color = MaterialTheme.colorScheme.error)
        if (found.isEmpty()) Hint("조건에 맞는 회원이 없어요") else found.forEach { member -> CardLine(member.name, "${member.cohort.ifBlank { "기수 미입력" }} · ${member.groupName.ifBlank { "소모임 미입력" }} · ${member.status}", onClick = { editing = member }) }
    }
    if (adding || editing != null) MemberDialog(editing, members, onDismiss = { adding = false; editing = null }, onSave = onSave)
    if (imported != null) {
        val rows = imported!!
        AlertDialog(onDismissRequest = { if (!importing) imported = null }, title = { Text("명단 가져오기 미리보기") }, text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("열 이름: 이름, 전화번호, 기수, 소모임. 정상 ${rows.count { it.issue == null }}건 · 오류·중복 ${rows.count { it.issue != null }}건")
                rows.take(30).forEach { row -> Text("${row.row}행 · ${row.name.ifBlank { "이름 없음" }} · ${row.issue ?: "가져오기 가능"}", fontSize = 14.sp) }
                if (rows.size > 30) Hint("나머지 ${rows.size - 30}행은 적용 시 같은 규칙으로 처리해요")
                if (importError.isNotBlank()) Text(importError, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = {
            if (!importing) { importing = true; scope.launch { try { commitSave({ onImport(rows) }, { imported = null; importError = "" }, { importError = it }) } finally { importing = false } } }
        }, enabled = rows.any { it.issue == null } && !importing) { Text("정상 행만 추가") } }, dismissButton = { TextButton(onClick = { imported = null }, enabled = !importing) { Text("취소") } })
    }
}

@Composable private fun MemberDialog(existing: Member?, members: List<Member>, onDismiss: () -> Unit, onSave: suspend (Member) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var phone by remember(existing) { mutableStateOf(existing?.phone ?: "") }
    var cohort by remember(existing) { mutableStateOf(existing?.cohort ?: "") }
    var group by remember(existing) { mutableStateOf(existing?.groupName ?: "") }
    var status by remember(existing) { mutableStateOf(existing?.status ?: "활동") }
    var optOut by remember(existing) { mutableStateOf(existing?.optedOut ?: false) }
    var error by remember { mutableStateOf("") }
    val duplicate = members.firstOrNull { it.id != existing?.id && phone.isNotBlank() && normalizePhone(it.phone) == normalizePhone(phone) }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (existing == null) "회원 추가" else "회원 수정") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("이름 *") }, singleLine = true)
            OutlinedTextField(phone, { phone = it }, label = { Text("전화번호") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), singleLine = true)
            if (duplicate != null) Text("⚠ ${duplicate.name} 회원과 번호가 같습니다. 자동 병합하지 않아요", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
            OutlinedTextField(cohort, { cohort = it }, label = { Text("기수·학년") }, singleLine = true)
            OutlinedTextField(group, { group = it }, label = { Text("소모임") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("활동", "휴면", "탈퇴").forEach { FilterChip(selected = status == it, onClick = { status = it }, label = { Text(it) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(optOut, { optOut = it }); Text("안내 수신 제외") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        error = when { name.isBlank() -> "이름을 입력해 주세요"; phone.isNotBlank() && !validPhone(phone) -> "휴대전화번호를 확인해 주세요"; else -> "" }
        if (error.isEmpty() && !saving) {
            saving = true
            val member = Member(id = existing?.id ?: newId(), name = name.trim(), phone = phone.trim(), cohort = cohort.trim(), groupName = group.trim(), status = status, optedOut = optOut)
            scope.launch { try { commitSave({ onSave(member) }, onDismiss, { error = it }) } finally { saving = false } }
        }
    }, enabled = !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } })
}

@Composable private fun EventsScreen(events: List<Event>, members: List<Member>, rsvps: List<Rsvp>, duties: List<Duty>, invites: List<Invite>, onlineReady: Boolean, onSave: suspend (Event) -> Unit, onRsvp: suspend (Rsvp) -> Unit, onPublish: (Event) -> Unit, onLink: (Event, Member?) -> Unit, onRefresh: (Event) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf<Event?>(null) }
    var adding by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Event?>(null) }
    var rsvpMember by remember { mutableStateOf<Member?>(null) }
    Page {
        Action("행사 만들기", { adding = true })
        if (events.isEmpty()) Hint("행사가 없어요. 일시와 장소는 미정으로 둘 수 있어요")
        events.forEach { event ->
            val eventRsvps = rsvps.filter { it.eventId == event.id }
            CardLine("${if (event.cancelled) "취소됨 · " else ""}${event.title}", "${event.whenText.ifBlank { "일시 미정" }} · ${event.place.ifBlank { "장소 미정" }} · 응답 ${eventRsvps.size}명", onClick = { selected = event })
        }
        if (selected != null) {
            val event = events.firstOrNull { it.id == selected?.id } ?: selected!!
            Section(event.title)
            if (event.cancelled) Text("⚠ 취소된 행사입니다. 기존 응답은 보존하고 새 응답은 받지 않습니다", color = MaterialTheme.colorScheme.error)
            Hint("${event.whenText.ifBlank { "일시 미정" }} · ${event.place.ifBlank { "장소 미정" }}")
            if (event.details.isNotBlank()) Text(event.details)
            if (!onlineReady) Hint("응답 링크를 사용하려면 설정에서 HTTPS 서버와 운영자 토큰을 연결해 주세요")
            else {
                val manualCount = rsvps.count { it.eventId == event.id }
                if (manualCount > 0 && event.serverVersion == 0) Hint("수동 참석 응답 $manualCount 건이 있어요. 최초 게시 이관 기능 전에는 이 행사를 게시할 수 없어요")
                Hint(if (event.serverVersion == 0) "온라인 미게시 · 일시/마감은 ISO 8601 날짜와 시간대(예: 2026-11-01T15:00:00+09:00)로 입력해 주세요" else if (event.serverDirty) "로컬 수정사항 미게시 · 서버 버전 ${event.serverVersion}" else "서버 버전 ${event.serverVersion} 게시 확인")
                if (event.pendingOperationId.isNotBlank()) Hint("이전 게시 요청 결과 미확인 · 같은 작업으로 재시도합니다")
                Action(if (event.pendingOperationId.isNotBlank()) "게시 결과 재확인" else if (event.serverVersion > 0) "현재 행사 다시 게시" else "행사 서버 게시", enabled = manualCount == 0, onClick = { onPublish(event) })
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
                        CardLine("입금 확인 요청 · ${request.optString("depositorName")}", "${request.optLong("amount")}원 주장 · ${request.optString("status")} · 운영자 실제 확인 전에는 납부 완료 아님")
                    }
                    if (!event.cancelled && !event.serverDirty && event.pendingOperationId.isBlank()) {
                        Section("응답 링크")
                        val now = java.time.Instant.now()
                        val common = invites.firstOrNull { it.eventId == event.id && it.memberId.isBlank() && it.url.isNotBlank() && runCatching { java.time.Instant.parse(it.expiresAt).isAfter(now) }.getOrDefault(false) }
                        if (common == null) Action("공통 링크 발급 · 30일", onClick = { onLink(event, null) })
                        else { CardLine("공통 링크", "만료 ${common.expiresAt}"); OutlinedButton(onClick = { copyText(context, common.url) }, modifier = Modifier.fillMaxWidth()) { Text("공통 링크 복사") } }
                        members.filter { it.status == "활동" && !it.optedOut }.forEach { member ->
                            val personal = invites.firstOrNull { it.eventId == event.id && it.memberId == member.id && it.url.isNotBlank() && runCatching { java.time.Instant.parse(it.expiresAt).isAfter(now) }.getOrDefault(false) }
                            if (personal == null) OutlinedButton(onClick = { onLink(event, member) }, modifier = Modifier.fillMaxWidth()) { Text("${member.name} 개인 링크 발급") }
                            else OutlinedButton(onClick = { copyText(context, personal.url) }, modifier = Modifier.fillMaxWidth()) { Text("${member.name} 개인 링크 복사 · 해당 회원에게만 전달") }
                        }
                    }
                }
            }
            OutlinedButton(onClick = { editing = event }, modifier = Modifier.fillMaxWidth()) { Text("행사 수정") }
            Section(if (event.serverVersion == 0) "참여자 · 운영자 수동 응답" else "게시 전 수동 응답 · 게시 후에는 서버 응답을 확인해 주세요")
            members.filter { it.status != "탈퇴" }.forEach { member ->
                val record = rsvps.firstOrNull { it.eventId == event.id && it.memberId == member.id }
                CardLine(member.name, "${record?.response ?: "미응답"} · ${if (record?.attendance == true) "출석" else "출석 미확인"}", onClick = if (!event.cancelled && event.serverVersion == 0 && event.pendingOperationId.isBlank()) ({ rsvpMember = member }) else null)
            }
            Section("준비 할 일")
            duties.filter { it.eventId == event.id }.forEach { CardLine(it.title, if (it.done) "완료" else "미완료") }
        }
    }
    if (adding || editing != null) EventDialog(editing, onDismiss = { adding = false; editing = null }, onSave = { onSave(it); selected = it })
    if (rsvpMember != null && selected != null) {
        val member = rsvpMember!!
        val event = selected!!
        val prior = rsvps.firstOrNull { it.eventId == event.id && it.memberId == member.id }
        var response by remember(member.id, event.id) { mutableStateOf(prior?.response ?: "미정") }
        var attendance by remember(member.id, event.id) { mutableStateOf(prior?.attendance ?: false) }
        var saving by remember(member.id, event.id) { mutableStateOf(false) }
        var saveError by remember(member.id, event.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!saving) rsvpMember = null }, title = { Text("${member.name} · 수동 응답") }, text = {
            Column {
                listOf("참석", "불참", "미정").forEach { Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = response == it, onClick = { response = it }); Text(it) } }
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(attendance, { attendance = it }); Text("현장 출석 확인") }
                if (saveError.isNotBlank()) Text(saveError, color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val value = Rsvp(eventId = event.id, memberId = member.id, response = response, attendance = attendance); scope.launch { try { commitSave({ onRsvp(value) }, { rsvpMember = null }, { saveError = it }) } finally { saving = false } } } }, enabled = !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = { rsvpMember = null }, enabled = !saving) { Text("취소") } })
    }
}

@Composable private fun EventDialog(existing: Event?, onDismiss: () -> Unit, onSave: suspend (Event) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var whenText by remember(existing) { mutableStateOf(existing?.whenText ?: "") }
    var place by remember(existing) { mutableStateOf(existing?.place ?: "") }
    var details by remember(existing) { mutableStateOf(existing?.details ?: "") }
    var deadline by remember(existing) { mutableStateOf(existing?.deadline ?: "") }
    var capacity by remember(existing) { mutableStateOf(existing?.capacity?.toString() ?: "") }
    var fee by remember(existing) { mutableStateOf(existing?.feeWon?.takeIf { it > 0 }?.toString() ?: "") }
    var cancelled by remember(existing) { mutableStateOf(existing?.cancelled ?: false) }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (existing == null) "행사 만들기" else "행사 수정") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("행사명 *") })
            OutlinedTextField(whenText, { whenText = it }, label = { Text("일시 · 미정 가능") })
            OutlinedTextField(place, { place = it }, label = { Text("장소 · 미정 가능") })
            OutlinedTextField(deadline, { deadline = it }, label = { Text("응답 마감 · 미정 가능") })
            OutlinedTextField(capacity, { capacity = it }, label = { Text("정원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(fee, { fee = it }, label = { Text("참가비 원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(details, { details = it }, label = { Text("안내사항") }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(cancelled, { cancelled = it }); Text("행사 취소 · 기존 응답 보존") }
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        error = when { title.isBlank() -> "행사명을 입력해 주세요"; capacity.isNotBlank() && (capacity.toIntOrNull() ?: 0) <= 0 -> "정원은 양수로 입력해 주세요"; fee.isNotBlank() && (fee.toLongOrNull() ?: -1) < 0 -> "참가비를 확인해 주세요"; else -> "" }
        if (error.isEmpty() && !saving) {
            saving = true
            val event = (existing ?: Event(title = title.trim())).copy(title = title.trim(), whenText = whenText.trim(), place = place.trim(), details = details.trim(), deadline = deadline.trim(), capacity = capacity.toIntOrNull(), feeWon = fee.toLongOrNull() ?: 0, cancelled = cancelled)
            scope.launch { try { commitSave({ onSave(event) }, onDismiss, { error = it }) } finally { saving = false } }
        }
    }, enabled = !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } })
}

@Composable private fun FeesScreen(charges: List<Charge>, members: List<Member>, ledger: List<LedgerEntry>, onCharge: suspend (Charge) -> Unit, onEntry: suspend (LedgerEntry) -> Unit) {
    val scope = rememberCoroutineScope()
    var addCharge by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Charge?>(null) }
    var addEntry by remember { mutableStateOf(false) }
    var chargeSaving by remember { mutableStateOf(false) }
    var chargeError by remember { mutableStateOf("") }
    Page {
        Action("회비 항목 추가", { chargeError = ""; addCharge = true })
        if (charges.isEmpty()) Hint("회비 항목이 없어요")
        charges.forEach { charge ->
            val entries = ledger.filter { it.chargeId == charge.id }
            val billed = entries.filter { it.kind == "청구" }.sumOf { it.deltaWon }
            val paid = -entries.filter { it.kind == "납부 확인" }.sumOf { it.deltaWon }
            CardLine(charge.title, "청구 %,d원 · 확인 납부 %,d원 · 남은 금액 %,d원".format(billed, paid, entries.sumOf { it.deltaWon }), onClick = { selected = charge })
        }
        if (selected != null) {
            val charge = selected!!
            Section(charge.title)
            Hint("입금 확인 요청은 납부 완료가 아니에요. 실제 입금을 확인한 뒤 별도 납부 기록을 남겨 주세요")
            Action("원장 기록 추가", { addEntry = true })
            members.forEach { member ->
                val balance = balance(charge.id, member.id, ledger)
                CardLine(member.name, "남은 금액 %,d원".format(balance))
            }
            Section("변경 이력")
            ledger.filter { it.chargeId == charge.id }.forEach { entry ->
                val name = members.firstOrNull { it.id == entry.memberId }?.name ?: "회원 삭제됨"
                CardLine("$name · ${entry.kind}", "%+,d원 · ${entry.reason} · ${entry.actor}".format(entry.deltaWon))
            }
        }
    }
    if (addCharge) {
        var title by remember { mutableStateOf("") }
        var due by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { if (!chargeSaving) addCharge = false }, title = { Text("회비 항목 추가") }, text = { Column { OutlinedTextField(title, { title = it }, label = { Text("항목명 *") }); OutlinedTextField(due, { due = it }, label = { Text("기한 · 선택") }); if (chargeError.isNotBlank()) Text(chargeError, color = MaterialTheme.colorScheme.error) } }, confirmButton = { TextButton(onClick = { if (!chargeSaving) { chargeSaving = true; val charge = Charge(title = title.trim(), dueDate = due.trim()); scope.launch { try { commitSave({ onCharge(charge) }, { addCharge = false }, { chargeError = it }) } finally { chargeSaving = false } } } }, enabled = title.isNotBlank() && !chargeSaving) { Text("저장") } }, dismissButton = { TextButton(onClick = { addCharge = false }, enabled = !chargeSaving) { Text("취소") } })
    }
    if (addEntry && selected != null) LedgerDialog(selected!!, members, ledger, onDismiss = { addEntry = false }, onSave = onEntry)
}

@Composable private fun LedgerDialog(charge: Charge, members: List<Member>, ledger: List<LedgerEntry>, onDismiss: () -> Unit, onSave: suspend (LedgerEntry) -> Unit) {
    val scope = rememberCoroutineScope()
    var saving by remember { mutableStateOf(false) }
    var memberId by remember { mutableStateOf(members.firstOrNull()?.id ?: "") }
    var kind by remember { mutableStateOf("청구") }
    var amount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("${charge.title} · 원장 기록") }, text = {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint("회원 선택")
            Column(Modifier.heightIn(max = 120.dp).verticalScroll(rememberScrollState())) { members.forEach { member -> Row(verticalAlignment = Alignment.CenterVertically) { RadioButton(memberId == member.id, { memberId = member.id }); Text(member.name) } } }
            Hint("유형")
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("청구", "납부 확인", "면제").forEach { FilterChip(kind == it, { kind = it }, label = { Text(it, fontSize = 12.sp) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf("환불", "정정 증가", "정정 감소").forEach { FilterChip(kind == it, { kind = it }, label = { Text(it, fontSize = 12.sp) }) } }
            OutlinedTextField(amount, { amount = it }, label = { Text("금액 원 *") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(reason, { reason = it }, label = { Text("이유 *") })
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = {
        if (saving) return@TextButton
        try {
            require(memberId.isNotBlank()) { "먼저 회원을 등록해 주세요" }
            val delta = requireLedgerEntry(kind, amount.toLongOrNull() ?: 0, reason.trim())
            val current = balance(charge.id, memberId, ledger)
            require(kind !in listOf("납부 확인", "면제") || -delta <= current) { "현재 남은 금액보다 클 수 없어요. 정정 기록으로 차이를 남겨 주세요" }
            val previous = ledger.filter { it.chargeId == charge.id && it.memberId == memberId }
            require(kind != "환불" || delta <= -previous.filter { it.kind == "납부 확인" }.sumOf { it.deltaWon } - previous.filter { it.kind == "환불" }.sumOf { it.deltaWon }) { "확인 납부액보다 환불액이 클 수 없어요" }
            val entry = LedgerEntry(chargeId = charge.id, memberId = memberId, kind = kind, deltaWon = delta, reason = reason.trim())
            saving = true
            scope.launch { try { commitSave({ onSave(entry) }, onDismiss, { error = it }) } finally { saving = false } }
        } catch (e: IllegalArgumentException) { error = e.message ?: "입력을 확인해 주세요" }
    }, enabled = !saving) { Text("원장에 기록") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } })
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

@Composable private fun ContactScreen(members: List<Member>, events: List<Event>, charges: List<Charge>, ledger: List<LedgerEntry>, templates: List<MessageTemplate>, contacts: List<ContactRecord>, reminders: List<Reminder>, drafts: List<ContactDraft>, dao: AppDao, onTemplate: suspend (MessageTemplate) -> Unit, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf("준비 중") }
    var selectedTemplate by remember { mutableStateOf<MessageTemplate?>(null) }
    var selectedEvent by remember { mutableStateOf<Event?>(null) }
    var selectedCharge by remember { mutableStateOf<Charge?>(null) }
    var selectedGroup by remember { mutableStateOf<String?>(null) }
    var body by remember { mutableStateOf("") }
    var publicBody by remember { mutableStateOf("") }
    var currentIndex by remember { mutableIntStateOf(0) }
    var editTemplate by remember { mutableStateOf<MessageTemplate?>(null) }
    var newTemplate by remember { mutableStateOf(false) }
    var newReminder by remember { mutableStateOf(false) }
    var draftTitle by remember { mutableStateOf("") }
    var activeDraft by remember { mutableStateOf<ContactDraft?>(null) }
    var allowRepeat by remember { mutableStateOf(false) }
    var smsBusy by remember { mutableStateOf(false) }
    var kakaoFallback by remember { mutableStateOf<String?>(null) }
    var kakaoError by remember { mutableStateOf("") }
    var draftSaving by remember { mutableStateOf(false) }
    var draftError by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (!granted) onMessage("알림 권한이 없어도 앱의 연락 알림 목록에서 확인할 수 있어요") }
    val candidates = eligibleMembers(members, ledger, selectedCharge?.id, selectedGroup)
    val shownIndex = currentIndex.coerceAtMost((candidates.size - 1).coerceAtLeast(0))
    val target = candidates.getOrNull(shownIndex)
    val rendered = target?.let { renderTemplate(body, it, selectedEvent, selectedCharge, ledger) }
    LaunchedEffect(target?.id, body) { allowRepeat = false }
    Page {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("준비 중", "연락 알림", "기록", "템플릿").forEach { FilterChip(section == it, { section = it }, label = { Text(it, fontSize = 12.sp) }) } }
        when (section) {
            "연락 알림" -> {
                Section("연락 알림")
                Hint("정확한 시간이나 실제 발송을 보장하지 않아요. 알림은 운영자에게만 표시돼요")
                Action("알림 추가", { newReminder = true })
                if (reminders.isEmpty()) Hint("예약한 알림이 없어요")
                reminders.forEach { reminder ->
                    val state = if (reminder.state == "대기" && reminder.atMillis < System.currentTimeMillis()) "시간 지남 · 확인 필요" else reminder.state
                    CardLine(reminder.title, "${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(reminder.atMillis))} · $state")
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
                (builtInTemplates + templates).forEach { template -> CardLine(template.title, template.body, onClick = { if (template.id in builtInTemplates.map { it.id }) { selectedTemplate = template; body = template.body; section = "준비 중" } else editTemplate = template }) }
            }
            else -> {
                Section("연락 준비")
                Hint("현재 활동·수신 설정·번호·회비 잔액을 기준으로 대상자를 계산합니다. 문자 앱에서 직접 전송해 주세요")
                if (drafts.isNotEmpty()) {
                    Section("저장한 초안")
                    TextButton(onClick = { activeDraft = null; draftTitle = ""; body = ""; selectedCharge = null; selectedEvent = null; selectedGroup = null; currentIndex = 0 }) { Text("새 초안") }
                    drafts.forEach { draft -> CardLine(draft.title, "대상은 열 때 다시 계산 · ${draft.body.take(40)}", onClick = {
                        activeDraft = draft; draftTitle = draft.title; body = draft.body
                        selectedEvent = events.firstOrNull { it.id == draft.eventId }
                        selectedCharge = charges.firstOrNull { it.id == draft.chargeId }
                        selectedGroup = draft.groupName; currentIndex = draft.currentIndex
                    }) }
                }
                OutlinedTextField(draftTitle, { draftTitle = it }, label = { Text("초안 제목") }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    if (draftSaving) return@OutlinedButton
                    draftSaving = true
                    val existingDraft = activeDraft
                    val draft = ContactDraft(id = existingDraft?.id ?: newId(), title = draftTitle.trim(), body = body, eventId = selectedEvent?.id, chargeId = selectedCharge?.id, groupName = selectedGroup, currentIndex = currentIndex)
                    scope.launch { try { commitSave({ if (existingDraft == null) dao.put(draft) else dao.updateDraft(draft.id, draft.title, draft.body, draft.eventId, draft.chargeId, draft.groupName, draft.currentIndex) }, { activeDraft = draft; draftError = ""; onMessage("초안을 이 기기에 저장했어요") }, { draftError = it }) } finally { draftSaving = false } }
                }, enabled = draftTitle.isNotBlank() && body.isNotBlank() && !draftSaving, modifier = Modifier.fillMaxWidth()) { Text("초안 저장") }
                if (draftError.isNotBlank()) Text(draftError, color = MaterialTheme.colorScheme.error)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selectedCharge == null, { selectedCharge = null; currentIndex = 0 }, label = { Text("전체") })
                    charges.forEach { charge -> FilterChip(selectedCharge?.id == charge.id, { selectedCharge = charge; currentIndex = 0 }, label = { Text(charge.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
                val groups = members.map { it.groupName }.filter(String::isNotBlank).distinct()
                if (groups.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FilterChip(selectedGroup == null, { selectedGroup = null; currentIndex = 0 }, label = { Text("모든 소모임") })
                    groups.forEach { group -> FilterChip(selectedGroup == group, { selectedGroup = group; currentIndex = 0 }, label = { Text(group) }) }
                }
                Hint("대상 ${candidates.size}명 · 제외 ${members.size - candidates.size}명 (탈퇴·수신 제외·번호 오류·중복 번호${if (selectedCharge != null) "·완납" else ""})")
                if (events.isNotEmpty()) {
                    Section("관련 행사")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        FilterChip(selectedEvent == null, { selectedEvent = null }, label = { Text("없음") })
                        events.forEach { event -> FilterChip(selectedEvent?.id == event.id, { selectedEvent = event }, label = { Text(event.title) }) }
                    }
                }
                Section("문구")
                (builtInTemplates + templates).take(8).forEach { template -> if (selectedTemplate?.id == template.id) Hint("선택: ${template.title}") }
                OutlinedButton(onClick = { section = "템플릿" }, modifier = Modifier.fillMaxWidth()) { Text("템플릿 선택") }
                OutlinedTextField(body, { body = it; selectedTemplate = null }, label = { Text("개인 안내 문구") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                if (target == null) Hint("연락 가능한 회원을 등록하거나 대상 조건을 바꿔 주세요") else {
                    Section("개인별 최종 미리보기")
                    Hint("${shownIndex + 1}/${candidates.size} ${target.name} · 문자 대상 ${target.phone}")
                    CardLine("현재 문구", rendered?.text ?: "")
                    if (rendered?.missing?.isNotEmpty() == true) Text("⚠ 미치환 정보: ${rendered.missing.joinToString()}", color = MaterialTheme.colorScheme.error)
                    val sameMessage = contacts.any { it.memberId == target.id && it.bodySnapshot == rendered?.text && it.channel == "문자 앱" }
                    if (sameMessage) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allowRepeat, { allowRepeat = it })
                        Text("같은 문구를 다시 준비하기", fontSize = 14.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { currentIndex = (currentIndex - 1).coerceAtLeast(0) }) { Text("이전") }
                        TextButton(onClick = { currentIndex = (currentIndex + 1).coerceAtMost(candidates.lastIndex) }) { Text("다음 대상") }
                    }
                    Action("문자 앱에서 보내기", enabled = !smsBusy && body.isNotBlank() && rendered?.missing?.isEmpty() == true, onClick = {
                        if (smsBusy) return@Action
                        smsBusy = true
                        val expectedId = target.id
                        val draftBody = body
                        val charge = selectedCharge
                        val group = selectedGroup
                        val event = selectedEvent
                        val repeatAllowed = allowRepeat
                        scope.launch {
                            var handedOff = false
                            try {
                                val currentLedger = dao.ledgerSnapshot()
                                val fresh = eligibleMembers(dao.memberSnapshot(), currentLedger, charge?.id, group).firstOrNull { it.id == expectedId }
                                if (fresh == null) { onMessage("대상 상태가 바뀌었어요. 목록을 다시 확인해 주세요"); return@launch }
                                val final = renderTemplate(draftBody, fresh, event, charge, currentLedger)
                                if (final.missing.isNotEmpty()) { onMessage("누락 정보: ${final.missing.joinToString()}"); return@launch }
                                if (!repeatAllowed && dao.contactSnapshot().any { it.memberId == fresh.id && it.bodySnapshot == final.text && it.channel == "문자 앱" }) { onMessage("같은 문구를 이미 전달했어요. 재준비하려면 체크해 주세요"); return@launch }
                                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", normalizePhone(fresh.phone), null)).putExtra("sms_body", final.text))
                                handedOff = true
                                dao.put(ContactRecord(memberId = fresh.id, memberName = fresh.name, phoneSnapshot = fresh.phone, bodySnapshot = final.text, channel = "문자 앱", state = "앱 전달됨"))
                                allowRepeat = false
                                onMessage("문자 앱을 열었어요. 실제 전송 여부는 확인되지 않았어요")
                            } catch (_: ActivityNotFoundException) { onMessage("문자 앱을 찾지 못했어요. 내용을 복사해 사용해 주세요") }
                            catch (e: CancellationException) { throw e }
                            catch (e: Exception) { onMessage(if (handedOff) "문자 앱은 열렸지만 연락 기록 저장에 실패했어요. 재시도 전 실제 발송 여부를 확인해 주세요" else "문자 준비 오류 · ${e.message ?: "다시 시도해 주세요"}") }
                            finally { smsBusy = false }
                        }
                    })
                    OutlinedButton(onClick = { copyText(context, rendered?.text ?: ""); onMessage("개인 안내 문구를 복사했어요. 발송 여부는 확인되지 않았어요") }, enabled = rendered?.missing?.isEmpty() == true, modifier = Modifier.fillMaxWidth()) { Text("개인 문구 복사") }
                }
                Section("공통 공지")
                Hint("카카오톡·일반 공유는 공통 문구만 사용해요. 이름·개인 잔액·전화번호·개인 링크를 넣지 마세요")
                OutlinedTextField(publicBody, { publicBody = it }, label = { Text("공통 안내 문구") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                val publicSafe = publicShareSafe(publicBody, members)
                if (publicBody.isNotBlank() && !publicSafe) Text("⚠ 이름·금액·전화번호·개인 링크를 제거해 주세요", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = {
                    val snapshot = publicBody
                    if (BuildConfig.KAKAO_APP_KEY.isBlank() || BuildConfig.KAKAO_SHARE_URL.isBlank()) shareCommonWithAndroid(context, snapshot, dao, scope, onMessage)
                    else try {
                        if (!ShareClient.instance.isKakaoTalkSharingAvailable(context)) { kakaoError = "카카오톡 공유를 사용할 수 없어요"; kakaoFallback = snapshot }
                        else ShareClient.instance.shareDefault(context, TextTemplate(text = snapshot, link = Link(webUrl = BuildConfig.KAKAO_SHARE_URL, mobileWebUrl = BuildConfig.KAKAO_SHARE_URL))) { result, error ->
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
                    } catch (e: Exception) { kakaoError = e.message ?: "카카오톡 공유를 시작하지 못했어요"; kakaoFallback = snapshot }
                }, enabled = publicSafe, modifier = Modifier.fillMaxWidth()) { Text(if (BuildConfig.KAKAO_APP_KEY.isNotBlank() && BuildConfig.KAKAO_SHARE_URL.isNotBlank()) "카카오톡으로 공유" else "공통 문구 공유") }
                OutlinedButton(onClick = { copyText(context, publicBody); onMessage("공통 공지를 복사했어요") }, enabled = publicSafe, modifier = Modifier.fillMaxWidth()) { Text("공통 문구 복사") }
            }
        }
    }
    if (editTemplate != null || newTemplate) TemplateDialog(editTemplate, onDismiss = { editTemplate = null; newTemplate = false }, onSave = onTemplate)
    if (newReminder) ReminderDialog(onDismiss = { newReminder = false }, onSave = { reminder ->
        dao.put(reminder)
        scheduleReminder(context, reminder)
        if (Build.VERSION.SDK_INT >= 33) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        onMessage("연락 알림을 저장했어요. 실제 발송은 하지 않아요")
    })
    if (kakaoFallback != null) {
        val snapshot = kakaoFallback!!
        AlertDialog(onDismissRequest = { kakaoFallback = null }, title = { Text("카카오톡 공유 오류") }, text = { Text("$kakaoError\n\n일반 Android 공유로 계속할 수 있어요. 실제 전송은 공유 앱에서 직접 확인해 주세요") }, confirmButton = { TextButton(onClick = { if (publicShareSafe(snapshot, members)) shareCommonWithAndroid(context, snapshot, dao, scope, onMessage) else onMessage("공통 문구의 개인정보를 다시 확인해 주세요"); kakaoFallback = null }) { Text("일반 공유로 계속") } }, dismissButton = { TextButton(onClick = { kakaoFallback = null }) { Text("닫기") } })
    }
}

@Composable private fun ReminderDialog(onDismiss: () -> Unit, onSave: suspend (Reminder) -> Unit) {
    val scope = rememberCoroutineScope()
    val reminderId = remember { newId() }
    var saving by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var whenText by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text("연락 알림") }, text = {
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
            val reminder = Reminder(id = reminderId, title = title.trim(), atMillis = time!!)
            scope.launch { try { commitSave({ onSave(reminder) }, onDismiss, { error = it }) } finally { saving = false } }
        }
    }, enabled = !saving) { Text("알림 저장") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } })
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
    var title by remember(existing) { mutableStateOf(existing?.title ?: "") }
    var body by remember(existing) { mutableStateOf(existing?.body ?: "") }
    AlertDialog(onDismissRequest = { if (!saving) onDismiss() }, title = { Text(if (existing == null) "내 템플릿 추가" else "템플릿 수정") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("제목") })
            OutlinedTextField(body, { body = it }, label = { Text("본문") }, minLines = 4)
            Hint("지원 변수: {이름}, {행사명}, {일시}, {장소}, {납부항목}, {남은금액}, {마감}")
            if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { if (!saving) { saving = true; val template = MessageTemplate(id = existing?.id ?: newId(), title = title.trim(), body = body.trim()); scope.launch { try { commitSave({ onSave(template) }, onDismiss, { error = it }) } finally { saving = false } } } }, enabled = title.isNotBlank() && body.isNotBlank() && !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("취소") } })
}

@Composable private fun SettingsScreen(theme: String, semester: String, serverUrl: String, adminToken: String, onlineStateExists: Boolean, onTheme: suspend (String) -> Unit, onSemester: suspend (String) -> Unit, onConnection: suspend (String, String) -> Unit) {
    val scope = rememberCoroutineScope()
    var editingSemester by remember { mutableStateOf(false) }
    var editingConnection by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf("") }
    var themeSaving by remember { mutableStateOf(false) }
    Page {
        Section("동아리 정보")
        Hint("COM’s · 운영 데이터는 이 기기의 비공개 저장소에 보관돼요")
        Section("학기")
        CardLine("현재 학기", semester, onClick = { editingSemester = true })
        Section("서식·템플릿")
        Hint("연락 탭의 템플릿에서 개인 문구를 추가·수정할 수 있어요")
        Section("화면 설정")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("밝게", "어둡게", "시스템").forEach { choice -> FilterChip(theme == choice, { if (!themeSaving) { themeSaving = true; scope.launch { try { commitSave({ onTheme(choice) }, { settingsError = "" }, { settingsError = it }) } finally { themeSaving = false } } } }, label = { Text(choice) }, enabled = !themeSaving) } }
        if (settingsError.isNotBlank()) Text(settingsError, color = MaterialTheme.colorScheme.error)
        Section("문자·카카오 연결")
        Hint("문자 앱 작성 화면은 직접 전송합니다. 통신사 요금제 적용. 카카오 개발자 키가 연결되기 전에는 Android 일반 공유를 사용합니다")
        Section("온라인 응답 연결")
        CardLine("서버 설정", if (serverUrl.isBlank() || adminToken.isBlank()) "연결 미설정" else "$serverUrl · 운영자 토큰 저장됨", onClick = { editingConnection = true })
        if (onlineStateExists) Hint("게시·초대 기록이 있어 이 버전에서는 서버 주소를 변경할 수 없어요. 운영자 토큰은 교체할 수 있어요")
        Hint("온라인 설정만으로 배포가 완료되지는 않아요. 응답은 행사별 게시와 링크 발급 후 사용합니다")
        Section("데이터 가져오기·백업·복구")
        Hint("회원 탭의 CSV 가져오기를 사용할 수 있어요. 백업·복구는 아직 제공되지 않으며 앱 삭제 시 로컬 데이터가 사라질 수 있어요")
        Section("앱 잠금")
        Hint("아직 제공되지 않아요. 단말 잠금을 사용해 주세요")
        Section("알림")
        Hint("연락 탭에서 운영자 연락 알림을 예약할 수 있어요")
        Section("비용·선택 기능")
        Hint("AI 호출 없음 · 유료 문자 API 꺼짐 · 알림톡 꺼짐 · 문자 비용은 요금제에 따름")
        Section("운영 도구")
        Hint("물품 대여와 추가 도구는 이후 구현 예정이에요")
        Section("앱 정보")
        Hint("COM’s Companion 0.1.0 · 오프라인 첫 구현")
    }
    if (editingSemester) {
        var value by remember { mutableStateOf(semester) }
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
                require(!onlineStateExists || checked == serverUrl) { "기존 게시·초대 기록이 있어 서버 주소를 변경할 수 없어요" }
                if (!saving) { saving = true; scope.launch { try { commitSave({ onConnection(checked, token.trim()) }, { editingConnection = false }, { error = it }) } finally { saving = false } } }
            } catch (e: Exception) { error = e.message ?: "연결 정보를 확인해 주세요" }
        }, enabled = !saving) { Text("저장") } }, dismissButton = { TextButton(onClick = { editingConnection = false }, enabled = !saving) { Text("취소") } })
    }
}
