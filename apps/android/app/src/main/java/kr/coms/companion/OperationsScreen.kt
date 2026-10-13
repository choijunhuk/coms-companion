package kr.coms.companion

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeParseException

@Composable
fun OperationsScreen(database: AppDatabase, members: List<Member>, events: List<Event>, charges: List<Charge>, ledger: List<LedgerEntry>, onBack: () -> Unit, onMessage: (String) -> Unit) {
    val dao = remember(database) { database.operationsDao() }
    val expenses by dao.expenses().collectAsState(initial = emptyList())
    val assets by dao.assets().collectAsState(initial = emptyList())
    val loans by dao.loans().collectAsState(initial = emptyList())
    val applicants by dao.applicants().collectAsState(initial = emptyList())
    val meetings by dao.meetings().collectAsState(initial = emptyList())
    val decisions by dao.decisions().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf("정산") }
    var dialog by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var field1 by remember { mutableStateOf("") }
    var field2 by remember { mutableStateOf("") }
    var field3 by remember { mutableStateOf("") }
    var field4 by remember { mutableStateOf("") }
    var field5 by remember { mutableStateOf("") }

    fun open(kind: String, id: String = "", a: String = "", b: String = "", c: String = "", d: String = "", e: String = "") {
        dialog = kind; selectedId = id; field1 = a; field2 = b; field3 = c; field4 = d; field5 = e; error = ""; discard = false
    }
    fun close() { dialog = ""; selectedId = ""; discard = false; error = "" }
    fun save(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try { commitSave(block, { close(); onMessage("저장했어요") }, { error = it }) }
            finally { saving = false }
        }
    }
    fun runAction(block: suspend () -> Unit) {
        scope.launch {
            try { block(); onMessage("변경했어요") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { onMessage(e.message ?: "변경하지 못했어요") }
        }
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("← 뒤로") } }
        item { Text("운영 도구", style = MaterialTheme.typography.headlineSmall) }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("정산", "물품", "지원자", "회의").forEach { choice ->
                    FilterChip(section == choice, { section = choice }, label = { Text(choice) })
                }
            }
        }
        when (section) {
            "정산" -> {
                item { Text("행사 수입은 확인된 회비 원장만 집계합니다. 비용 지출·업체 환급·정정은 새 기록으로 남습니다. 회원 회비 환불은 회비 원장에서 기록하세요.") }
                if (events.isEmpty()) item { Text("등록된 행사가 없어요") }
                items(events, key = { it.id }) { event ->
                    val settlement = eventSettlement(event.id, charges, ledger, expenses)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(event.title, style = MaterialTheme.typography.titleMedium)
                        Text("확인 수입 ${settlement.incomeWon}원 · 지출 ${settlement.expenseWon}원 · 잔액 ${settlement.balanceWon}원")
                        TextButton(onClick = { open("expense", event.id) }) { Text("지출·환불 기록") }
                        expenses.filter { it.eventId == event.id }.forEach { entry ->
                            Text("${entry.category} ${entry.amountWon}원 · ${entry.memo} · ${entry.actor}")
                        }
                    }
                }
            }
            "물품" -> {
                item { Button(onClick = { open("asset") }) { Text("물품 추가") } }
                if (assets.isEmpty()) item { Text("등록된 물품이 없어요") }
                items(assets, key = { it.id }) { asset ->
                    val current = loans.firstOrNull { it.assetId == asset.id && it.returnedAt == null }
                    Column {
                        Text(asset.name, style = MaterialTheme.typography.titleMedium)
                        Text(if (current == null) "대여 가능" else "대여 중 · ${members.firstOrNull { it.id == current.memberId }?.name ?: "회원 정보 없음"}")
                        if (asset.details.isNotBlank()) Text(asset.details)
                        Row {
                            TextButton(onClick = { open("asset", asset.id, asset.name, asset.details) }) { Text("수정") }
                            if (current == null) TextButton(onClick = { open("loan", asset.id) }) { Text("대여") }
                            else TextButton(onClick = { runAction { dao.returnLoan(current.id) } }) { Text("반납") }
                        }
                        loans.filter { it.assetId == asset.id && it.returnedAt != null }.take(3).forEach { loan ->
                            Text("대여 기록 · ${members.firstOrNull { it.id == loan.memberId }?.name ?: "회원 정보 없음"} · 반납 완료")
                        }
                    }
                }
            }
            "지원자" -> {
                item { Button(onClick = { open("applicant") }) { Text("지원자 추가") } }
                if (applicants.isEmpty()) item { Text("등록된 지원자가 없어요") }
                items(applicants, key = { it.id }) { person ->
                    Column {
                        Text("${person.name} · ${person.status}", style = MaterialTheme.typography.titleMedium)
                        Text("${person.phone} ${person.interviewAt}")
                        if (person.notes.isNotBlank()) Text(person.notes)
                        TextButton(onClick = { open("applicant", person.id, person.name, person.phone, person.status, person.interviewAt, person.notes) }) { Text("수정") }
                    }
                }
            }
            "회의" -> {
                item { Button(onClick = { open("meeting") }) { Text("회의 기록 추가") } }
                if (meetings.isEmpty()) item { Text("회의 기록이 없어요") }
                items(meetings, key = { it.id }) { meeting ->
                    Column {
                        Text(meeting.title, style = MaterialTheme.typography.titleMedium)
                        Text(meeting.heldAt)
                        Text(meeting.minutes)
                        Row {
                            TextButton(onClick = { open("meeting", meeting.id, meeting.title, meeting.heldAt, meeting.minutes) }) { Text("수정") }
                            TextButton(onClick = { open("decision", meeting.id) }) { Text("결정 추가") }
                        }
                        decisions.filter { it.meetingId == meeting.id }.forEach { decision ->
                            Text("${if (decision.done) "✓" else "○"} ${decision.text} · ${decision.responsible} · ${decision.dueAt}")
                            Row {
                                TextButton(onClick = { open("decision", meeting.id, decision.text, decision.responsible, decision.dueAt, decision.id) }) { Text("수정") }
                                TextButton(onClick = { runAction { dao.update(decision.copy(done = !decision.done)) } }) { Text(if (decision.done) "미완료" else "완료") }
                            }
                        }
                        CopyMeeting(meeting, decisions.filter { it.meetingId == meeting.id }, onMessage)
                    }
                }
            }
        }
    }

    if (dialog.isNotBlank()) AlertDialog(
        onDismissRequest = { if (!saving) discard = true },
        title = { Text(when (dialog) { "expense" -> "지출·환불 기록"; "asset" -> "물품"; "loan" -> "물품 대여"; "applicant" -> "지원자"; "meeting" -> "회의 기록"; else -> "결정" }) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (dialog) {
                    "expense" -> {
                        OutlinedTextField(field1, { field1 = it }, label = { Text("분류") })
                        OutlinedTextField(field2, { field2 = it }, label = { Text("메모") })
                        OutlinedTextField(field3, { field3 = it }, label = { Text("금액 원 · 업체 환급/정정은 음수") })
                        OutlinedTextField(field4, { field4 = it }, label = { Text("기록자") })
                    }
                    "asset" -> {
                        OutlinedTextField(field1, { field1 = it }, label = { Text("물품명") })
                        OutlinedTextField(field2, { field2 = it }, label = { Text("설명") })
                    }
                    "loan" -> {
                        Text("대여할 회원")
                        members.forEach { member -> FilterChip(field1 == member.id, { field1 = member.id }, label = { Text(member.name) }) }
                        OutlinedTextField(field2, { field2 = it }, label = { Text("반납 예정일 YYYY-MM-DD · 선택") })
                    }
                    "applicant" -> {
                        OutlinedTextField(field1, { field1 = it }, label = { Text("이름") })
                        OutlinedTextField(field2, { field2 = it }, label = { Text("전화번호") })
                        OutlinedTextField(field3, { field3 = it }, label = { Text("상태") })
                        OutlinedTextField(field4, { field4 = it }, label = { Text("면접 일시") })
                        OutlinedTextField(field5, { field5 = it }, label = { Text("메모") })
                        if (field2.isNotBlank() && (members.any { normalizePhone(it.phone) == normalizePhone(field2) } || applicants.any { it.id != selectedId && normalizePhone(it.phone) == normalizePhone(field2) })) Text("같은 연락처가 있어요. 회원과 자동 병합되지 않습니다.")
                    }
                    "meeting" -> {
                        OutlinedTextField(field1, { field1 = it }, label = { Text("제목") })
                        OutlinedTextField(field2, { field2 = it }, label = { Text("회의 일시") })
                        OutlinedTextField(field3, { field3 = it }, label = { Text("회의록") })
                    }
                    "decision" -> {
                        OutlinedTextField(field1, { field1 = it }, label = { Text("결정 사항") })
                        OutlinedTextField(field2, { field2 = it }, label = { Text("담당") })
                        OutlinedTextField(field3, { field3 = it }, label = { Text("기한") })
                    }
                }
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                save {
                    when (dialog) {
                        "expense" -> {
                            val amount = field3.trim().toLongOrNull() ?: throw IllegalArgumentException("금액을 숫자로 입력해 주세요")
                            requireExpenseAmount(amount)
                            require(field1.isNotBlank() && field2.isNotBlank()) { "분류와 메모를 입력해 주세요" }
                            dao.insert(ExpenseEntry(eventId = selectedId, category = field1.trim(), memo = field2.trim(), amountWon = amount, actor = field4.trim().ifBlank { "운영자" }))
                        }
                        "asset" -> {
                            require(field1.isNotBlank()) { "물품명을 입력해 주세요" }
                            val asset = ClubAsset(id = selectedId.ifBlank(::newId), name = field1.trim(), details = field2.trim())
                            if (selectedId.isBlank()) dao.insert(asset) else dao.update(asset)
                        }
                        "loan" -> {
                            require(field1.isNotBlank()) { "회원을 선택해 주세요" }
                            val due = if (field2.isBlank()) null else try { LocalDate.parse(field2.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() } catch (_: DateTimeParseException) { throw IllegalArgumentException("반납 예정일을 YYYY-MM-DD로 입력해 주세요") }
                            dao.lend(selectedId, field1, due)
                        }
                        "applicant" -> {
                            require(field1.isNotBlank()) { "이름을 입력해 주세요" }
                            val old = applicants.firstOrNull { it.id == selectedId }
                            val person = Applicant(id = selectedId.ifBlank(::newId), name = field1.trim(), phone = field2.trim(), status = field3.trim().ifBlank { "접수" }, interviewAt = field4.trim(), notes = field5.trim(), memberId = old?.memberId)
                            if (old == null) dao.insert(person) else dao.update(person)
                        }
                        "meeting" -> {
                            require(field1.isNotBlank()) { "제목을 입력해 주세요" }
                            val meeting = MeetingNote(id = selectedId.ifBlank(::newId), title = field1.trim(), heldAt = field2.trim(), minutes = field3.trim())
                            if (selectedId.isBlank()) dao.insert(meeting) else dao.update(meeting)
                        }
                        "decision" -> {
                            require(field1.isNotBlank()) { "결정 사항을 입력해 주세요" }
                            val old = decisions.firstOrNull { it.id == field4 }
                            val decision = MeetingDecision(id = field4.ifBlank(::newId), meetingId = selectedId, text = field1.trim(), responsible = field2.trim(), dueAt = field3.trim(), done = old?.done ?: false)
                            if (old == null) dao.insert(decision) else dao.update(decision)
                        }
                    }
                }
            }, enabled = !saving) { Text("저장") }
        },
        dismissButton = { TextButton(onClick = { discard = true }, enabled = !saving) { Text("취소") } },
    )
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("입력을 버릴까요?") }, text = { Text("저장하지 않은 내용은 사라집니다.") }, confirmButton = { TextButton(onClick = ::close) { Text("버리기") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("계속 작성") } })
}

@Composable private fun CopyMeeting(meeting: MeetingNote, decisions: List<MeetingDecision>, onMessage: (String) -> Unit) {
    val context = LocalContext.current
    TextButton(onClick = {
        val text = buildString {
            appendLine("${meeting.title} · ${meeting.heldAt}")
            appendLine(meeting.minutes)
            decisions.forEach { appendLine("${if (it.done) "[완료]" else "[미완료]"} ${it.text} · ${it.responsible} · ${it.dueAt}") }
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("회의록", text))
        onMessage("회의록을 복사했어요")
    }) { Text("회의록 복사") }
}
