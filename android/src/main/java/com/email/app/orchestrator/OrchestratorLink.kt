package com.email.app.orchestrator

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import java.security.MessageDigest

/**
 * How one of the family's Android apps talks to SuperAwesomeMagicalOrchestrator on the same
 * phone: to sign in as whoever is signed in there, and to say what the person is doing so their
 * other devices can carry on with it.
 *
 * This file is the same in every app (only the package line differs): it's kept in
 * SAM-DeviceOrchestrator, `kits/android/`; change it there and copy. It needs nothing but
 * Android itself, and this in the app's manifest so the orchestrator can be seen:
 *
 *     <queries><package android:name="app.superawesomemagical.orchestrator" /></queries>
 *
 * Everything here may take a moment (it asks another app, which may ask its server): call it
 * off the main thread.
 */
@Suppress("ObsoleteSdkInt", "UseKtx") // the same file in apps with different minimum versions, and nothing but Android itself
object OrchestratorLink {
    const val PACKAGE = "app.superawesomemagical.orchestrator"
    private val PROVIDER: Uri = Uri.parse("content://$PACKAGE.link")

    /** The orchestrator's page asking the person whether this app may sign in as them. */
    const val ACTION_APPROVE = "$PACKAGE.APPROVE"

    /**
     * The SHA-256 of the certificate SuperAwesomeMagicalOrchestrator's releases are signed with
     * (lowercase hex, no colons). While this is empty (before its first signed release) nothing is
     * pinned, and the person confirms the server the first time instead: see [SignIn.Ticket.confirm].
     */
    val PINNED_CERTIFICATES: Set<String> = emptySet()

    sealed interface SignIn {
        /**
         * Hand [ticket] to the app's server at [url]: `POST <url>/api/auth/orchestrator`.
         *
         * [confirm]: the orchestrator app couldn't be told from an impostor by its certificate, and
         * the person hasn't yet agreed to this server through it. **Never use the ticket silently
         * then**: show [url] ("Continue as … at music.example.com") and go on only when the person
         * taps; then call [confirmed], so the same app and server needn't be asked about again.
         */
        data class Ticket(val url: String, val ticket: String, val confirm: Boolean, internal val certificate: String) : SignIn

        /** The person hasn't said this app may sign in as them yet: start [intent] for a result, and ask again on RESULT_OK. */
        data class NeedsApproval(val intent: Intent) : SignIn

        /** Not installed, nobody signed in, the app isn't listed on the orchestrator, or it couldn't be reached. [reason] is for a log. */
        data class Unavailable(val reason: String) : SignIn
    }

    /**
     * Who is signed in to the orchestrator on this phone. Until the person has let this app sign in
     * through it, only [signedIn] is told: the rest is empty.
     */
    data class Status(val signedIn: Boolean, val server: String, val username: String, val displayName: String)

    /**
     * The signing certificate of the app that answers as the orchestrator, or null when there's
     * none to trust: nothing answers, something under another package name answers, or its
     * certificate isn't one of [PINNED_CERTIFICATES] (when there are any).
     */
    private fun agentCertificate(context: Context): String? {
        val pm = context.packageManager
        val provider = pm.resolveContentProvider("$PACKAGE.link", 0) ?: return null
        if (provider.packageName != PACKAGE) return null
        val certificates = try {
            if (Build.VERSION.SDK_INT >= 28) {
                val signing = pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
                // Several signers at once is nothing the orchestrator's releases do.
                if (signing.hasMultipleSigners()) return null
                signing.signingCertificateHistory
            } else {
                @Suppress("DEPRECATION", "PackageManagerGetSignatures")
                pm.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES).signatures
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }
        // The certificate it's signed with now (the last of its history).
        val current = certificates?.lastOrNull() ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(current.toByteArray()).joinToString("") { "%02x".format(it) }
        return digest.takeIf { PINNED_CERTIFICATES.isEmpty() || it in PINNED_CERTIFICATES }
    }

    fun installed(context: Context): Boolean = agentCertificate(context) != null

    private fun call(context: Context, method: String, arg: String?, extras: Bundle? = null): Bundle? = try {
        if (agentCertificate(context) == null) null else context.contentResolver.call(PROVIDER, method, arg, extras)
    } catch (e: IllegalArgumentException) {
        null // not installed, or an older one without this
    } catch (e: SecurityException) {
        null
    }

    private fun prefs(context: Context) = context.getSharedPreferences("orchestrator_link", Context.MODE_PRIVATE)

    /** The person agreed to sign in to [ticket]'s server through the orchestrator app as it is now. */
    fun confirmed(context: Context, ticket: SignIn.Ticket) {
        prefs(context).edit().putString("agreed", "${ticket.certificate} ${ticket.url}").apply()
    }

    fun status(context: Context): Status? = call(context, "status", null)?.let {
        Status(it.getBoolean("signedIn"), it.getString("server").orEmpty(), it.getString("username").orEmpty(), it.getString("displayName").orEmpty())
    }

    /**
     * The app signed out: what the person agreed to goes with it, so nothing more is said to the
     * orchestrator ([publishActivity]) and the next sign-in through it is confirmed afresh.
     */
    fun forget(context: Context) {
        prefs(context).edit().remove("agreed").apply()
    }

    /** A ticket to sign in to this app's server ([app]: "music", "health", "video", "maps", "email"). */
    fun signIn(context: Context, app: String): SignIn {
        val answer = call(context, "signIn", app) ?: return SignIn.Unavailable("SuperAwesomeMagicalOrchestrator isn't installed")
        val url = answer.getString("url")
        val ticket = answer.getString("ticket")
        return when {
            answer.getBoolean("ok") && !url.isNullOrBlank() && !ticket.isNullOrBlank() -> {
                val certificate = agentCertificate(context) ?: return SignIn.Unavailable("SuperAwesomeMagicalOrchestrator isn't installed")
                val server = url.trimEnd('/')
                val known = certificate in PINNED_CERTIFICATES || prefs(context).getString("agreed", null) == "$certificate $server"
                SignIn.Ticket(server, ticket, confirm = !known, certificate = certificate)
            }
            answer.getBoolean("needsApproval") -> SignIn.NeedsApproval(Intent(ACTION_APPROVE).setPackage(PACKAGE).putExtra("app", app))
            else -> SignIn.Unavailable(answer.getString("error") ?: "No answer")
        }
    }

    /**
     * Says what the person is doing here ("Now playing", a video, a route), so another device can
     * offer to carry on: [url] is what opens it there. A null [title] says they stopped. Quietly
     * does nothing when there's no orchestrator, nobody is signed in, the person hasn't let this
     * app sign in through it, or the orchestrator app isn't the one this app signed in through.
     */
    fun publishActivity(context: Context, app: String, title: String?, detail: String = "", url: String = "") {
        val certificate = agentCertificate(context) ?: return
        if (certificate !in PINNED_CERTIFICATES && prefs(context).getString("agreed", null)?.startsWith("$certificate ") != true) return
        val extras = Bundle().apply {
            putString("app", app)
            title?.let { putString("title", it) }
            putString("detail", detail)
            putString("url", url)
        }
        call(context, "publishActivity", app, extras)
    }
}
