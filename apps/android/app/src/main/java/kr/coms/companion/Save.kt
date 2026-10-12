package kr.coms.companion

import kotlinx.coroutines.CancellationException

suspend fun commitSave(save: suspend () -> Unit, onSuccess: () -> Unit, onError: (String) -> Unit) {
    try {
        save()
        onSuccess()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        onError(e.message ?: "저장하지 못했어요. 다시 시도해 주세요")
    }
}
