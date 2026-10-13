package kr.coms.companion

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

val Context.operatorSettings by preferencesDataStore("operator-settings")

object SettingsKeys {
    val theme = stringPreferencesKey("theme")
    val semester = stringPreferencesKey("semester")
    val serverUrl = stringPreferencesKey("server-url")
    val adminToken = stringPreferencesKey("admin-token")
    val syncEnabled = booleanPreferencesKey("sync-enabled")
}

data class OperatorConnection(val baseUrl: String, val adminToken: String)

suspend fun Context.readConnection(): OperatorConnection? {
    val values = operatorSettings.data.first()
    val url = values[SettingsKeys.serverUrl].orEmpty()
    val token = values[SettingsKeys.adminToken].orEmpty()
    if (url.isBlank() || token.isBlank()) return null
    return OperatorConnection(checkedBaseUrl(url, BuildConfig.DEBUG), token)
}
