package com.email.server.mail

import com.email.api.ConnectionCheck
import com.email.api.Endpoint
import com.email.api.Security
import jakarta.mail.AuthenticationFailedException
import jakarta.mail.FolderClosedException
import jakarta.mail.FolderNotFoundException
import jakarta.mail.MessagingException
import jakarta.mail.Session
import jakarta.mail.StoreClosedException
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.eclipse.angus.mail.imap.IMAPStore
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLException

/** Something went wrong with a mail server; [message] says what, for people. */
class MailProblem(message: String, val signIn: Boolean = false, cause: Throwable? = null) : Exception(message, cause)

/**
 * The server's connections to mail servers. Each account keeps one IMAP connection, used by one
 * request at a time (IMAP connections aren't for sharing) and let go after a few idle minutes or
 * when the account's settings change. Mail is sent over a fresh SMTP connection each time.
 *
 * Only mail servers on public addresses are reached (unless [allowPrivateHosts]), so nobody can
 * point an account at the server's own network to look around it.
 */
class MailConnections(
    private val allowPrivateHosts: Boolean,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Told when an account stops (a message) or starts again (null) working. */
    private val onProblem: suspend (accountId: String, problem: String?) -> Unit = { _, _ -> },
) {
    private class Pooled(val version: String, val session: Session, var problem: String?) {
        val mutex = Mutex()
        var store: IMAPStore? = null

        @Volatile var lastUsed = 0L
    }

    private val pool = ConcurrentHashMap<String, Pooled>()

    /** Runs [block] with [account]'s IMAP connection (made, or made again, as needed). */
    suspend fun <T> imap(account: StoredAccount, block: (IMAPStore) -> T): T {
        val pooled = pooledFor(account)
        return pooled.mutex.withLock {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    try {
                        block(connected(pooled, account))
                    } catch (e: Exception) {
                        if (!lostConnection(e)) throw e
                        // Servers let idle connections go: once more, on a fresh one.
                        closeQuietly(pooled)
                        block(connected(pooled, account))
                    }
                }
            }
            pooled.lastUsed = clock()
            outcome.fold(
                onSuccess = { result ->
                    noteProblem(pooled, account, null)
                    result
                },
                onFailure = { error ->
                    if (error is com.email.server.ApiError) throw error
                    val problem = translate(account.imap, error)
                    if (problem.signIn || isConnectionFailure(error)) {
                        closeQuietly(pooled)
                        noteProblem(pooled, account, problem.message)
                    }
                    throw problem
                },
            )
        }
    }

    /** Sends [message] through [account]'s SMTP server. */
    suspend fun send(account: StoredAccount, message: MimeMessage) = withContext(Dispatchers.IO) {
        try {
            checkHost(account.smtp.host)
            val transport = smtpSession(account.smtp).getTransport("smtp")
            try {
                transport.connect(account.smtp.host, account.smtp.port, account.username, account.password)
                transport.sendMessage(message, message.allRecipients)
            } finally {
                runCatching { transport.close() }
            }
        } catch (e: Exception) {
            throw translate(account.smtp, e)
        }
    }

    /** Signs in to both servers with these settings (not saved yet), and says how it went. */
    suspend fun check(username: String, password: String, imap: Endpoint, smtp: Endpoint): ConnectionCheck = withContext(Dispatchers.IO) {
        val imapResult = runCatching {
            checkHost(imap.host)
            val store = imapSession(imap).getStore("imap") as IMAPStore
            store.connect(imap.host, imap.port, username, password)
            val folders = runCatching { store.defaultFolder.list("*").size }.getOrDefault(0)
            store.close()
            "Signed in to ${imap.host}" + if (folders > 0) " ($folders folders)" else ""
        }
        val smtpResult = runCatching {
            checkHost(smtp.host)
            val transport = smtpSession(smtp).getTransport("smtp")
            transport.connect(smtp.host, smtp.port, username, password)
            transport.close()
            "Signed in to ${smtp.host}"
        }
        ConnectionCheck(
            ok = imapResult.isSuccess && smtpResult.isSuccess,
            imap = imapResult.getOrElse { translate(imap, it).message.orEmpty() },
            smtp = smtpResult.getOrElse { translate(smtp, it).message.orEmpty() },
        )
    }

    /** Lets go of [accountId]'s connection (its settings changed, or it was removed). */
    fun forget(accountId: String) {
        pool.remove(accountId)?.let { old -> scope.launch { old.mutex.withLock { closeQuietly(old) } } }
    }

    /** Lets go of connections nobody has used for [idleMs]. */
    suspend fun closeIdle(idleMs: Long = 5 * 60_000L) {
        val now = clock()
        pool.values.filter { it.store != null && now - it.lastUsed > idleMs }.forEach { pooled ->
            if (pooled.mutex.tryLock()) {
                try {
                    withContext(Dispatchers.IO) { closeQuietly(pooled) }
                } finally {
                    pooled.mutex.unlock()
                }
            }
        }
    }

    fun closeAll() = pool.values.forEach { closeQuietly(it) }

    private fun pooledFor(account: StoredAccount): Pooled {
        var replaced: Pooled? = null
        val pooled = pool.compute(account.id) { _, existing ->
            if (existing != null && existing.version == account.version) {
                existing
            } else {
                replaced = existing
                Pooled(account.version, imapSession(account.imap), account.problem)
            }
        }!!
        replaced?.let { old -> scope.launch { old.mutex.withLock { closeQuietly(old) } } }
        return pooled
    }

    private fun connected(pooled: Pooled, account: StoredAccount): IMAPStore {
        pooled.store?.let { if (it.isConnected) return it }
        closeQuietly(pooled)
        if (account.password.isEmpty()) throw MailProblem("${account.email} has no saved password", signIn = true)
        checkHost(account.imap.host)
        val store = pooled.session.getStore("imap") as IMAPStore
        store.connect(account.imap.host, account.imap.port, account.username, account.password)
        pooled.store = store
        return store
    }

    private suspend fun noteProblem(pooled: Pooled, account: StoredAccount, problem: String?) {
        if (pooled.problem == problem) return
        pooled.problem = problem
        if (problem != null) log.warn("{} ({}): {}", account.email, account.id, problem)
        runCatching { onProblem(account.id, problem) }
    }

    private fun closeQuietly(pooled: Pooled) {
        pooled.store?.let { runCatching { it.close() } }
        pooled.store = null
    }

    private fun checkHost(host: String) {
        if (host.isBlank()) throw MailProblem("No mail server is set")
        if (allowPrivateHosts) return
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: UnknownHostException) {
            throw MailProblem("Couldn't find the mail server $host", cause = e)
        }
        if (addresses.any(::isPrivate)) {
            throw MailProblem("$host is on a private network, which this server doesn't connect to (SAME_ALLOW_PRIVATE_MAIL_HOSTS=true allows it)")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger("same.mail")

        /** Loopback, private, link-local, carrier-grade NAT and unique-local addresses. */
        fun isPrivate(address: InetAddress): Boolean {
            val bytes = address.address
            return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress ||
                address.isAnyLocalAddress || address.isMulticastAddress ||
                (address is Inet6Address && (bytes[0].toInt() and 0xfe) == 0xfc) ||
                (address is Inet4Address && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xc0) == 64) ||
                (address is Inet4Address && bytes[0].toInt() == 0)
        }

        fun imapSession(endpoint: Endpoint): Session = Session.getInstance(
            Properties().apply {
                val p = "mail.imap."
                put("${p}host", endpoint.host)
                put("${p}port", endpoint.port.toString())
                put("${p}ssl.enable", (endpoint.security == Security.SSL).toString())
                put("${p}starttls.enable", (endpoint.security == Security.STARTTLS).toString())
                put("${p}starttls.required", (endpoint.security == Security.STARTTLS).toString())
                put("${p}ssl.checkserveridentity", "true")
                put("${p}connectiontimeout", "15000")
                put("${p}timeout", "60000")
                put("${p}writetimeout", "60000")
                // Reading a message doesn't mark it read by itself: the server does that when asked.
                put("${p}peek", "true")
                // Bigger pieces, so attachments come down in fewer round trips.
                put("${p}fetchsize", (1024 * 1024).toString())
                put("${p}appendbuffersize", (4 * 1024 * 1024).toString())
            },
        )

        fun smtpSession(endpoint: Endpoint): Session = Session.getInstance(
            Properties().apply {
                val p = "mail.smtp."
                put("${p}host", endpoint.host)
                put("${p}port", endpoint.port.toString())
                put("${p}auth", "true")
                put("${p}ssl.enable", (endpoint.security == Security.SSL).toString())
                put("${p}starttls.enable", (endpoint.security == Security.STARTTLS).toString())
                put("${p}starttls.required", (endpoint.security == Security.STARTTLS).toString())
                put("${p}ssl.checkserveridentity", "true")
                put("${p}connectiontimeout", "15000")
                put("${p}timeout", "60000")
                put("${p}writetimeout", "120000")
            },
        )

        /** A session for building messages (sending uses the account's own). */
        val buildSession: Session = Session.getInstance(Properties())

        private fun causes(e: Throwable) = generateSequence(e) { it.cause.takeIf { cause -> cause !== it } }

        private fun lostConnection(e: Throwable) = causes(e).any { it is StoreClosedException || it is FolderClosedException }

        private fun isConnectionFailure(e: Throwable) = causes(e).any {
            it is UnknownHostException || it is ConnectException || it is SocketTimeoutException || it is SSLException ||
                it is StoreClosedException || (it is MessagingException && it.message?.contains("Couldn't connect", ignoreCase = true) == true)
        }

        /** What went wrong, in words for people. */
        fun translate(endpoint: Endpoint, e: Throwable): MailProblem {
            if (e is MailProblem) return e
            val host = endpoint.host
            val chain = causes(e).toList()
            return when {
                chain.any { it is AuthenticationFailedException } ->
                    MailProblem("$host didn't accept the username or password${detail(chain.first { it is AuthenticationFailedException })}", signIn = true, cause = e)
                chain.any { it is UnknownHostException } -> MailProblem("Couldn't find the mail server $host", cause = e)
                chain.any { it is ConnectException } -> MailProblem("$host refused the connection on port ${endpoint.port}", cause = e)
                chain.any { it is SocketTimeoutException } -> MailProblem("$host didn't answer in time", cause = e)
                chain.any { it is SSLException } ->
                    MailProblem("Couldn't make a secure connection to $host:${endpoint.port} (check SSL or STARTTLS: ${chain.first { it is SSLException }.message})", cause = e)
                chain.any { it is FolderNotFoundException } -> MailProblem("That folder isn't there any more", cause = e)
                e is MessagingException || e is IOException -> MailProblem("$host: ${e.message ?: e.javaClass.simpleName}", cause = e)
                else -> MailProblem("Something went wrong talking to $host (${e.message ?: e.javaClass.simpleName})", cause = e)
            }
        }

        private fun detail(e: Throwable) = e.message?.trim()?.takeIf { it.isNotEmpty() && it.length < 160 && !it.equals("failed", true) }
            ?.let { " ($it)" }.orEmpty()
    }
}
