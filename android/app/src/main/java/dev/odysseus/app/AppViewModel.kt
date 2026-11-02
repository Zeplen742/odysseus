package dev.odysseus.app

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

enum class Screen { SETUP, SESSIONS, CHAT }

class AppViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("odysseus", Context.MODE_PRIVATE)
    private var api: OdysseusApi? = null

    var screen by mutableStateOf(Screen.SETUP); private set
    var baseUrl by mutableStateOf(prefs.getString("url", "") ?: "")
    var token by mutableStateOf(prefs.getString("token", "") ?: "")
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set

    var sessions by mutableStateOf<List<SessionInfo>>(emptyList()); private set
    var currentId by mutableStateOf<String?>(null); private set
    var messages by mutableStateOf<List<ChatMessage>>(emptyList()); private set
    /** Assistant text for the turn currently streaming (or being resumed). */
    var live by mutableStateOf(""); private set
    var status by mutableStateOf<String?>(null); private set
    var streaming by mutableStateOf(false); private set

    private var turnJob: Job? = null

    init {
        if (baseUrl.isNotBlank() && token.isNotBlank()) {
            api = OdysseusApi(baseUrl, token)
            screen = Screen.SESSIONS
            refreshSessions()
        }
    }

    /** Accepts either a bare token or the pairing JSON ({"host","port","token"}). */
    fun connect(urlInput: String, tokenInput: String) = viewModelScope.launch {
        error = null; busy = true
        try {
            var url = urlInput.trim(); var tok = tokenInput.trim()
            if (tok.startsWith("{")) {
                val o = JSONObject(tok)
                tok = o.getString("token")
                if (url.isBlank()) url = "http://${o.getString("host")}:${o.getInt("port")}"
            }
            if (url.isBlank() || tok.isBlank()) { error = "Enter the server URL and token"; return@launch }
            if (!url.startsWith("http")) url = "https://$url"
            val candidate = OdysseusApi(url, tok)
            candidate.ping()
            prefs.edit().putString("url", url).putString("token", tok).apply()
            baseUrl = url; token = tok; api = candidate
            screen = Screen.SESSIONS
            refreshSessions()
        } catch (e: Exception) {
            error = describe(e)
        } finally { busy = false }
    }

    fun signOut() {
        turnJob?.cancel()
        prefs.edit().clear().apply()
        api = null; token = ""; sessions = emptyList(); currentId = null
        screen = Screen.SETUP
    }

    fun refreshSessions() = viewModelScope.launch {
        val a = api ?: return@launch
        busy = true; error = null
        try { sessions = a.listSessions() } catch (e: Exception) { error = describe(e) } finally { busy = false }
    }

    fun newChat() = viewModelScope.launch {
        val a = api ?: return@launch
        try { openSession(a.newSession()) } catch (e: Exception) { error = describe(e) }
    }

    fun openSession(id: String) {
        val a = api ?: return
        turnJob?.cancel()
        currentId = id; messages = emptyList(); live = ""; status = null; error = null
        screen = Screen.CHAT
        turnJob = viewModelScope.launch {
            try {
                messages = a.history(id)
                // A run may still be going (or buffered) server-side: re-attach to it.
                if (a.hasActiveRun(id)) runTurn(id, null, resumeOnly = true)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = describe(e) }
        }
    }

    fun send(text: String) {
        val id = currentId ?: return
        if (streaming || text.isBlank()) return
        messages = messages + ChatMessage("user", text)
        turnJob = viewModelScope.launch { runTurn(id, text, resumeOnly = false) }
    }

    /** Called when the app returns to the foreground: re-attach if the stream died while away. */
    fun onForeground() {
        val id = currentId ?: return
        if (screen != Screen.CHAT || streaming) return
        val a = api ?: return
        turnJob = viewModelScope.launch {
            try { if (a.hasActiveRun(id)) runTurn(id, null, resumeOnly = true) } catch (_: Exception) {}
        }
    }

    private suspend fun runTurn(id: String, message: String?, resumeOnly: Boolean) {
        val a = api ?: return
        streaming = true; live = ""; error = null
        val runner = TurnRunner(open = { first, lastId, onEvent -> a.openTurn(id, message, first, lastId, onEvent) })
        try {
            val outcome = runner.run(
                resumeOnly = resumeOnly,
                onDelta = { live += it },
                onStatus = { status = it },
            )
            if (outcome is TurnOutcome.Failed) error = outcome.message
        } finally {
            status = null; streaming = false
            // The saved history is canonical (tool output, edits, replacements); prefer it over our deltas.
            try { messages = a.history(id); live = "" } catch (_: Exception) {}
        }
    }

    fun back() { turnJob?.cancel(); streaming = false; status = null; live = ""; screen = Screen.SESSIONS; refreshSessions() }

    private fun describe(e: Exception) = when (e) {
        is HttpException -> if (e.code == 401) "Token rejected (401). Re-pair the device." else "Server error ${e.code}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
