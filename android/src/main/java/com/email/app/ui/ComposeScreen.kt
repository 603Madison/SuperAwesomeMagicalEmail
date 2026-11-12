package com.email.app.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.email.api.Address
import com.email.api.AttachmentRef
import com.email.api.Contact
import com.email.api.Draft
import com.email.api.MailAccount
import com.email.app.mail.ComposeRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A file being attached: uploading, uploaded (with its id on the server), or failed. */
data class Attaching(val key: String, val name: String, val size: Long, val uploadId: String? = null, val error: String? = null)

@Composable
fun ComposeRoute(onDone: () -> Unit) {
    val c = appContainer()
    val context = LocalContext.current
    val request by c.mail.composing.collectAsState()
    val accounts by c.mail.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    val current = request ?: run {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    ComposeScreen(
        request = current,
        accounts = accounts.orEmpty(),
        suggest = { query -> runCatching { c.api.contacts(query) }.getOrDefault(emptyList()) },
        upload = { uri, onResult ->
            scope.launch {
                val (name, size) = describe(context, uri)
                try {
                    val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val upload = c.api.upload(name, type, size) { context.contentResolver.openInputStream(uri) ?: error("Couldn't read $name") }
                    onResult(Attaching(uri.toString(), name, size, uploadId = upload.id))
                } catch (e: Exception) {
                    onResult(Attaching(uri.toString(), name, size, error = e.message ?: "Didn't upload"))
                }
            }
        },
        send = { draft ->
            val result = c.api.send(draft)
            c.tell(result.note ?: "Sent")
            c.mail.loadFolders(draft.accountId)
            c.mail.doneWriting()
            onDone()
        },
        saveDraft = { draft -> c.api.saveDraft(draft).id },
        discard = { draftId ->
            draftId?.let { id -> runCatching { c.api.act(listOf(id), com.email.api.Action.DELETE) } }
            c.mail.doneWriting()
            onDone()
        },
        close = { saved ->
            if (saved) c.tell("Saved to Drafts")
            c.mail.doneWriting()
            onDone()
        },
        describe = { uri -> describe(context, uri) },
    )
}

/** A shared or picked file's name and size. */
private fun describe(context: Context, uri: Uri): Pair<String, Long> {
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "attachment"
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0)?.let { name = it }
                if (!cursor.isNull(1)) size = cursor.getLong(1)
            }
        }
    }
    return name to size
}

/** Writing a message: who it's to (with suggestions), what about, what it says, and attachments. */
@Composable
fun ComposeScreen(
    request: ComposeRequest,
    accounts: List<MailAccount>,
    suggest: suspend (String) -> List<Contact>,
    upload: (Uri, (Attaching) -> Unit) -> Unit,
    send: suspend (Draft) -> Unit,
    saveDraft: suspend (Draft) -> String?,
    discard: suspend (String?) -> Unit,
    close: (saved: Boolean) -> Unit,
    describe: (Uri) -> Pair<String, Long> = { it.lastPathSegment.orEmpty() to -1L },
) {
    val seed = request.seed
    val scope = rememberCoroutineScope()
    var accountId by remember(request.id) { mutableStateOf(seed.accountId) }
    val to = remember(request.id) { mutableStateListOf(*seed.to.toTypedArray()) }
    val cc = remember(request.id) { mutableStateListOf(*seed.cc.toTypedArray()) }
    val bcc = remember(request.id) { mutableStateListOf(*seed.bcc.toTypedArray()) }
    var copies by remember(request.id) { mutableStateOf(seed.cc.isNotEmpty() || seed.bcc.isNotEmpty()) }
    var subject by remember(request.id) { mutableStateOf(seed.subject) }
    var body by remember(request.id) { mutableStateOf(seed.text) }
    val kept = remember(request.id) { mutableStateListOf(*seed.attachments.toTypedArray()) }
    val files = remember(request.id) { mutableStateListOf<Attaching>() }
    var draftId by remember(request.id) { mutableStateOf(seed.draftId) }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var dirty by remember(request.id) { mutableStateOf(false) }

    fun attach(uri: Uri) {
        val (name, size) = describe(uri)
        files += Attaching(uri.toString(), name, size)
        dirty = true
        upload(uri) { done -> files.replaceAll { if (it.key == done.key) done else it } }
    }
    LaunchedEffect(request.id) { request.shared.forEach(::attach) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris -> uris.forEach(::attach) }

    fun draft() = Draft(
        accountId = accountId,
        to = to.toList(), cc = cc.toList(), bcc = bcc.toList(),
        subject = subject,
        text = body,
        uploads = files.mapNotNull { it.uploadId },
        attachments = kept.toList(),
        inReplyTo = seed.inReplyTo,
        forwardOf = seed.forwardOf,
        draftId = draftId,
    )

    val hasContent = to.isNotEmpty() || cc.isNotEmpty() || bcc.isNotEmpty() || subject.isNotBlank() || body.isNotBlank() || files.isNotEmpty()
    val leave: () -> Unit = {
        if (dirty && hasContent) {
            scope.launch {
                val saved = runCatching { saveDraft(draft()) }.isSuccess
                close(saved)
            }
        } else {
            close(false)
        }
    }
    BackHandler(onBack = leave)

    // Kept as a draft every half minute while it's being written.
    LaunchedEffect(request.id) {
        while (true) {
            delay(30_000)
            if (dirty && hasContent && !sending) runCatching { draftId = saveDraft(draft()); dirty = false }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = leave) { Icon(Icons.Outlined.Close, "Close (keeps a draft)") }
            Text(
                when {
                    seed.draftId != null -> "Draft"
                    seed.inReplyTo != null -> "Reply"
                    seed.forwardOf != null -> "Forward"
                    else -> "New message"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { picker.launch("*/*") }) { Icon(Icons.Outlined.AttachFile, "Attach files") }
            IconButton(onClick = { scope.launch { discard(draftId) } }) { Icon(Icons.Outlined.Delete, "Discard") }
            IconButton(
                enabled = !sending,
                onClick = {
                    error = null
                    when {
                        to.isEmpty() && cc.isEmpty() && bcc.isEmpty() -> error = "Who's it to?"
                        files.any { it.uploadId == null && it.error == null } -> error = "Wait for the attachments to finish uploading"
                        else -> scope.launch {
                            sending = true
                            try {
                                send(draft())
                            } catch (e: Exception) {
                                error = e.message ?: "Couldn't send it"
                                sending = false
                            }
                        }
                    }
                },
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Outlined.Send, "Send", tint = MaterialTheme.colorScheme.primary)
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            if (accounts.size > 1) {
                FromRow(accounts, accountId) { accountId = it; dirty = true }
                HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            }
            RecipientRow("To", to, suggest, onChange = { dirty = true }) {
                if (!copies) TextButton(onClick = { copies = true }) { Text("Cc Bcc") }
            }
            if (copies) {
                RecipientRow("Cc", cc, suggest, onChange = { dirty = true })
                RecipientRow("Bcc", bcc, suggest, onChange = { dirty = true })
            }
            FieldRow("Subject") {
                PlainField(subject, { subject = it; dirty = true }, "Subject", singleLine = true)
            }
            if (kept.isNotEmpty() || files.isNotEmpty()) {
                FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    kept.forEach { a: AttachmentRef ->
                        InputChip(
                            selected = false,
                            onClick = { kept.remove(a); dirty = true },
                            label = { Text(a.filename, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = { Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp)) },
                            trailingIcon = { Icon(Icons.Outlined.Close, "Remove ${a.filename}", Modifier.size(16.dp)) },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                    files.forEach { f ->
                        InputChip(
                            selected = false,
                            onClick = { files.remove(f); dirty = true },
                            label = { Text(f.name + if (f.uploadId != null && f.size > 0) "  ${fileSize(f.size)}" else "", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            leadingIcon = {
                                when {
                                    f.error != null -> Icon(Icons.Outlined.ErrorOutline, f.error, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
                                    f.uploadId == null -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                    else -> Icon(Icons.Outlined.AttachFile, null, Modifier.size(16.dp))
                                }
                            },
                            trailingIcon = { Icon(Icons.Outlined.Close, "Remove ${f.name}", Modifier.size(16.dp)) },
                            modifier = Modifier.padding(bottom = 4.dp),
                        )
                    }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 280.dp).padding(horizontal = 20.dp, vertical = 14.dp)) {
                PlainField(body, { body = it; dirty = true }, "Write your message", singleLine = false)
            }
        }
    }
}

@Composable
private fun FieldRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
        Box(Modifier.weight(1f)) { content() }
    }
}

@Composable
private fun PlainField(value: String, onChange: (String) -> Unit, hint: String, singleLine: Boolean, keyboard: KeyboardOptions = KeyboardOptions.Default, onDone: () -> Unit = {}) {
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = keyboard,
        keyboardActions = KeyboardActions(onDone = { onDone() }, onNext = { onDone() }),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                inner()
            }
        },
    )
}

@Composable
private fun FromRow(accounts: List<MailAccount>, selected: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val account = accounts.firstOrNull { it.id == selected }
    FieldRow("From") {
        Box {
            TextButton(onClick = { open = true }) { Text(account?.let { if (it.name.isNotBlank()) "${it.name} <${it.email}>" else it.email } ?: "Choose", maxLines = 1, overflow = TextOverflow.Ellipsis) }
            DropdownMenu(open, onDismissRequest = { open = false }) {
                accounts.forEach { a -> DropdownMenuItem(text = { Text(a.email) }, onClick = { open = false; onPick(a.id) }) }
            }
        }
    }
}

/** People to send to, as chips, with suggestions from addresses written to before. */
@Composable
private fun RecipientRow(
    label: String,
    people: MutableList<Address>,
    suggest: suspend (String) -> List<Contact>,
    onChange: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    var text by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<Contact>>(emptyList()) }
    LaunchedEffect(text) {
        val query = text.trim()
        suggestions = if (query.length < 2) emptyList() else {
            delay(150)
            suggest(query).filter { s -> people.none { it.email.equals(s.email, ignoreCase = true) } }
        }
    }
    fun commit() {
        val parts = text.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        val parsed = parts.map { parseAddress(it) }
        parsed.filterNotNull().forEach { a -> if (people.none { it.email.equals(a.email, true) }) people += a }
        text = parts.filterIndexed { i, _ -> parsed[i] == null }.joinToString(", ")
        if (parsed.any { it != null }) onChange()
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(60.dp))
            FlowRow(Modifier.weight(1f).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.Center) {
                people.forEach { a ->
                    InputChip(
                        selected = false,
                        onClick = { people.remove(a); onChange() },
                        label = { Text(a.shown, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(Icons.Outlined.Close, "Remove ${a.shown}", Modifier.size(16.dp)) },
                    )
                }
                Box(Modifier.widthIn(min = 120.dp).align(Alignment.CenterVertically)) {
                    PlainField(
                        text,
                        { value ->
                            if (value.endsWith(",") || value.endsWith(";") || value.endsWith(" ") && value.contains('@')) {
                                text = value
                                commit()
                            } else {
                                text = value
                            }
                        },
                        "",
                        singleLine = true,
                        keyboard = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                        onDone = { commit() },
                    )
                }
            }
            trailing()
        }
        suggestions.take(4).forEach { s ->
            TextButton(onClick = { people += Address(s.email, s.name); text = ""; onChange() }, modifier = Modifier.fillMaxWidth().padding(start = 64.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text(s.name ?: s.email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    if (s.name != null) Text(s.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
    }
}
