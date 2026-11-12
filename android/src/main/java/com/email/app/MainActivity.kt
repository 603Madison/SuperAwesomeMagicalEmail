package com.email.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.email.api.Address
import com.email.app.ui.MainScaffold
import com.email.app.ui.SignInScreen
import com.email.app.ui.theme.EmailTheme
import com.email.app.ui.theme.isAppInDarkTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** The scrims Android's default edge-to-edge puts behind three-button navigation. */
private val LIGHT_SCRIM = android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
private val DARK_SCRIM = android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)

class MainActivity : ComponentActivity() {
    private val finishing = mutableStateOf(false)
    private val signInError = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handle(intent)
        val c = container
        setContent {
            val settings by c.settings.collectAsState()
            // The status and navigation bars' icons follow the app's theme, which may not be the phone's.
            val dark = isAppInDarkTheme(settings.themeMode)
            LaunchedEffect(dark) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { dark },
                )
            }
            EmailTheme(settings.themeMode, settings.wallpaperColors, settings.boldText, settings.palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    if (settings.signedIn && !finishing.value) MainScaffold() else SignInScreen(finishing.value, signInError.value)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStart() {
        super.onStart()
        container.inForeground = true
    }

    override fun onStop() {
        super.onStop()
        container.inForeground = false
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        val c = container
        intent.getStringExtra(EXTRA_MESSAGE)?.let {
            c.openMessage.value = it
            return
        }
        val link = intent.data
        when {
            // The server's sign-in page sends the app back here with its one-time code.
            link?.scheme == "samemail" && link.host == "signed-in" -> {
                finishing.value = true
                signInError.value = null
                lifecycleScope.launch {
                    try {
                        c.signIn.finish(link)
                    } catch (e: Exception) {
                        signInError.value = e.message ?: "Couldn't finish signing in"
                    }
                    finishing.value = false
                }
            }
            link?.scheme == "mailto" -> {
                val (to, subject, body) = mailto(link)
                write { c.mail.write(to, subject, body) }
            }
            intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE -> {
                val files = shared(intent)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
                val to = intent.getStringArrayExtra(Intent.EXTRA_EMAIL).orEmpty().map { Address(it) }
                write { c.mail.write(to, subject, text, files) }
            }
        }
    }

    /** Starts writing once the accounts are known (they load as the app starts). */
    private fun write(start: () -> Boolean) {
        val c = container
        lifecycleScope.launch {
            if (!c.settings.value.signedIn) {
                c.tell("Sign in first")
                return@launch
            }
            c.mail.accounts.first { it != null }
            if (!start()) c.tell("Add a mail account first")
        }
    }

    @Suppress("DEPRECATION")
    private fun shared(intent: Intent): List<Uri> = when {
        intent.action == Intent.ACTION_SEND_MULTIPLE ->
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> listOfNotNull(
            if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM),
        )
    }

    companion object {
        const val EXTRA_MESSAGE = "com.email.app.MESSAGE"

        /** Who, what about and what to say, from a mailto: link (RFC 6068). */
        fun mailto(link: Uri): Triple<List<Address>, String, String> {
            val raw = link.toString().removePrefix("mailto:")
            val to = Uri.decode(raw.substringBefore('?')).split(',').map { it.trim() }.filter { it.contains('@') }.map { Address(it) }
            val query = raw.substringAfter('?', "").split('&').mapNotNull { pair ->
                pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].lowercase() to Uri.decode(it[1]) }
            }.toMap()
            val more = query["to"]?.split(',')?.map { it.trim() }?.filter { it.contains('@') }?.map { Address(it) }.orEmpty()
            return Triple(to + more, query["subject"].orEmpty(), query["body"].orEmpty())
        }
    }
}
