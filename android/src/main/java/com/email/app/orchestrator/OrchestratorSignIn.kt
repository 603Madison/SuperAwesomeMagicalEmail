package com.email.app.orchestrator

import android.content.Context
import android.content.Intent
import com.email.app.attempt
import com.email.app.data.Prefs
import com.email.app.net.ServerApi
import com.email.app.net.ServerException
import com.email.app.net.SignIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What to do with what SuperAwesomeMagicalOrchestrator's agent on this phone says: the decisions,
 * apart from the phone, so they can be tested.
 */
object OrchestratorSteps {
    /** Why the agent is being asked. */
    enum class Why {
        /** The app opened, not signed in: quietly. */
        START,

        /** The person tapped the button. */
        TAPPED,

        /** The person just said yes on the orchestrator's page. */
        APPROVED,
    }

    sealed interface Step {
        /** Hand the ticket to the app's server, and carry on into the app. */
        data class HandIn(val ticket: OrchestratorLink.SignIn.Ticket) : Step

        /**
         * The orchestrator app can't yet be told from an impostor, and the person hasn't agreed to
         * this server through it: show the button with [url]'s host under it and wait for a tap.
         * The app syncs to whatever server it signs in to, so this is never skipped.
         */
        data class Confirm(val url: String) : Step

        /** Open the orchestrator's page asking whether this app may sign in as them, now. */
        data class AskApproval(val intent: Intent) : Step

        /** Show the sign-in screen with "Continue with SuperAwesomeMagicalOrchestrator" on it. */
        data class OfferApproval(val intent: Intent) : Step

        /** The usual sign-in, unchanged, and no error. */
        data object Usual : Step
    }

    /**
     * Whether to ask the agent by itself when the app opens: only when nobody is signed in, the
     * person didn't sign out on purpose (they chose to be signed out: it stays that way until they
     * sign in again), and it hasn't been tried since the app started.
     */
    fun asksAtStart(signedIn: Boolean, signedOutOnPurpose: Boolean, triedAlready: Boolean): Boolean =
        !signedIn && !signedOutOnPurpose && !triedAlready

    /**
     * [shownUrl]: the server whose address the button is showing, if any. A ticket that needs
     * confirming is only used on a tap, and only for the very address the person was looking at
     * when they tapped (tickets are short-lived, so the tap asks for a fresh one: if that names
     * another server, the new address is shown and nothing is used).
     */
    fun next(answer: OrchestratorLink.SignIn, why: Why, shownUrl: String? = null): Step = when (answer) {
        is OrchestratorLink.SignIn.Ticket -> when {
            !answer.confirm -> Step.HandIn(answer)
            why == Why.TAPPED && shownUrl == answer.url -> Step.HandIn(answer)
            else -> Step.Confirm(answer.url)
        }
        // Its page only opens when the person asked for it; never by itself, and never twice in a row.
        is OrchestratorLink.SignIn.NeedsApproval -> if (why == Why.TAPPED) Step.AskApproval(answer.intent) else Step.OfferApproval(answer.intent)
        is OrchestratorLink.SignIn.Unavailable -> Step.Usual
    }

    /**
     * The button's words on the sign-in screen, or null for no button. [confirming]: a ticket is
     * waiting for the person to agree to its server. (The orchestrator only tells who is signed in
     * once this app has been approved there: until then there's no name to show.)
     */
    fun label(approvalWaiting: Boolean, status: OrchestratorLink.Status?, confirming: Boolean = false): String? {
        val name = status?.takeIf { it.signedIn }?.let { it.displayName.ifBlank { it.username } }.orEmpty().trim()
        return when {
            approvalWaiting -> WITH_ORCHESTRATOR
            name.isNotEmpty() -> "Continue as $name"
            confirming || status?.signedIn == true -> WITH_ORCHESTRATOR
            else -> null
        }
    }

    /** "at mail.example.com": the server a tap would sign in to, said under the button. */
    fun place(url: String): String {
        val address = url.toHttpUrlOrNull()
        val host = when {
            address == null -> url
            address.port == okhttp3.HttpUrl.defaultPort(address.scheme) -> address.host
            else -> "${address.host}:${address.port}"
        }
        return "at $host"
    }

    const val WITH_ORCHESTRATOR = "Continue with SuperAwesomeMagicalOrchestrator"
}

/** How the agent is reached (the phone's, or a stand-in in tests). */
interface OrchestratorAgent {
    fun status(): OrchestratorLink.Status?

    fun signIn(): OrchestratorLink.SignIn

    /** The person agreed to sign in to [ticket]'s server through the orchestrator app as it is now. */
    fun confirmed(ticket: OrchestratorLink.SignIn.Ticket)
}

/**
 * Signing in through SuperAwesomeMagicalOrchestrator: where someone is signed in to it on this
 * phone, the app asks its agent for a short-lived ticket, hands that to its own server, and is
 * signed in as the same person, exactly as if they had signed in by hand.
 *
 * Any failure falls back to the usual sign-in, with no error shown: this is only ever a shortcut.
 */
class OrchestratorSignIn(
    private val agent: OrchestratorAgent,
    private val api: ServerApi,
    private val prefs: Prefs,
    private val deviceName: () -> String = SignIn::deviceName,
) {
    /**
     * What the sign-in screen shows: the button's words (null: no button), under it the server a
     * tap would sign in to ("at mail.example.com") when the person is being asked to agree to it,
     * and whether a ticket is being handed in.
     */
    data class Offer(val label: String? = null, val place: String? = null, val working: Boolean = false)

    private val _offer = MutableStateFlow(Offer())
    val offer: StateFlow<Offer> = _offer.asStateFlow()

    private val _approvals = MutableSharedFlow<Intent>(extraBufferCapacity = 1)

    /** The orchestrator's page to open for a result (the sign-in screen does; RESULT_OK: [approved]). */
    val approvals: SharedFlow<Intent> = _approvals.asSharedFlow()

    private val triedAtStart = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)

    @Volatile private var approval: Intent? = null
    @Volatile private var status: OrchestratorLink.Status? = null
    @Volatile private var gaveUp = false

    /** The server whose address the button shows, waiting for the person's tap. */
    @Volatile private var shownUrl: String? = null

    /** The app came to the front: who is signed in to the orchestrator, and (once) a quiet try at signing in. */
    fun opened(scope: CoroutineScope) {
        scope.launch { attempt { open() } }
    }

    internal suspend fun open() {
        val settings = prefs.current()
        if (settings.signedIn) return
        status = attempt { asked { agent.status() } }.getOrNull()
        show()
        if (OrchestratorSteps.asksAtStart(settings.signedIn, settings.signedOutOnPurpose, triedAtStart.getAndSet(true))) run(OrchestratorSteps.Why.START)
    }

    /** The person tapped the button. */
    fun tapped(scope: CoroutineScope) {
        scope.launch { attempt { run(OrchestratorSteps.Why.TAPPED) } }
    }

    /** The person said yes on the orchestrator's page. */
    fun approved(scope: CoroutineScope) {
        scope.launch { attempt { run(OrchestratorSteps.Why.APPROVED) } }
    }

    /** The orchestrator's page couldn't be opened: the usual sign-in. (Saying no there leaves the button, to change their mind.) */
    fun unavailable() {
        fellBack()
        show()
    }

    internal suspend fun run(why: OrchestratorSteps.Why) {
        if (!busy.compareAndSet(false, true)) return
        try {
            // Just approved: the orchestrator now says who is signed in.
            if (why == OrchestratorSteps.Why.APPROVED) status = attempt { asked { agent.status() } }.getOrNull() ?: status
            val answer = attempt { asked { agent.signIn() } }.getOrElse { OrchestratorLink.SignIn.Unavailable(it.message ?: "No answer") }
            when (val step = OrchestratorSteps.next(answer, why, shownUrl)) {
                is OrchestratorSteps.Step.HandIn -> {
                    approval = null
                    _offer.value = _offer.value.copy(working = true)
                    // The tap on the button showing this server's address was the person agreeing to it.
                    if (step.ticket.confirm) attempt { agent.confirmed(step.ticket) }
                    shownUrl = null
                    val worked = attempt { handIn(step.ticket.url, step.ticket.ticket) }.isSuccess
                    // Didn't work here (no account, an older server, no network): the usual sign-in, and no button to try in vain.
                    if (!worked) fellBack()
                }
                is OrchestratorSteps.Step.Confirm -> {
                    approval = null
                    shownUrl = step.url
                }
                is OrchestratorSteps.Step.AskApproval -> {
                    approval = step.intent
                    _approvals.tryEmit(step.intent)
                }
                is OrchestratorSteps.Step.OfferApproval -> approval = step.intent
                // Asked for by hand and nothing came of it: no button to try in vain.
                OrchestratorSteps.Step.Usual -> if (why != OrchestratorSteps.Why.START) fellBack()
            }
        } finally {
            busy.set(false)
            show(working = false)
        }
    }

    /** It didn't work: the button goes, and stays away until the app next starts, rather than fail again at every tap. */
    private fun fellBack() {
        gaveUp = true
        approval = null
        shownUrl = null
    }

    /** Hands [ticket] to the server at [url] and keeps the sign-in as every other way of signing in does. */
    internal suspend fun handIn(url: String, ticket: String) {
        val server = ServerApi.normalize(url) ?: throw ServerException(0, "The orchestrator gave no server address")
        val signedIn = api.orchestratorSignIn(server, ticket, deviceName())
        val token = signedIn.token ?: throw ServerException(0, "The server sent no sign-in")
        // Signed in already; the server's name is only for show.
        val name = attempt { api.authConfig(server).serverName }.getOrDefault("")
        prefs.signIn(server, token, signedIn.user, name)
    }

    private fun show(working: Boolean = _offer.value.working) {
        val url = shownUrl
        val label = if (gaveUp) null else OrchestratorSteps.label(approval != null, status, confirming = url != null)
        _offer.value = Offer(label, url?.takeIf { label != null }?.let(OrchestratorSteps::place), working)
    }

    /** The agent is another app, which may ask its server: never on the main thread. */
    private suspend fun <T> asked(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** This app's name in the orchestrator's list of the family's apps. */
        const val APP = "email"

        /**
         * The orchestrator on this phone, through the family's kit ([OrchestratorLink]).
         *
         * Only signing in goes through it. Email never tells the orchestrator what someone is
         * doing here (`OrchestratorLink.publishActivity` is never called): what a person is reading
         * or writing is private, and nothing about it (not a subject, not a sender, not even that a
         * message is open) leaves the phone this way, so there is nothing for their other devices
         * to "carry on" with.
         */
        fun onThisPhone(context: Context): OrchestratorAgent = object : OrchestratorAgent {
            override fun status(): OrchestratorLink.Status? = OrchestratorLink.status(context)

            override fun signIn(): OrchestratorLink.SignIn = OrchestratorLink.signIn(context, APP)

            override fun confirmed(ticket: OrchestratorLink.SignIn.Ticket) = OrchestratorLink.confirmed(context, ticket)
        }
    }
}
