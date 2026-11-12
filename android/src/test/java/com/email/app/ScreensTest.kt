package com.email.app

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.email.api.Address
import com.email.api.Attachment
import com.email.api.Draft
import com.email.api.Endpoint
import com.email.api.Folder
import com.email.api.FolderRole
import com.email.api.MailAccount
import com.email.api.Message
import com.email.api.MessageSummary
import com.email.api.Provider
import com.email.api.Role
import com.email.api.User
import com.email.app.data.Palette
import com.email.app.data.ThemeMode
import com.email.app.mail.ComposeRequest
import com.email.app.mail.MailView
import com.email.app.ui.Backdrop
import com.email.app.ui.ComposeScreen
import com.email.app.ui.MailDrawer
import com.email.app.ui.MailboxActions
import com.email.app.ui.MailboxScreen
import com.email.app.ui.MailboxState
import com.email.app.ui.MessageActions
import com.email.app.ui.MessageScreen
import com.email.app.ui.SettingsScreen
import com.email.app.ui.SignInScreen
import com.email.app.ui.theme.EmailTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The screens, drawn as they'd look on a phone (saved to build/screens for a look), and that they come up. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val out = File("build/screens").apply { mkdirs() }

    private fun save(name: String) {
        compose.waitForIdle()
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun show(mode: ThemeMode = ThemeMode.LIGHT, palette: Palette = Palette.AURORA, content: @Composable () -> Unit) {
        compose.setContent {
            EmailTheme(mode, palette = palette) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Backdrop { content() } }
            }
        }
    }

    private val now = System.currentTimeMillis()
    private fun summary(i: Int, from: String, name: String, subject: String, preview: String, unread: Boolean = false, flagged: Boolean = false, attachments: Boolean = false, account: String = "a1") =
        MessageSummary("m$i", account, "INBOX", Address(from, name), listOf(Address("maddie@603norton.org")), subject, preview, now - i * 47 * 60_000L, unread, flagged, hasAttachments = attachments)

    private val messages = listOf(
        summary(1, "alex@example.com", "Alex Norton", "Re: Camping in June?", "I'm in! I can bring the big tent and the stove. Should we book the site at Lafayette Place?", unread = true),
        summary(2, "priya@example.com", "Priya", "Book club on the 20th?", "Still on for book club on the 20th? I'll bring the snacks this time.", unread = true, account = "a2"),
        summary(3, "appointments@clinic.example", "Dr. Patel's office", "Your appointment on Tuesday", "This is a reminder of your appointment on Tuesday 17 November at 9:40.", unread = true),
        summary(4, "sam@example.com", "Sam Rivera", "Lease renewal", "Hey! The landlord sent the renewal over. It's attached; can you look before Friday?", flagged = true, attachments = true),
        summary(30, "news@trailclub.example", "Trail Club", "This week: the Franconia Ridge loop", "Eight and a half miles, three summits and some of the best views in New England."),
        summary(40, "jo.norton@example.com", "Grandma Jo", "Thanksgiving plans", "Hi sweetie, are you and Sam still coming up on Wednesday? I'm making the cranberry sauce."),
    )

    private fun mailbox(mode: ThemeMode, name: String, palette: Palette = Palette.AURORA) {
        show(mode, palette) {
            MailboxScreen(
                MailboxState("All inboxes", "2 accounts", messages, accountLabels = mapOf("a1" to "603norton.org", "a2" to "Personal")),
                MailboxActions(),
            )
        }
        compose.onNodeWithText("Lease renewal").assertExists()
        save(name)
    }

    @Test
    fun `sign-in`() {
        show { SignInScreen(false, null) }
        compose.onNodeWithText("Server address").assertExists()
        save("sign-in")
    }

    @Test
    fun `the mailbox`() = mailbox(ThemeMode.LIGHT, "mailbox")

    @Test
    fun `the mailbox, dark`() = mailbox(ThemeMode.DARK, "mailbox-dark")

    @Test
    fun `the mailbox, in the Ocean theme`() = mailbox(ThemeMode.DARK, "mailbox-ocean-dark", Palette.OCEAN)

    @Test
    fun `nothing to read`() {
        show { MailboxScreen(MailboxState("Inbox", "603norton.org", emptyList()), MailboxActions()) }
        compose.onNodeWithText("All caught up").assertExists()
        save("mailbox-empty")
    }

    @Test
    fun `the drawer`() {
        val accounts = listOf(account("a1", "maddie@603norton.org", "603norton.org"), account("a2", "maddie@example.com", "Personal"))
        val folders = mapOf(
            "a1" to listOf(
                Folder("INBOX", "INBOX", FolderRole.INBOX, 3, 120), Folder("Drafts", "Drafts", FolderRole.DRAFTS, 0, 1), Folder("Sent", "Sent", FolderRole.SENT, 0, 40),
                Folder("Archive", "Archive", FolderRole.ARCHIVE, 0, 800), Folder("Receipts", "Receipts", FolderRole.OTHER, 2, 30), Folder("Trash", "Trash", FolderRole.TRASH, 0, 5),
            ),
            "a2" to listOf(Folder("INBOX", "INBOX", FolderRole.INBOX, 1, 20)),
        )
        show(ThemeMode.DARK) { MailDrawer(accounts, folders, mapOf("a1" to 3, "a2" to 1), MailView("a1", "INBOX"), "Maddie Norton", {}, {}) }
        compose.onNodeWithText("Receipts").assertExists()
        save("drawer-dark")
    }

    @Test
    fun `a message`() {
        val message = Message(
            summary = messages[5].copy(unread = false),
            text = "Hi sweetie,\n\nAre you and Sam still coming up on Wednesday? I'm making the cranberry sauce from scratch this year.\n\nLove,\nGrandma\n\n> Can't wait! See https://example.com/directions",
            attachments = listOf(Attachment("2", "Recipe card.pdf", "application/pdf", 84_000, false, "/api/parts/x/Recipe%20card.pdf")),
        )
        show { MessageScreen(message, null, "https://mail.603norton.org", emptyList(), false, MessageActions()) }
        compose.onNodeWithText("Recipe card.pdf").assertExists()
        save("message")
    }

    @Test
    fun `writing a reply`() {
        val seed = Draft(
            accountId = "a1",
            to = listOf(Address("jo.norton@example.com", "Grandma Jo")),
            subject = "Re: Thanksgiving plans",
            text = "We'll be there by noon!\n\n-- \nMaddie\n\nOn Wed, 11 Nov 2026 at 14:20, Grandma Jo wrote:\n> Are you and Sam still coming up on Wednesday?",
            inReplyTo = "m40",
        )
        show(ThemeMode.DARK) {
            ComposeScreen(ComposeRequest(seed), listOf(account("a1", "maddie@603norton.org", "603norton.org")), { emptyList() }, { _, _ -> }, {}, { null }, {}, {})
        }
        compose.onNodeWithText("Re: Thanksgiving plans").assertExists()
        save("compose-dark")
    }

    @Test
    fun settings() {
        val c = RuntimeEnvironment.getApplication().container
        runBlocking { c.prefs.signIn("https://mail.603norton.org", "token", User("u1", "maddie", "Maddie Norton", role = Role.USER, createdAt = 0), "Norton mail") }
        show { SettingsScreen(onBack = {}) }
        compose.onNodeWithText("Tell me about new mail").assertExists()
        save("settings")
    }

    private fun account(id: String, email: String, label: String) = MailAccount(
        id, email, "Maddie Norton", label, Provider.PURELYMAIL, email, Endpoint("imap.purelymail.com", 993), Endpoint("smtp.purelymail.com", 465),
        saveSent = true, managed = true, editable = false,
    )
}
