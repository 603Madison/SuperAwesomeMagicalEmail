package com.email.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.ViewStream
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.email.api.Folder
import com.email.api.FolderRole
import com.email.api.MailAccount
import com.email.app.R
import com.email.app.mail.ALL
import com.email.app.mail.MailView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Signed in: the mailbox, a message, writing, settings; the accounts and their folders in a drawer. */
@Composable
fun MainScaffold() {
    val c = appContainer()
    val context = LocalContext.current
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    val settings by c.settings.collectAsState()

    LaunchedEffect(Unit) { c.messages.collect { snackbar.showSnackbar(it) } }

    // A message tapped in a notification.
    val opening by c.openMessage.collectAsState()
    LaunchedEffect(opening) {
        opening?.let {
            nav.navigate("message/$it") { launchSingleTop = true }
            c.openMessage.value = null
        }
    }

    // Something to write (new, a reply, a mail link from another app).
    val composing by c.mail.composing.collectAsState()
    LaunchedEffect(composing?.id) {
        if (composing != null && nav.currentDestination?.route != "compose") nav.navigate("compose")
    }

    // While the app's open, new mail shows in it (and isn't announced later).
    LaunchedEffect(settings.server) {
        while (true) {
            runCatching {
                val news = c.notifier.check(notify = false)
                if (news.isNotEmpty()) c.mail.refresh()
            }
            delay(60_000)
        }
    }

    // Android 13 and later ask before an app may show notifications.
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) c.tell("New mail won't be announced: allow notifications in Android's settings")
    }
    LaunchedEffect(settings.notifications) {
        if (settings.notifications && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val accounts by c.mail.accounts.collectAsState()
    val folders by c.mail.folders.collectAsState()
    val unread by c.mail.unread.collectAsState()
    val view by c.mail.view.collectAsState()

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = route == "mail" || drawer.isOpen,
        drawerContent = {
            MailDrawer(
                accounts = accounts.orEmpty(),
                folders = folders,
                unread = unread,
                view = view,
                userName = settings.user?.displayName.orEmpty(),
                onPick = { picked ->
                    c.mail.open(picked)
                    nav.popBackStack("mail", inclusive = false)
                    scope.launch { drawer.close() }
                },
                onSettings = {
                    nav.navigate("settings") { launchSingleTop = true }
                    scope.launch { drawer.close() }
                },
            )
        },
    ) {
        Scaffold(snackbarHost = { SnackbarHost(snackbar) }, contentWindowInsets = WindowInsets(0)) { padding ->
            Backdrop(Modifier.padding(padding)) {
                NavHost(nav, startDestination = "mail") {
                    composable("mail") {
                        MailboxRoute(
                            onMenu = { scope.launch { drawer.open() } },
                            onOpen = { m ->
                                if (m.draft) scope.launch { runCatching { c.mail.writeFrom(m.id, com.email.api.ComposeMode.DRAFT) }.onFailure { c.tell(it.message ?: "Couldn't open the draft") } }
                                else nav.navigate("message/${m.id}")
                            },
                        )
                    }
                    composable("message/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                        MessageRoute(entry.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
                    }
                    composable("compose") { ComposeRoute(onDone = { nav.popBackStack() }) }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }
}

private fun roleIcon(role: FolderRole): ImageVector = when (role) {
    FolderRole.INBOX -> Icons.Outlined.Inbox
    FolderRole.DRAFTS -> Icons.Outlined.Description
    FolderRole.SENT -> Icons.AutoMirrored.Outlined.Send
    FolderRole.ARCHIVE -> Icons.Outlined.Archive
    FolderRole.JUNK -> Icons.Outlined.Report
    FolderRole.TRASH -> Icons.Outlined.Delete
    FolderRole.ALL -> Icons.Outlined.ViewStream
    FolderRole.FLAGGED -> Icons.Outlined.StarOutline
    FolderRole.OTHER -> Icons.Outlined.Folder
}

/** Every inbox together, then each account's folders, as on the web app's sidebar. */
@Composable
fun MailDrawer(
    accounts: List<MailAccount>,
    folders: Map<String, List<Folder>>,
    unread: Map<String, Int>,
    view: MailView,
    userName: String,
    onPick: (MailView) -> Unit,
    onSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 12.dp, vertical = 12.dp)) {
            Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(BrandGradient)) {
                    androidx.compose.foundation.Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Text("SuperAwesomeMagicalEmail", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (accounts.size > 1) {
                DrawerItem("All inboxes", Icons.Outlined.Inbox, unread.values.sum(), view.account == ALL) { onPick(MailView(ALL, "INBOX")) }
            }
            accounts.forEach { account ->
                Row(Modifier.padding(start = 16.dp, top = 18.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        account.label.ifBlank { account.email }.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        letterSpacing = 0.8.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (account.problem != null) Icon(Icons.Outlined.Warning, account.problem, Modifier.padding(start = 6.dp).size(16.dp), tint = MaterialTheme.colorScheme.error)
                }
                val list = folders[account.id]
                if (list == null) {
                    DrawerItem("Inbox", Icons.Outlined.Inbox, unread[account.id] ?: 0, view.account == account.id && view.folder == "INBOX") { onPick(MailView(account.id, "INBOX")) }
                } else {
                    list.filter { it.selectable }.forEach { f ->
                        val count = when (f.role) {
                            FolderRole.SENT, FolderRole.TRASH, FolderRole.JUNK -> 0
                            FolderRole.DRAFTS -> f.total
                            else -> f.unread
                        }
                        DrawerItem(folderName(f.role, f.name), roleIcon(f.role), count, view.account == account.id && view.folder == f.path, indent = f.depth) {
                            onPick(MailView(account.id, f.path))
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            DrawerItem("Settings", Icons.Outlined.Settings, 0, false, onClick = onSettings)
            if (userName.isNotBlank()) {
                Text(userName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
            }
        }
    }
}

@Composable
private fun DrawerItem(label: String, icon: ImageVector, count: Int, selected: Boolean, indent: Int = 0, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, null) },
        badge = { if (count > 0) Text(count.toString(), fontWeight = FontWeight.Bold) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(start = (indent.coerceAtMost(4) * 14).dp).fillMaxWidth().height(48.dp),
        colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = androidx.compose.ui.graphics.Color.Transparent),
    )
}
