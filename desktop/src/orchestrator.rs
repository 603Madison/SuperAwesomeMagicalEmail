//! Signing in through SuperAwesomeMagicalOrchestrator: where someone is signed in to its agent on
//! this desktop, the app asks it (over the session bus) for a short-lived ticket, hands that to
//! its own server, and is signed in as the same person, exactly as if they had signed in by hand.
//!
//! Any failure (no agent, nobody signed in there, the app isn't listed, the server says no) is the
//! usual sign-in, with no error shown: this is only ever a shortcut. The agent is optional: the
//! app needs nothing installed for this (the bus is GTK's own gio), and without it nothing changes.
//!
//! There's no "agree to this server" step here as on the phone: on a desktop the session bus is
//! the boundary (anything running as the person could read the app's token anyway).
//!
//! Only signing in goes through it. Email never tells the orchestrator what someone is doing here
//! (`PublishActivity` is never called): what a person is reading or writing is private, and
//! nothing about it leaves the app this way.

use anyhow::{Result, anyhow, bail};
use gtk::glib::prelude::ToVariant;
use gtk::{gio, glib};
use serde::Deserialize;

use crate::api::{Api, User};
use crate::rt::bg;

/// This app's name in the orchestrator's list of the family's apps, and in its tickets.
pub const APP: &str = "email";

const BUS_NAME: &str = "app.superawesomemagical.Orchestrator";
const PATH: &str = "/app/superawesomemagical/Orchestrator";
const INTERFACE: &str = "app.superawesomemagical.Orchestrator1";

/// The agent is started by the bus when first asked, and may ask its server: long enough for
/// that, short enough that the sign-in screen isn't left waiting on it.
const TIMEOUT_MS: i32 = 10_000;

pub const WITH_ORCHESTRATOR: &str = "Continue with SuperAwesomeMagicalOrchestrator";

/// What the agent's `Status()` says, as far as this app cares.
#[derive(Debug, Clone, Default, PartialEq, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Status {
    pub signed_in: bool,
    pub username: String,
    pub display_name: String,
}

impl Status {
    /// From the agent's JSON; anything else is "nobody signed in".
    pub fn parse(json: &str) -> Status {
        serde_json::from_str(json).unwrap_or_default()
    }

    /// The button's words on the sign-in screen, or none for no button.
    pub fn label(&self) -> Option<String> {
        if !self.signed_in {
            return None;
        }
        let name = if self.display_name.trim().is_empty() {
            self.username.trim()
        } else {
            self.display_name.trim()
        };
        Some(if name.is_empty() {
            WITH_ORCHESTRATOR.to_string()
        } else {
            format!("Continue as {name}")
        })
    }
}

/// Whether to ask the agent by itself when the app opens: only when nobody is signed in, the
/// person didn't sign out on purpose (they chose to be signed out: it stays that way until they
/// sign in again), and it hasn't been tried since the app started.
pub fn asks_at_start(signed_in: bool, signed_out_on_purpose: bool, tried_already: bool) -> bool {
    !signed_in && !signed_out_on_purpose && !tried_already
}

/// The server a ticket is for, as the app keeps servers: `https://mail.example.com`.
pub fn server_origin(url: &str) -> Option<String> {
    let parsed = url::Url::parse(url.trim()).ok()?;
    ((parsed.scheme() == "https" || parsed.scheme() == "http") && parsed.host_str().is_some())
        .then(|| parsed.origin().ascii_serialization())
}

/// Calls the agent on the session bus. Runs on the main loop without blocking it.
async fn call(method: &str, parameters: Option<glib::Variant>, reply: &glib::VariantTy) -> Result<glib::Variant> {
    let bus = gio::bus_get_future(gio::BusType::Session).await?;
    let answer = bus
        .call_future(
            Some(BUS_NAME),
            PATH,
            INTERFACE,
            method,
            parameters.as_ref(),
            Some(reply),
            gio::DBusCallFlags::NONE,
            TIMEOUT_MS,
        )
        .await?;
    Ok(answer)
}

/// Who is signed in to the orchestrator on this desktop (nobody, when there's no agent).
pub async fn status() -> Status {
    match call("Status", None, glib::VariantTy::new("(s)").expect("a type")).await {
        Ok(answer) => answer.get::<(String,)>().map(|(json,)| Status::parse(&json)).unwrap_or_default(),
        Err(_) => Status::default(),
    }
}

/// A ticket for this app's server: (the server's address, the ticket).
async fn ticket() -> Result<(String, String)> {
    let answer = call("SignInTo", Some((APP,).to_variant()), glib::VariantTy::new("(ss)").expect("a type")).await?;
    answer
        .get::<(String, String)>()
        .ok_or_else(|| anyhow!("The orchestrator's answer wasn't a ticket"))
}

/// A sign-in, as every other way of signing in gives it: the server, the app's token, and who.
pub struct SignedIn {
    pub server: String,
    pub token: String,
    pub user: User,
}

/// Asks the agent for a ticket and hands it to the server it names.
pub async fn sign_in(device: String) -> Result<SignedIn> {
    let (url, ticket) = ticket().await?;
    let Some(server) = server_origin(&url) else {
        bail!("The orchestrator gave no server address");
    };
    let api = Api::new(&server, None);
    let signed = bg(async move { api.orchestrator_sign_in(&ticket, &device).await }).await?;
    let token = signed.token.ok_or_else(|| anyhow!("The server sent no sign-in"))?;
    Ok(SignedIn {
        server,
        token,
        user: signed.user,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn asked_at_start_only_when_nobody_is_signed_in_and_nobody_chose_to_sign_out() {
        assert!(asks_at_start(false, false, false));
        assert!(!asks_at_start(true, false, false));
        assert!(!asks_at_start(false, true, false));
        assert!(!asks_at_start(false, false, true));
    }

    #[test]
    fn the_buttons_words() {
        let status = Status::parse(
            r#"{"signedIn":true,"server":"https://orchestrator.example.com","username":"maddie","displayName":"Maddie",
                "connected":true,"device":{"id":"d1","name":"Maddie's laptop"}}"#,
        );
        assert_eq!(status.label().as_deref(), Some("Continue as Maddie"));
        let unnamed = Status {
            display_name: " ".into(),
            ..status.clone()
        };
        assert_eq!(unnamed.label().as_deref(), Some("Continue as maddie"));
        let nameless = Status {
            signed_in: true,
            ..Status::default()
        };
        assert_eq!(nameless.label().as_deref(), Some(WITH_ORCHESTRATOR));
        // Nobody signed in, an agent that says something else, or none at all: no button.
        assert_eq!(Status::parse(r#"{"signedIn":false,"username":"maddie"}"#).label(), None);
        assert_eq!(Status::parse("not json").label(), None);
        assert_eq!(Status::parse("{}").label(), None);
        assert_eq!(Status::default().label(), None);
    }

    #[test]
    fn a_tickets_server_becomes_the_address_the_app_keeps() {
        assert_eq!(
            server_origin("https://mail.603norton.org/").as_deref(),
            Some("https://mail.603norton.org")
        );
        assert_eq!(
            server_origin(" http://192.168.1.20:8090 ").as_deref(),
            Some("http://192.168.1.20:8090")
        );
        assert_eq!(
            server_origin("https://mail.example.com:443/x?y").as_deref(),
            Some("https://mail.example.com")
        );
        assert_eq!(server_origin(""), None);
        assert_eq!(server_origin("mail.example.com"), None);
        assert_eq!(server_origin("samemail://signed-in"), None);
        assert_eq!(server_origin("file:///etc/passwd"), None);
    }

    /// The real thing, against a real agent: run with `SAME_ORCHESTRATOR_E2E=1` on a session bus
    /// where SuperAwesomeMagicalOrchestrator's agent is signed in and lists `email` (see
    /// desktop/README.md). Asks for a ticket, hands it in, and reads mail accounts with the token.
    #[test]
    fn signs_in_through_a_real_agent() {
        if std::env::var("SAME_ORCHESTRATOR_E2E").is_err() {
            return;
        }
        let (label, signed, accounts) = glib::MainContext::default().block_on(async {
            let label = status().await.label();
            let signed = sign_in("Linux desktop (test)".into()).await.expect("a sign-in through the agent");
            let api = Api::new(&signed.server, Some(signed.token.clone()));
            let accounts = bg(async move { api.accounts().await }).await.expect("the token works");
            (label, signed, accounts)
        });
        println!(
            "orchestrator e2e: \"{}\"; signed in to {} as {} ({}); token of {} characters; {} mail account(s)",
            label.unwrap_or_default(),
            signed.server,
            signed.user.username,
            signed.user.display_name,
            signed.token.len(),
            accounts.len()
        );
        if let Ok(expected) = std::env::var("SAME_ORCHESTRATOR_E2E_USER") {
            assert_eq!(signed.user.username, expected);
        }
    }
}
