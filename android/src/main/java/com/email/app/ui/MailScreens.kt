package com.email.app.ui

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Environment
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.ReplyAll
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MarkEmailUnread
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.email.api.Action
import com.email.api.Address
import com.email.api.Attachment
import com.email.api.ComposeMode
import com.email.api.FolderRole
import com.email.api.Message
import com.email.api.MessageSummary
import com.email.app.mail.ALL
import kotlinx.coroutines.launch

// ---------------------------------------------------------------- The mailbox

/** What the list shows. */
data class MailboxState(
    val title: String,
    val subtitle: String?,
    val messages: List<MessageSummary>,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    val problems: List<String> = emptyList(),
    val query: String = "",
    /** Account labels to tag messages with, when several accounts' mail is shown together. */
    val accountLabels: Map<String, String> = emptyMap(),
    /** Sent mail and drafts show who they're to rather than who they're from. */
    val outgoing: Boolean = false,
    val hasMore: Boolean = false,
    val noAccounts: Boolean = false,
)

class MailboxActions(
    val onMenu: () -> Unit = {},
    val onOpen: (MessageSummary) -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onLoadMore: () -> Unit = {},
    val onSearch: (String) -> Unit = {},
    val onAct: (MessageSummary, Action) -> Unit = { _, _ -> },
    val onWrite: () -> Unit = {},
)

@Composable
fun MailboxRoute(onMenu: () -> Unit, onOpen: (MessageSummary) -> Unit) {
    val c = appContainer()
    val list by c.mail.list.collectAsState()
    val view by c.mail.view.collectAsState()
    val query by c.mail.query.collectAsState()
    val accounts by c.mail.accounts.collectAsState()
    val folders by c.mail.folders.collectAsState()
    val error by c.mail.accountsError.collectAsState()
    val scope = rememberCoroutineScope()
    val account = accounts?.firstOrNull { it.id == view.account }
    val folder = folders[view.account]?.firstOrNull { it.path == view.folder }
    val title = when {
        view.account == ALL -> "All inboxes"
        folder != null -> folderName(folder.role, folder.name)
        view.folder == "INBOX" -> "Inbox"
        else -> view.folder
    }
    MailboxScreen(
        MailboxState(
            title = title,
            subtitle = account?.let { it.label.ifBlank { it.email } } ?: if (view.account == ALL) "${accounts?.size ?: 0} accounts" else null,
            messages = list.messages,
            loading = list.loading || accounts == null,
            refreshing = list.refreshing,
            error = list.error ?: error,
            problems = list.problems.map { (id, problem) -> "${accounts?.firstOrNull { it.id == id }?.label ?: "An account"}: $problem" },
            query = query,
            accountLabels = if (view.account == ALL && (accounts?.size ?: 0) > 1) accounts.orEmpty().associate { it.id to it.label.ifBlank { it.email } } else emptyMap(),
            outgoing = folder?.role == FolderRole.SENT || folder?.role == FolderRole.DRAFTS,
            hasMore = list.next != null,
            noAccounts = accounts?.isEmpty() == true,
        ),
        MailboxActions(
            onMenu = onMenu,
            onOpen = onOpen,
            onRefresh = { c.mail.refresh() },
            onLoadMore = { c.mail.loadMore() },
            onSearch = { c.mail.open(view, it) },
            onAct = { m, action ->
                scope.launch {
                    runCatching { c.mail.act(listOf(m.id), action) }.onFailure { c.tell(it.message ?: "Couldn't do that") }
                }
            },
            onWrite = { if (!c.mail.write()) c.tell("Add a mail account first (in the web app's Settings)") },
        ),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailboxScreen(state: MailboxState, actions: MailboxActions) {
    var searching by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    var search by rememberSaveable(state.query) { mutableStateOf(state.query) }
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= listState.layoutInfo.totalItemsCount - 6 } == true } }
    LaunchedEffect(nearEnd, state.hasMore) { if (nearEnd && state.hasMore) actions.onLoadMore() }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            ScreenTitle(
                state.title,
                state.subtitle,
                leading = { IconButton(onClick = actions.onMenu) { Icon(Icons.Outlined.Menu, "Mailboxes") } },
            ) {
                IconButton(onClick = { searching = !searching; if (!searching && state.query.isNotEmpty()) actions.onSearch("") }) {
                    Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, if (searching) "Stop searching" else "Search")
                }
            }
            AnimatedVisibility(searching) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    placeholder = { Text("Search ${state.title.lowercase()}") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { actions.onSearch(search.trim()) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            state.problems.forEach { Notice(it) }
            state.error?.let { Notice(it) }
            PullToRefreshBox(isRefreshing = state.refreshing, onRefresh = actions.onRefresh, modifier = Modifier.weight(1f)) {
                when {
                    state.noAccounts -> EmptyState(
                        { Icon(Icons.Outlined.Inbox, null, Modifier.size(32.dp)) },
                        "No mailbox yet",
                        "An admin can set one up for you on the server, or you can add your own account in the web app's Settings.",
                    )
                    state.messages.isEmpty() && !state.loading && state.error == null -> EmptyState(
                        { Icon(Icons.Outlined.TaskAlt, null, Modifier.size(32.dp)) },
                        if (state.query.isNotEmpty()) "Nothing found" else "All caught up",
                        if (state.query.isNotEmpty()) "No messages match \"${state.query}\"." else "No messages here.",
                    )
                    else -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 96.dp)) {
                        items(state.messages, key = { it.id }) { m ->
                            SwipeableRow(m, state, actions)
                        }
                        if (state.loading) item { Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                    }
                }
            }
        }
        if (!state.noAccounts) {
            ExtendedFloatingActionButton(
                onClick = actions.onWrite,
                icon = { Icon(Icons.Outlined.Edit, null) },
                text = { Text("Write") },
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp),
            )
        }
    }
}

@Composable
private fun Notice(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(text, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
    }
}

/** A message in the list; swipe right to archive, left to delete. */
@Composable
private fun SwipeableRow(m: MessageSummary, state: MailboxState, actions: MailboxActions) {
    val swipe = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { actions.onAct(m, Action.ARCHIVE); true }
                SwipeToDismissBoxValue.EndToStart -> { actions.onAct(m, Action.TRASH); true }
                else -> false
            }
        },
    )
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {
            // Only while it's being swiped: at rest the row sits over the backdrop.
            if (swipe.dismissDirection == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val archive = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (archive) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (archive) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Icon(if (archive) Icons.Outlined.Archive else Icons.Outlined.Delete, if (archive) "Archive" else "Delete",
                    tint = if (archive) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        MessageRow(
            m, state.outgoing, state.accountLabels[m.accountId],
            swiping = swipe.dismissDirection != SwipeToDismissBoxValue.Settled,
            onOpen = { actions.onOpen(m) },
            onStar = { actions.onAct(m, if (m.flagged) Action.UNFLAG else Action.FLAG) },
        )
    }
}

@Composable
fun MessageRow(m: MessageSummary, outgoing: Boolean, accountLabel: String?, swiping: Boolean = false, onOpen: () -> Unit, onStar: () -> Unit) {
    val who = if (outgoing) (if (m.to.isEmpty()) "(no recipients)" else "To: " + m.to.joinToString(", ") { it.shown }) else m.from?.shown ?: "(unknown sender)"
    val weight = if (m.unread) FontWeight.Bold else FontWeight.Normal
    Row(
        Modifier.fillMaxWidth().background(if (swiping) MaterialTheme.colorScheme.background else Color.Transparent).clickable(onClick = onOpen).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(10.dp).padding(top = 18.dp)) {
            if (m.unread) Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
        Avatar(if (outgoing) m.to.firstOrNull() else m.from)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (m.draft) Text("Draft  ", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                Text(who, style = MaterialTheme.typography.titleSmall, fontWeight = if (m.unread) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(shortDate(m.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            }
            Text(m.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    buildAnnotatedString {
                        if (accountLabel != null) {
                            pushStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary))
                            append(accountLabel)
                            append(" · ")
                            pop()
                        }
                        append(m.preview)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (m.hasAttachments) Icon(Icons.Outlined.AttachFile, "Has attachments", Modifier.padding(start = 4.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        IconButton(onClick = onStar) {
            Icon(if (m.flagged) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (m.flagged) "Unstar" else "Star",
                tint = if (m.flagged) Color(0xFFF2B01E) else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------------------------------------------------------------- A message

class MessageActions(
    val onBack: () -> Unit = {},
    val onShowImages: () -> Unit = {},
    val onReply: (ComposeMode) -> Unit = {},
    val onAct: (Action, String?) -> Unit = { _, _ -> },
    val onAttachment: (Attachment) -> Unit = {},
    val onLink: (String) -> Unit = {},
    val onWriteTo: (Address) -> Unit = {},
)

@Composable
fun MessageRoute(id: String, onBack: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val settings by c.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var images by rememberSaveable(id) { mutableStateOf(settings.alwaysShowImages) }
    var message by remember(id) { mutableStateOf<Message?>(null) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val folders by c.mail.folders.collectAsState()
    LaunchedEffect(id, images) {
        try {
            message = c.api.message(id, images)
            c.mail.update(id) { it.copy(unread = false) }
            com.email.app.mail.NewMailNotifier.cancel(context, id)
        } catch (e: Exception) {
            error = e.message ?: "Couldn't open the message"
        }
    }
    val m = message
    MessageScreen(
        message = m,
        error = error,
        server = settings.server,
        moveTargets = m?.let { msg -> folders[msg.summary.accountId].orEmpty().filter { it.selectable && it.path != msg.summary.folder }.map { it.path to folderName(it.role, it.name) } }.orEmpty(),
        inTrash = m?.let { c.mail.folder(it.summary.accountId, it.summary.folder)?.role == FolderRole.TRASH } == true,
        actions = MessageActions(
            onBack = onBack,
            onShowImages = { images = true },
            onReply = { mode -> scope.launch { runCatching { c.mail.writeFrom(id, mode) }.onFailure { c.tell(it.message ?: "Couldn't start the reply") } } },
            onAct = { action, folder ->
                scope.launch {
                    try {
                        c.mail.act(listOf(id), action, folder)
                        when (action) {
                            Action.FLAG, Action.UNFLAG -> message = m?.copy(summary = m.summary.copy(flagged = action == Action.FLAG))
                            else -> onBack()
                        }
                    } catch (e: Exception) {
                        c.tell(e.message ?: "Couldn't do that")
                    }
                }
            },
            onAttachment = { a -> download(context, c.api.absolute(a.url) + "?download=1", a.filename); c.tell("Downloading ${a.filename}") },
            onLink = { url -> openInBrowser(context, url) },
            onWriteTo = { to -> c.mail.write(listOf(to)) },
        ),
    )
}

private fun download(context: Context, url: String, filename: String) {
    val manager = context.getSystemService(DownloadManager::class.java)
    val request = DownloadManager.Request(url.toUri())
        .setTitle(filename)
        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
        .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, filename)
    runCatching { manager.enqueue(request) }
}

@Composable
fun MessageScreen(message: Message?, error: String?, server: String, moveTargets: List<Pair<String, String>>, inTrash: Boolean, actions: MessageActions) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
            Spacer(Modifier.weight(1f))
            if (message != null) {
                IconButton(onClick = { actions.onAct(Action.ARCHIVE, null) }) { Icon(Icons.Outlined.Archive, "Archive") }
                IconButton(onClick = { actions.onAct(Action.TRASH, null) }) { Icon(Icons.Outlined.Delete, if (inTrash) "Delete for good" else "Delete") }
                IconButton(onClick = { actions.onAct(Action.UNREAD, null) }) { Icon(Icons.Outlined.MarkEmailUnread, "Mark unread") }
                val flagged = message.summary.flagged
                IconButton(onClick = { actions.onAct(if (flagged) Action.UNFLAG else Action.FLAG, null) }) {
                    Icon(if (flagged) Icons.Outlined.Star else Icons.Outlined.StarOutline, if (flagged) "Unstar" else "Star", tint = if (flagged) Color(0xFFF2B01E) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                        moveTargets.forEach { (path, name) ->
                            DropdownMenuItem(text = { Text("Move to $name") }, onClick = { menu = false; actions.onAct(Action.MOVE, path) })
                        }
                        DropdownMenuItem(text = { Text("Junk") }, onClick = { menu = false; actions.onAct(Action.JUNK, null) })
                        DropdownMenuItem(text = { Text("Download (.eml)") }, onClick = { menu = false; actions.onLink(server + message.rawUrl) })
                    }
                }
            }
        }
        when {
            error != null -> Notice(error)
            message == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            else -> MessageBody(message, server, actions)
        }
    }
}

@Composable
private fun MessageBody(message: Message, server: String, actions: MessageActions) {
    val s = message.summary
    var details by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp).navigationBarsPadding()) {
        Text(s.subject.ifBlank { "(no subject)" }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        Row(Modifier.fillMaxWidth().clickable { details = !details }.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(s.from, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(s.from?.shown ?: "(unknown sender)", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (details) s.from?.email.orEmpty() else "to " + (s.to + message.cc).joinToString(", ") { it.shown },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = if (details) 2 else 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Text(shortDate(s.date), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (details) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                People("To", s.to, actions)
                People("Cc", message.cc, actions)
                People("Bcc", message.bcc, actions)
                People("Replies go to", message.replyTo, actions)
                Text(fullDate(s.date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (message.remoteImages && !message.imagesShown) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Image, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Images from the web are hidden", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 10.dp))
                    TextButton(onClick = actions.onShowImages) { Text("Show") }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        val html = message.html
        if (html != null) {
            HtmlBody(html, server, actions.onLink, actions.onWriteTo)
        } else {
            PlainBody(message.text, actions.onLink)
        }
        val files = message.attachments.filter { !it.inline }
        if (files.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                files.forEach { a ->
                    Surface(onClick = { actions.onAttachment(a) }, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                                Icon(if (a.contentType.startsWith("image/")) Icons.Outlined.Image else Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(a.filename, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (a.size > 0) fileSize(a.size.toLong()) else a.contentType, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Outlined.Download, "Download", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (!s.draft) {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { actions.onReply(ComposeMode.REPLY) }) { Icon(Icons.AutoMirrored.Outlined.Reply, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reply") }
                if (s.to.size + message.cc.size > 1) {
                    FilledTonalButton(onClick = { actions.onReply(ComposeMode.REPLY_ALL) }) { Icon(Icons.AutoMirrored.Outlined.ReplyAll, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reply all") }
                }
                FilledTonalButton(onClick = { actions.onReply(ComposeMode.FORWARD) }) { Icon(Icons.AutoMirrored.Outlined.Forward, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Forward") }
            }
        } else {
            Button(onClick = { actions.onReply(ComposeMode.DRAFT) }, modifier = Modifier.padding(16.dp)) { Text("Edit draft") }
        }
    }
}

@Composable
private fun People(label: String, people: List<Address>, actions: MessageActions) {
    if (people.isEmpty()) return
    Row {
        Text("$label  ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            buildAnnotatedString {
                people.forEachIndexed { i, a ->
                    if (i > 0) append(", ")
                    withLink(LinkAnnotation.Clickable(a.email) { actions.onWriteTo(a) }) { append(a.name?.let { "$it <${a.email}>" } ?: a.email) }
                }
            },
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/**
 * HTML mail, made safe by the server, shown without JavaScript on white (as mail is written for),
 * as tall as it is so it scrolls with the rest. Links open in the browser; mail links write here.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlBody(html: String, server: String, onLink: (String) -> Unit, onWriteTo: (Address) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(horizontal = 8.dp).clip(RoundedCornerShape(16.dp)),
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.WHITE)
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.domStorageEnabled = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                isVerticalScrollBarEnabled = false
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url.toString()
                        if (url.startsWith("mailto:", ignoreCase = true)) {
                            parseAddress(Uri.decode(url.removePrefix("mailto:").substringBefore('?')))?.let(onWriteTo)
                        } else {
                            onLink(url)
                        }
                        return true
                    }

                    override fun onPageFinished(view: WebView, url: String?) = view.requestLayout()
                }
            }
        },
        update = { view ->
            if (view.tag != html) {
                view.tag = html
                view.loadDataWithBaseURL(server, html, "text/html", "utf-8", null)
            }
        },
    )
}

private val URL = Regex("https?://[^\\s<>\"']+[^\\s<>\"'.,;:!?)\\]]")

/** Plain-text mail in the app's own look, with its web addresses as links and quotes set apart. */
@Composable
private fun PlainBody(text: String, onLink: (String) -> Unit) {
    val linkStyle = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val quote = MaterialTheme.colorScheme.onSurfaceVariant
    Text(
        buildAnnotatedString {
            text.replace("\r\n", "\n").lines().forEachIndexed { i, line ->
                if (i > 0) append('\n')
                if (line.startsWith(">")) pushStyle(SpanStyle(color = quote))
                var at = 0
                URL.findAll(line).forEach { match ->
                    append(line.substring(at, match.range.first))
                    withLink(LinkAnnotation.Url(match.value, linkStyle) { onLink(match.value) }) { append(match.value) }
                    at = match.range.last + 1
                }
                append(line.substring(at))
                if (line.startsWith(">")) pop()
            }
        },
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
    )
}
