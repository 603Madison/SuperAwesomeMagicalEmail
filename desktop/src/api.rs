//! The server's API, as the web app and the phone app use it (see shared/src/main/kotlin and
//! web/src/lib/types.ts), signed in with an app token (`Authorization: Bearer`).

use std::collections::HashMap;
use std::time::Duration;

use anyhow::{Context, Result, anyhow, bail};
use reqwest::{Method, StatusCode};
use serde::de::DeserializeOwned;
use serde::{Deserialize, Serialize};
use serde_json::json;

// ---- What the server sends ----

#[derive(Debug, Clone, Default, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct User {
    pub id: String,
    pub username: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub role: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AuthConfig {
    pub server_name: String,
    #[serde(default)]
    pub local_login: bool,
    #[serde(default)]
    pub oidc: Option<OidcButton>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct OidcButton {
    pub label: String,
}

#[derive(Debug, Clone, Deserialize)]
pub struct SignedIn {
    pub user: User,
    pub token: Option<String>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct Address {
    pub email: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<String>,
}

impl Address {
    pub fn new(email: &str) -> Address {
        Address {
            email: email.to_string(),
            name: None,
        }
    }

    /// The name if there is one, else the address.
    pub fn shown(&self) -> String {
        match self.name.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
            Some(name) => name.to_string(),
            None => self.email.clone(),
        }
    }

    /// "Name <a@b>", or "a@b".
    pub fn full(&self) -> String {
        match self.name.as_deref().map(str::trim).filter(|n| !n.is_empty()) {
            Some(name) => format!("{name} <{}>", self.email),
            None => self.email.clone(),
        }
    }

    /// "Name <a@b>" or "a@b", as typed; None if it isn't an address.
    pub fn parse(text: &str) -> Option<Address> {
        let t = text.trim().trim_end_matches([',', ';']).trim();
        if let (Some(open), true) = (t.rfind('<'), t.ends_with('>')) {
            let email = t[open + 1..t.len() - 1].trim();
            let name = t[..open].trim().trim_matches('"').trim();
            return valid(email).then(|| Address {
                email: email.to_string(),
                name: (!name.is_empty()).then(|| name.to_string()),
            });
        }
        valid(t).then(|| Address::new(t))
    }

    /// Several addresses, separated by commas or semicolons; the ones that aren't addresses separately.
    pub fn parse_list(text: &str) -> (Vec<Address>, Vec<String>) {
        let mut good = vec![];
        let mut bad = vec![];
        for part in text.split([',', ';', '\n']).map(str::trim).filter(|p| !p.is_empty()) {
            match Address::parse(part) {
                Some(a) => good.push(a),
                None => bad.push(part.to_string()),
            }
        }
        (good, bad)
    }
}

fn valid(email: &str) -> bool {
    let Some((local, domain)) = email.split_once('@') else {
        return false;
    };
    !local.is_empty() && domain.contains('.') && !domain.starts_with('.') && !domain.ends_with('.') && !email.contains(char::is_whitespace)
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MailAccount {
    pub id: String,
    pub email: String,
    #[serde(default)]
    pub name: String,
    #[serde(default)]
    pub label: String,
    #[serde(default)]
    pub signature: String,
    #[serde(default)]
    pub problem: Option<String>,
}

impl MailAccount {
    pub fn shown(&self) -> &str {
        if self.label.is_empty() { &self.email } else { &self.label }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct Folder {
    pub path: String,
    pub name: String,
    pub role: String,
    #[serde(default)]
    pub unread: i64,
    #[serde(default)]
    pub total: i64,
    #[serde(default)]
    pub depth: i32,
    #[serde(default = "yes")]
    pub selectable: bool,
}

fn yes() -> bool {
    true
}

impl Folder {
    /// What the folder's called, whatever the server names it.
    pub fn shown(&self) -> String {
        match self.role.as_str() {
            "INBOX" => "Inbox".into(),
            "DRAFTS" => "Drafts".into(),
            "SENT" => "Sent".into(),
            "ARCHIVE" => "Archive".into(),
            "JUNK" => "Junk".into(),
            "TRASH" => "Trash".into(),
            "ALL" => "All mail".into(),
            "FLAGGED" => "Starred".into(),
            _ => self.name.clone(),
        }
    }

    pub fn icon(&self) -> &'static str {
        match self.role.as_str() {
            "INBOX" => "same-inbox-symbolic",
            "DRAFTS" => "document-edit-symbolic",
            "SENT" => "mail-send-symbolic",
            "ARCHIVE" => "same-archive-symbolic",
            "JUNK" => "mail-mark-junk-symbolic",
            "TRASH" => "user-trash-symbolic",
            "FLAGGED" => "starred-symbolic",
            "ALL" => "mail-read-symbolic",
            _ => "folder-symbolic",
        }
    }
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageSummary {
    pub id: String,
    pub account_id: String,
    pub folder: String,
    #[serde(default)]
    pub from: Option<Address>,
    #[serde(default)]
    pub to: Vec<Address>,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub preview: String,
    #[serde(default)]
    pub date: i64,
    #[serde(default)]
    pub unread: bool,
    #[serde(default)]
    pub flagged: bool,
    #[serde(default)]
    pub answered: bool,
    #[serde(default)]
    pub draft: bool,
    #[serde(default)]
    pub has_attachments: bool,
}

#[derive(Debug, Clone, Deserialize)]
pub struct MessagePage {
    pub messages: Vec<MessageSummary>,
    #[serde(default)]
    pub next: Option<String>,
    #[serde(default)]
    pub problems: HashMap<String, String>,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Attachment {
    pub filename: String,
    #[serde(default)]
    pub content_type: String,
    #[serde(default)]
    pub size: i64,
    #[serde(default)]
    pub inline: bool,
    pub url: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    pub summary: MessageSummary,
    #[serde(default)]
    pub cc: Vec<Address>,
    #[serde(default)]
    pub bcc: Vec<Address>,
    #[serde(default)]
    pub reply_to: Vec<Address>,
    /// Where to unsubscribe from a mailing list (a web or mailto: link).
    #[serde(default)]
    pub unsubscribe: Option<String>,
    #[serde(default)]
    pub html: Option<String>,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub remote_images: bool,
    #[serde(default)]
    pub images_shown: bool,
    #[serde(default)]
    pub attachments: Vec<Attachment>,
    #[serde(default)]
    pub raw_url: String,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AttachmentRef {
    pub message_id: String,
    pub part: String,
    #[serde(default)]
    pub filename: String,
    #[serde(default)]
    pub size: i64,
}

/// A message being written, to send or keep as a draft.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Draft {
    pub account_id: String,
    #[serde(default)]
    pub to: Vec<Address>,
    #[serde(default)]
    pub cc: Vec<Address>,
    #[serde(default)]
    pub bcc: Vec<Address>,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub uploads: Vec<String>,
    #[serde(default)]
    pub attachments: Vec<AttachmentRef>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub in_reply_to: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub forward_of: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub draft_id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Upload {
    pub id: String,
    pub filename: String,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SendResult {
    #[serde(default)]
    pub note: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct DraftSaved {
    pub id: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct Contact {
    pub email: String,
    #[serde(default)]
    pub name: Option<String>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct NewMail {
    pub cursor: String,
    #[serde(default)]
    pub messages: Vec<MessageSummary>,
    #[serde(default)]
    pub unread: HashMap<String, i64>,
}

#[derive(Debug, Clone, Deserialize)]
pub struct ActionResult {
    #[serde(default)]
    pub failed: HashMap<String, String>,
}

// ---- Asking ----

/// The server, signed in or not. Cheap to clone (the HTTP client is shared).
#[derive(Clone)]
pub struct Api {
    base: String,
    token: Option<String>,
    http: reqwest::Client,
}

/// The server said the sign-in is gone.
#[derive(Debug)]
pub struct Unauthorized(pub String);

impl std::fmt::Display for Unauthorized {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for Unauthorized {}

pub fn is_unauthorized(error: &anyhow::Error) -> bool {
    error.downcast_ref::<Unauthorized>().is_some()
}

pub fn enc(value: &str) -> String {
    url::form_urlencoded::byte_serialize(value.as_bytes())
        .collect::<String>()
        .replace('+', "%20")
}

fn client() -> reqwest::Client {
    reqwest::Client::builder()
        .user_agent(concat!("SuperAwesomeMagicalEmail-Desktop/", env!("CARGO_PKG_VERSION")))
        .connect_timeout(Duration::from_secs(10))
        .timeout(Duration::from_secs(120))
        .build()
        .expect("the HTTP client")
}

#[derive(Deserialize)]
struct ErrorBody {
    error: Option<String>,
}

fn describe(error: &reqwest::Error) -> String {
    if error.is_connect() {
        "Can't reach the server".into()
    } else if error.is_timeout() {
        "The server didn't answer in time".into()
    } else {
        error.to_string()
    }
}

impl Api {
    pub fn new(base: &str, token: Option<String>) -> Api {
        Api {
            base: base.trim_end_matches('/').to_string(),
            token,
            http: client(),
        }
    }

    pub fn base(&self) -> &str {
        &self.base
    }

    /// The server's address for [path] (which the server gives as `/api/…`).
    pub fn url(&self, path: &str) -> String {
        if path.starts_with("http://") || path.starts_with("https://") {
            path.to_string()
        } else {
            format!("{}{}", self.base, path)
        }
    }

    async fn check(&self, response: reqwest::Response) -> Result<reqwest::Response> {
        let status = response.status();
        if status.is_success() {
            return Ok(response);
        }
        let text = response.text().await.unwrap_or_default();
        let said = serde_json::from_str::<ErrorBody>(&text).ok().and_then(|b| b.error);
        if status == StatusCode::UNAUTHORIZED {
            bail!(Unauthorized(said.unwrap_or_else(|| "Sign in again".into())));
        }
        bail!(said.unwrap_or_else(|| format!("The server answered {status}")))
    }

    async fn send<T: DeserializeOwned>(&self, method: Method, path: &str, body: Option<serde_json::Value>) -> Result<T> {
        let mut request = self.http.request(method, self.url(path)).header("Accept", "application/json");
        if let Some(token) = &self.token {
            request = request.bearer_auth(token);
        }
        if let Some(body) = body {
            request = request.json(&body);
        }
        let response = request.send().await.map_err(|e| anyhow!(describe(&e)))?;
        let bytes = self.check(response).await?.bytes().await?;
        serde_json::from_slice(&bytes).with_context(|| format!("an unexpected answer from {path}"))
    }

    async fn get<T: DeserializeOwned>(&self, path: &str) -> Result<T> {
        self.send(Method::GET, path, None).await
    }

    async fn post<T: DeserializeOwned>(&self, path: &str, body: serde_json::Value) -> Result<T> {
        self.send(Method::POST, path, Some(body)).await
    }

    // Signing in.

    pub async fn auth_config(&self) -> Result<AuthConfig> {
        self.get("/api/auth/config").await
    }

    pub async fn login(&self, username: &str, password: &str, device: &str) -> Result<SignedIn> {
        self.post(
            "/api/auth/login",
            json!({ "username": username, "password": password, "app": true, "deviceName": device }),
        )
        .await
    }

    /// Finishes signing in through the browser: the code it handed back, and this app's secret.
    pub async fn app_token(&self, code: &str, verifier: &str, device: &str) -> Result<SignedIn> {
        self.post(
            "/api/auth/app/token",
            json!({ "code": code, "verifier": verifier, "deviceName": device }),
        )
        .await
    }

    pub async fn logout(&self) -> Result<serde_json::Value> {
        self.post("/api/auth/logout", json!({})).await
    }

    // Mail.

    pub async fn accounts(&self) -> Result<Vec<MailAccount>> {
        self.get("/api/accounts").await
    }

    pub async fn folders(&self, account: &str) -> Result<Vec<Folder>> {
        self.get(&format!("/api/accounts/{}/folders", enc(account))).await
    }

    pub async fn messages(&self, account: &str, folder: &str, cursor: Option<&str>, query: Option<&str>) -> Result<MessagePage> {
        let mut path = format!("/api/messages?account={}&folder={}", enc(account), enc(folder));
        if let Some(cursor) = cursor {
            path.push_str(&format!("&cursor={}", enc(cursor)));
        }
        if let Some(query) = query.filter(|q| !q.trim().is_empty()) {
            path.push_str(&format!("&q={}", enc(query.trim())));
        }
        self.get(&path).await
    }

    pub async fn message(&self, id: &str, images: bool) -> Result<Message> {
        self.get(&format!("/api/messages/{}?images={}&read=1", enc(id), u8::from(images)))
            .await
    }

    /// READ, UNREAD, FLAG, UNFLAG, ARCHIVE, TRASH, DELETE, JUNK, NOT_JUNK or MOVE (to [folder]).
    pub async fn act(&self, ids: &[String], action: &str, folder: Option<&str>) -> Result<()> {
        let result: ActionResult = self
            .post("/api/messages/actions", json!({ "ids": ids, "action": action, "folder": folder }))
            .await?;
        match result.failed.into_values().next() {
            Some(problem) => bail!(problem),
            None => Ok(()),
        }
    }

    /// REPLY, REPLY_ALL, FORWARD or DRAFT: the message to write, as the server starts it.
    pub async fn compose(&self, id: &str, mode: &str, zone: &str) -> Result<Draft> {
        self.get(&format!("/api/messages/{}/compose?mode={mode}&tz={}", enc(id), enc(zone)))
            .await
    }

    pub async fn send_mail(&self, draft: &Draft) -> Result<SendResult> {
        self.post("/api/send", serde_json::to_value(draft)?).await
    }

    pub async fn save_draft(&self, draft: &Draft) -> Result<DraftSaved> {
        self.post("/api/drafts", serde_json::to_value(draft)?).await
    }

    pub async fn contacts(&self, query: &str) -> Result<Vec<Contact>> {
        self.get(&format!("/api/contacts?q={}", enc(query))).await
    }

    pub async fn new_mail(&self, cursor: Option<&str>) -> Result<NewMail> {
        match cursor {
            Some(c) => self.get(&format!("/api/mail/new?cursor={}", enc(c))).await,
            None => self.get("/api/mail/new").await,
        }
    }

    /// Uploads a file to attach.
    pub async fn upload(&self, filename: &str, bytes: Vec<u8>) -> Result<Upload> {
        let part = reqwest::multipart::Part::bytes(bytes).file_name(filename.to_string());
        let form = reqwest::multipart::Form::new().part("file", part);
        let mut request = self.http.post(self.url("/api/uploads")).multipart(form);
        if let Some(token) = &self.token {
            request = request.bearer_auth(token);
        }
        let response = request.send().await.map_err(|e| anyhow!(describe(&e)))?;
        let uploads: Vec<Upload> = self.check(response).await?.json().await?;
        uploads.into_iter().next().context("the server kept no file")
    }

    /// A signed link's content (an attachment), which needs no sign-in.
    pub async fn download(&self, path: &str) -> Result<Vec<u8>> {
        let response = self.http.get(self.url(path)).send().await.map_err(|e| anyhow!(describe(&e)))?;
        Ok(self.check(response).await?.bytes().await?.to_vec())
    }
}

/// The addresses to try for what was typed: the origin, https first.
pub fn candidates(typed: &str) -> Vec<String> {
    let text = typed.trim();
    if text.is_empty() {
        return vec![];
    }
    let tries = if text.contains("://") {
        vec![text.to_string()]
    } else {
        vec![format!("https://{text}"), format!("http://{text}")]
    };
    tries
        .into_iter()
        .filter_map(|t| url::Url::parse(&t).ok())
        .filter(|u| (u.scheme() == "https" || u.scheme() == "http") && u.host_str().is_some())
        .map(|u| u.origin().ascii_serialization())
        .collect()
}

/// Who, what about and what to say, from a mailto: link (RFC 6068).
pub fn mailto(link: &str) -> (Vec<Address>, String, String) {
    let rest = link.strip_prefix("mailto:").unwrap_or(link);
    let (to, query) = rest.split_once('?').unwrap_or((rest, ""));
    let decode = |s: &str| {
        url::form_urlencoded::parse(format!("x={}", s.replace('+', "%2B")).as_bytes())
            .next()
            .map(|(_, v)| v.to_string())
            .unwrap_or_default()
    };
    let mut people: Vec<Address> = decode(to).split(',').filter_map(Address::parse).collect();
    let mut subject = String::new();
    let mut body = String::new();
    for pair in query.split('&').filter(|p| !p.is_empty()) {
        let (key, value) = pair.split_once('=').unwrap_or((pair, ""));
        let value = decode(value);
        match key.to_ascii_lowercase().as_str() {
            "subject" => subject = value,
            "body" => body = value,
            "to" => people.extend(value.split(',').filter_map(Address::parse)),
            _ => {}
        }
    }
    (people, subject, body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn addresses_become_origins() {
        assert_eq!(
            candidates("mail.603norton.org"),
            vec!["https://mail.603norton.org", "http://mail.603norton.org"]
        );
        assert_eq!(
            candidates("https://mail.603norton.org/settings?x=1"),
            vec!["https://mail.603norton.org"]
        );
        assert_eq!(candidates("http://127.0.0.1:8095"), vec!["http://127.0.0.1:8095"]);
        assert!(candidates("  ").is_empty());
    }

    #[test]
    fn typed_addresses_are_understood() {
        assert_eq!(
            Address::parse("Grandma Jo <jo@example.com>"),
            Some(Address {
                email: "jo@example.com".into(),
                name: Some("Grandma Jo".into())
            })
        );
        assert_eq!(Address::parse("\"Grandma Jo\" <jo@example.com>,").unwrap().shown(), "Grandma Jo");
        assert_eq!(Address::parse(" jo@example.com ").unwrap().full(), "jo@example.com");
        assert_eq!(Address::parse("grandma"), None);
        let (good, bad) = Address::parse_list("a@example.com, Sam <sam@example.com>; nobody");
        assert_eq!(good.len(), 2);
        assert_eq!(bad, vec!["nobody"]);
    }

    #[test]
    fn mail_links_become_messages() {
        let (to, subject, body) = mailto("mailto:jo@example.com,sam@example.com?subject=Hello%20there&body=Line%201%0ALine%202");
        assert_eq!(
            to.iter().map(|a| a.email.as_str()).collect::<Vec<_>>(),
            vec!["jo@example.com", "sam@example.com"]
        );
        assert_eq!(subject, "Hello there");
        assert_eq!(body, "Line 1\nLine 2");
        assert!(mailto("mailto:").0.is_empty());
    }

    #[test]
    fn the_server_json_reads() {
        let page: MessagePage = serde_json::from_str(
            r#"{"messages":[{"id":"m1","accountId":"a","folder":"INBOX","from":{"email":"x@y.z","name":"X"},"to":[],"subject":"Hi",
               "preview":"p","date":1,"unread":true,"flagged":false,"answered":false,"draft":false,"hasAttachments":true,"size":3}],
               "next":"u5","problems":{}}"#,
        )
        .unwrap();
        assert_eq!(page.messages[0].from.as_ref().unwrap().shown(), "X");
        assert!(page.messages[0].has_attachments);
        assert_eq!(page.next.as_deref(), Some("u5"));
        let draft = Draft {
            account_id: "a".into(),
            in_reply_to: Some("m1".into()),
            ..Default::default()
        };
        let json = serde_json::to_value(&draft).unwrap();
        assert_eq!(json["accountId"], "a");
        assert_eq!(json["inReplyTo"], "m1");
        assert!(json.get("forwardOf").is_none());
    }
}
