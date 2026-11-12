package com.email.app.ui

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.email.api.MailAccount
import com.email.app.BuildConfig
import com.email.app.data.Palette
import com.email.app.data.ThemeMode
import kotlinx.coroutines.launch

/** How the app looks and tells you about mail, your accounts' signatures, and signing out. */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = appContainer()
    val settings by c.settings.collectAsState()
    val accounts by c.mail.accounts.collectAsState()
    val scope = rememberCoroutineScope()
    var signing by remember { mutableStateOf<MailAccount?>(null) }
    var signingOut by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        ScreenTitle("Settings", leading = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Section("Mail accounts") {
                accounts.orEmpty().forEach { a ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { signing = a }.background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(BrandGradient), contentAlignment = Alignment.Center) {
                            Text(a.label.ifBlank { a.email }.take(1).uppercase(), color = Color.White, style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(a.label.ifBlank { a.email }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(a.email + if (a.managed) " · from your admin" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            a.problem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 2) }
                        }
                        if (a.problem != null) Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error)
                    }
                }
                Text(
                    "Tap an account for its signature. Accounts are added on the server: an admin gives you mailboxes, and you can add your own in the web app's Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Section("Reading") {
                Toggle("Tell me about new mail", "Checks about every 15 minutes while the app is closed.", settings.notifications) { on ->
                    scope.launch { c.prefs.update { it.copy(notifications = on) } }
                }
                Toggle("Always show images from the web", "Off: they're hidden until you ask, so senders can't tell when you read their mail.", settings.alwaysShowImages) { on ->
                    scope.launch { c.prefs.update { it.copy(alwaysShowImages = on) } }
                }
            }

            Section("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Palette.entries.forEach { p ->
                        val selected = p == settings.palette && !settings.wallpaperColors
                        Row(
                            Modifier.clip(RoundedCornerShape(50))
                                .border(2.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                .clickable { scope.launch { c.prefs.update { it.copy(palette = p, wallpaperColors = false) } } }
                                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(24.dp).clip(CircleShape).background(Brush.linearGradient(0.5f to Color(p.swatchA), 0.5f to Color(p.swatchB))))
                            Spacer(Modifier.width(8.dp))
                            Text(p.label, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Text("Style", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { scope.launch { c.prefs.update { it.copy(themeMode = mode) } } },
                            shape = SegmentedButtonDefaults.itemShape(i, ThemeMode.entries.size),
                        ) { Text(mode.label) }
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    Toggle("Wallpaper colours", "Your phone's colours instead of the theme's.", settings.wallpaperColors) { on ->
                        scope.launch { c.prefs.update { it.copy(wallpaperColors = on) } }
                    }
                }
                Toggle("Bold text", "All text in Lexend Bold.", settings.boldText) { on ->
                    scope.launch { c.prefs.update { it.copy(boldText = on) } }
                }
            }

            Section("Signed in") {
                Text(settings.user?.let { "${it.displayName} (${it.username})" }.orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text("${settings.serverName.ifBlank { "Server" }} · ${settings.server}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = { signingOut = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Logout, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sign out")
                }
            }
            Text("SuperAwesomeMagicalEmail ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
            Spacer(Modifier.height(24.dp))
        }
    }

    signing?.let { account ->
        var signature by remember(account.id) { mutableStateOf(account.signature) }
        AlertDialog(
            onDismissRequest = { signing = null },
            title = { Text("Signature for ${account.email}") },
            text = {
                OutlinedTextField(signature, { signature = it }, minLines = 3, modifier = Modifier.fillMaxWidth(), label = { Text("Added below what you write") })
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { c.api.setSignature(account.id, signature) }
                            .onSuccess { c.mail.loadAccounts(); c.tell("Saved") }
                            .onFailure { c.tell(it.message ?: "Couldn't save it") }
                        signing = null
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { signing = null }) { Text("Cancel") } },
        )
    }
    if (signingOut) {
        AlertDialog(
            onDismissRequest = { signingOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your mail stays on the server; sign in again any time.") },
            confirmButton = { TextButton(onClick = { signingOut = false; scope.launch { c.signOut() } }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { signingOut = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}

@Composable
private fun Toggle(label: String, help: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked, onChange)
    }
}
