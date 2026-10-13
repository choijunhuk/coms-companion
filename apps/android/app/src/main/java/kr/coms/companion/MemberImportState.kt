package kr.coms.companion

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

class MemberImportState : ViewModel() {
    var rows by mutableStateOf<List<ImportRow>?>(null)
    var error by mutableStateOf("")
}
