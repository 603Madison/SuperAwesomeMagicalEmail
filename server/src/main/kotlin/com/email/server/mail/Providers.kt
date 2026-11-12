package com.email.server.mail

import com.email.api.Endpoint
import com.email.api.Provider
import com.email.api.ProviderPreset
import com.email.api.Security

/**
 * Mail services whose settings are known, so an account is just an address and a password.
 * PurelyMail first: it's what this server was made for. Microsoft's (Outlook, Hotmail) aren't
 * here: they no longer let mail apps sign in with a password.
 */
object Providers {
    val presets: List<ProviderPreset> = listOf(
        ProviderPreset(
            Provider.PURELYMAIL, "PurelyMail", "Mailboxes you've made at purelymail.com, on your own domains",
            Endpoint("imap.purelymail.com", 993, Security.SSL),
            Endpoint("smtp.purelymail.com", 465, Security.SSL),
            // PurelyMail keeps no copy of what's sent through SMTP, so the server puts one in Sent.
            saveSent = true,
            passwordHelp = "The mailbox's password (or an app password made for it at purelymail.com).",
        ),
        ProviderPreset(
            Provider.GMAIL, "Gmail", "Google accounts, including Google Workspace",
            Endpoint("imap.gmail.com", 993, Security.SSL),
            Endpoint("smtp.gmail.com", 465, Security.SSL),
            // Gmail files what it sends in Sent Mail by itself.
            saveSent = false,
            passwordHelp = "An app password (Google Account › Security › 2-Step Verification › App passwords), not your Google password.",
        ),
        ProviderPreset(
            Provider.ICLOUD, "iCloud Mail", "@icloud.com, @me.com and @mac.com addresses",
            Endpoint("imap.mail.me.com", 993, Security.SSL),
            Endpoint("smtp.mail.me.com", 587, Security.STARTTLS),
            saveSent = true,
            passwordHelp = "An app-specific password (account.apple.com › Sign-In and Security), not your Apple Account password.",
        ),
        ProviderPreset(
            Provider.FASTMAIL, "Fastmail", "Fastmail accounts",
            Endpoint("imap.fastmail.com", 993, Security.SSL),
            Endpoint("smtp.fastmail.com", 465, Security.SSL),
            saveSent = true,
            passwordHelp = "An app password (Fastmail › Settings › Privacy & Security › App passwords).",
        ),
        ProviderPreset(
            Provider.YAHOO, "Yahoo Mail", "@yahoo.com and Yahoo-run addresses",
            Endpoint("imap.mail.yahoo.com", 993, Security.SSL),
            Endpoint("smtp.mail.yahoo.com", 465, Security.SSL),
            saveSent = false,
            passwordHelp = "An app password (Yahoo › Account security › Generate app password).",
        ),
        ProviderPreset(
            Provider.OTHER, "Another service", "Any mail service with IMAP and SMTP",
            Endpoint("", 993, Security.SSL),
            Endpoint("", 465, Security.SSL),
            saveSent = true,
        ),
    )

    fun of(provider: Provider): ProviderPreset = presets.first { it.provider == provider }
}
