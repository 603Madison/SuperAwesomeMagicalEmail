package com.email.app.mail

import com.email.api.Action
import com.email.api.Address
import com.email.api.ComposeMode
import com.email.api.Draft
import com.email.api.Folder
import com.email.api.FolderRole
import com.email.api.MailAccount
import com.email.api.MessageSummary
import com.email.app.net.ServerApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What's being looked at: an account's folder, or every inbox together ([ALL], "INBOX"). */
data class MailView(val account: String = ALL, val folder: String = "INBOX")

const val ALL = "all"

/** The messages in the folder being looked at, a page at a time. */
data class Listing(
    val key: String = "",
    val messages: List<MessageSummary> = emptyList(),
    val next: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val problems: Map<String, String> = emptyMap(),
)

/** A message to write: what it starts with, and files shared to the app to attach. */
data class ComposeRequest(val seed: Draft, val shared: List<android.net.Uri> = emptyList(), val id: Long = System.nanoTime())

/**
 * The mail the app shows, as state the screens watch: accounts, their folders, the list being
 * looked at, and what's being written. Changes show at once and are put right if the server
 * says otherwise.
 */
class MailModel(private val api: ServerApi, private val scope: CoroutineScope) {
    private val _accounts = MutableStateFlow<List<MailAccount>?>(null)
    val accounts: StateFlow<List<MailAccount>?> = _accounts.asStateFlow()

    private val _accountsError = MutableStateFlow<String?>(null)
    val accountsError: StateFlow<String?> = _accountsError.asStateFlow()

    private val _folders = MutableStateFlow<Map<String, List<Folder>>>(emptyMap())
    val folders: StateFlow<Map<String, List<Folder>>> = _folders.asStateFlow()

    private val _unread = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unread: StateFlow<Map<String, Int>> = _unread.asStateFlow()

    private val _view = MutableStateFlow(MailView())
    val view: StateFlow<MailView> = _view.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _list = MutableStateFlow(Listing())
    val list: StateFlow<Listing> = _list.asStateFlow()

    private val _composing = MutableStateFlow<ComposeRequest?>(null)
    val composing: StateFlow<ComposeRequest?> = _composing.asStateFlow()

    private var loading: Job? = null

    private fun keyOf(view: MailView, query: String) = "${view.account}\u0000${view.folder}\u0000$query"

    fun loadAccounts(): Job = scope.launch {
        try {
            val accounts = api.accounts()
            _accounts.value = accounts
            _accountsError.value = null
            // One account: its own inbox, rather than "all inboxes" of one.
            if (accounts.size == 1 && _view.value.account == ALL) _view.value = MailView(accounts[0].id)
            if (_view.value.account != ALL && accounts.none { it.id == _view.value.account }) _view.value = MailView()
            accounts.forEach { loadFolders(it.id) }
            open(_view.value, _query.value, force = _list.value.key.isEmpty())
        } catch (e: Exception) {
            _accountsError.value = e.message ?: "Couldn't load your accounts"
            if (_accounts.value == null) _accounts.value = emptyList()
        }
    }

    fun loadFolders(accountId: String) = scope.launch {
        runCatching { api.folders(accountId) }.onSuccess { folders ->
            _folders.update { it + (accountId to folders) }
            folders.firstOrNull { it.role == FolderRole.INBOX }?.let { inbox -> _unread.update { it + (accountId to inbox.unread) } }
        }
    }

    /** Shows [view], searched for [query], from its first page. */
    fun open(view: MailView, query: String = "", force: Boolean = false) {
        val key = keyOf(view, query)
        _view.value = view
        _query.value = query
        if (!force && _list.value.key == key && _list.value.error == null) return
        _list.value = Listing(key = key, loading = true)
        load(key, view, query, null)
    }

    fun loadMore() {
        val current = _list.value
        if (current.next == null || current.loading) return
        _list.value = current.copy(loading = true)
        load(current.key, _view.value, _query.value, current.next)
    }

    /** The current view again from the top (pull to refresh, new mail). */
    fun refresh() {
        val key = keyOf(_view.value, _query.value)
        _list.update { it.copy(key = key, refreshing = true) }
        load(key, _view.value, _query.value, null)
        val account = _view.value.account
        if (account == ALL) _accounts.value?.forEach { loadFolders(it.id) } else loadFolders(account)
    }

    private fun load(key: String, view: MailView, query: String, cursor: String?) {
        loading?.takeIf { cursor == null }?.cancel()
        loading = scope.launch {
            try {
                val page = api.messages(view.account, view.folder, cursor, query.ifBlank { null })
                _list.update { current ->
                    if (current.key != key) return@update current
                    val messages = if (cursor == null) page.messages else current.messages + page.messages.filter { m -> current.messages.none { it.id == m.id } }
                    Listing(key, messages, page.next, loading = false, refreshing = false, error = null, problems = page.problems)
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _list.update { if (it.key == key) it.copy(loading = false, refreshing = false, error = e.message ?: "Couldn't load the messages") else it }
            }
        }
    }

    /** Changes a message in the list (read, starred…). */
    fun update(id: String, change: (MessageSummary) -> MessageSummary) {
        _list.update { list -> list.copy(messages = list.messages.map { if (it.id == id) change(it) else it }) }
    }

    /** Does [action] to [ids]: shown at once, put back if the server says no. */
    suspend fun act(ids: List<String>, action: Action, folder: String? = null) {
        val before = _list.value
        val leaves = action in setOf(Action.ARCHIVE, Action.TRASH, Action.DELETE, Action.JUNK, Action.NOT_JUNK, Action.MOVE)
        _list.update { list ->
            list.copy(
                messages = if (leaves) list.messages.filter { it.id !in ids } else list.messages.map { m ->
                    if (m.id !in ids) m else when (action) {
                        Action.READ -> m.copy(unread = false)
                        Action.UNREAD -> m.copy(unread = true)
                        Action.FLAG -> m.copy(flagged = true)
                        Action.UNFLAG -> m.copy(flagged = false)
                        else -> m
                    }
                },
            )
        }
        try {
            val result = api.act(ids, action, folder)
            result.failed.values.firstOrNull()?.let { throw IllegalStateException(it) }
            before.messages.filter { it.id in ids }.map { it.accountId }.distinct().forEach { loadFolders(it) }
        } catch (e: Exception) {
            _list.update { if (it.key == before.key) before else it }
            throw e
        }
    }

    fun setUnread(counts: Map<String, Int>) = _unread.update { it + counts }

    fun account(id: String): MailAccount? = _accounts.value?.firstOrNull { it.id == id }

    fun folder(accountId: String, path: String): Folder? = _folders.value[accountId]?.firstOrNull { it.path == path }

    /** A new message (to [to], with [subject] and [body] when a mail link gave them). */
    fun write(to: List<Address> = emptyList(), subject: String = "", body: String = "", shared: List<android.net.Uri> = emptyList()): Boolean {
        val account = _accounts.value?.firstOrNull { it.id == _view.value.account } ?: _accounts.value?.firstOrNull() ?: return false
        val signature = account.signature.trim().takeIf { it.isNotEmpty() }?.let { "\n\n-- \n$it" }.orEmpty()
        _composing.value = ComposeRequest(Draft(accountId = account.id, to = to, subject = subject, text = body + signature), shared)
        return true
    }

    /** A reply, reply to all or forward of [id], or a draft opened again, as the server starts it. */
    suspend fun writeFrom(id: String, mode: ComposeMode) {
        val seed = api.compose(id, mode)
        val signature = account(seed.accountId)?.signature?.trim()?.takeIf { it.isNotEmpty() && mode != ComposeMode.DRAFT }?.let { "\n\n-- \n$it" }.orEmpty()
        _composing.value = ComposeRequest(seed.copy(text = signature + seed.text, html = null))
    }

    fun doneWriting() {
        _composing.value = null
    }

    /** Signed out: nothing of the last account's mail stays. */
    fun reset() {
        loading?.cancel()
        _accounts.value = null
        _folders.value = emptyMap()
        _unread.value = emptyMap()
        _view.value = MailView()
        _query.value = ""
        _list.value = Listing()
        _composing.value = null
    }
}
