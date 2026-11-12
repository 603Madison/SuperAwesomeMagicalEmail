package com.email.app.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.content.edit
import androidx.core.net.toUri
import com.email.app.data.Prefs
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Signing in to the server, as SuperAwesomeMagicalMusic's app does. With single sign-on
 * (Authentik) the server's own page does it, in a browser tab: the person signs in there (or
 * already is), agrees to sign the app in, and the page hands back a one-time code at
 * `samemail://signed-in`, which only works together with the secret this app made for that
 * sign-in (PKCE), so another app catching the link gets nothing.
 */
class SignIn(private val context: Context, private val api: ServerApi, private val prefs: Prefs) {
    private val pending = context.getSharedPreferences("sign-in", Context.MODE_PRIVATE)

    suspend fun signInWithPassword(server: String, username: String, password: String) {
        val config = api.authConfig(server)
        val signedIn = api.login(server, username, password, deviceName())
        prefs.signIn(server, signedIn.token ?: throw ServerException(0, "The server sent no sign-in"), signedIn.user, config.serverName)
    }

    /** Opens the server's sign-in page in a browser tab. */
    fun startBrowserSignIn(server: String) {
        val verifier = token(48)
        val state = token(24)
        pending.edit {
            putString("server", server)
            putString("verifier", verifier)
            putString("state", state)
        }
        val url = "$server/connect-app".toUri().buildUpon()
            .appendQueryParameter("challenge", challenge(verifier))
            .appendQueryParameter("state", state)
            .appendQueryParameter("device", deviceName())
            .build()
        CustomTabsIntent.Builder().setShowTitle(true).build().apply {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }.launchUrl(context, url)
    }

    /** Finishes a browser sign-in from the link the server's page opened. */
    suspend fun finish(link: Uri) {
        val server = pending.getString("server", null) ?: throw ServerException(0, "That sign-in wasn't started here; try again")
        val verifier = pending.getString("verifier", null) ?: throw ServerException(0, "That sign-in expired; try again")
        val expected = pending.getString("state", null)
        val code = link.getQueryParameter("code") ?: throw ServerException(0, "The server sent no sign-in code")
        if (expected == null || link.getQueryParameter("state") != expected) throw ServerException(0, "That sign-in link wasn't for this sign-in")
        pending.edit { clear() }
        val config = api.authConfig(server)
        val signedIn = api.exchangeCode(server, code, verifier, deviceName())
        prefs.signIn(server, signedIn.token ?: throw ServerException(0, "The server sent no sign-in"), signedIn.user, config.serverName)
    }

    companion object {
        private val random = SecureRandom()

        fun deviceName(): String = listOf(Build.MANUFACTURER.orEmpty().replaceFirstChar { it.uppercase() }, Build.MODEL.orEmpty())
            .filter { it.isNotBlank() }.distinct().joinToString(" ").take(60).ifBlank { "Android phone" }

        fun token(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

        /** The PKCE S256 challenge for [verifier]. */
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
    }
}
