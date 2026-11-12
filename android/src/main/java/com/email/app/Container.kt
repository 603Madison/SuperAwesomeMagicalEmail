package com.email.app

import android.content.Context
import android.util.Log
import com.email.app.data.Prefs
import com.email.app.data.Settings
import com.email.app.mail.MailModel
import com.email.app.mail.NewMailNotifier
import com.email.app.mail.NewMailWorker
import com.email.app.net.ServerApi
import com.email.app.net.SignIn
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Everything the app is made of; one lives in [EmailApp]. */
class Container(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Background task failed", e) })
    val prefs = Prefs(context)

    /** The settings, kept current (read once at start so the first requests know the server). */
    val settings: StateFlow<Settings> = prefs.settings.stateIn(scope, SharingStarted.Eagerly, runBlocking { prefs.current() })

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    val api = ServerApi(http, { settings.value.server }, { settings.value.token }, onSignedOut = { scope.launch { signOut(callServer = false) } })

    val signIn = SignIn(context, api, prefs)

    val mail = MailModel(api, scope)

    val notifier = NewMailNotifier(context, api, prefs)

    /** Whether the app is on screen (new mail then shows in it, rather than as notifications). */
    @Volatile var inForeground = false

    /** A message to open (from a notification), for the screens to pick up. */
    val openMessage = MutableStateFlow<String?>(null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Short messages for the snackbar. */
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun tell(message: String) {
        _messages.tryEmit(message)
    }

    fun start() {
        NewMailNotifier.createChannel(context)
        scope.launch {
            settings.map { it.signedIn to it.notifications }.distinctUntilChanged().collect { (signedIn, notify) ->
                NewMailWorker.schedule(context, signedIn && notify)
                if (signedIn) mail.loadAccounts() else mail.reset()
            }
        }
    }

    suspend fun signOut(callServer: Boolean = true) {
        if (callServer) api.logout()
        prefs.signOut()
        mail.reset()
    }

    companion object {
        private const val TAG = "Email"
    }
}
