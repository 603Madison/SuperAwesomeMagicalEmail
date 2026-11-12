//! The window: signing in, then the mail (folders, the list, and the message open beside it).
//! New mail is checked every minute while the app runs, and announced by notifications (with
//! Archive and Mark read) when the window isn't in front, through the desktop's notification
//! service: SuperAwesomeMagicalLinux's shell, GNOME's or KDE's.

mod compose;
mod login;
mod mail;
mod prefs;
pub mod theme;
pub mod widgets;

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, gio, glib};

use crate::api::{Api, Draft, is_unauthorized};
use crate::config::Config;
use crate::rt::bg;

/// The app's one of everything.
pub struct Ctx {
    pub app: adw::Application,
    pub window: adw::ApplicationWindow,
    pub toasts: adw::ToastOverlay,
    pub config: RefCell<Config>,
    api: RefCell<Option<Api>>,
    /// The mail's view (once signed in).
    mail: RefCell<Option<Rc<mail::MailUi>>>,
    /// Signing in through the browser: (server, secret, state) until it hands back a code.
    pending_browser_sign_in: RefCell<Option<(String, String, String)>>,
    /// Kept while the app should go on without a window.
    hold: RefCell<Option<gio::ApplicationHoldGuard>>,
    checking: Cell<bool>,
}

thread_local! {
    static CTX: RefCell<Option<Rc<Ctx>>> = const { RefCell::new(None) };
    /// Started with --background: no window until asked for one.
    static BACKGROUND_START: Cell<bool> = const { Cell::new(false) };
}

/// The app's context (on the main thread, once the window exists).
pub fn ctx() -> Rc<Ctx> {
    CTX.with(|c| c.borrow().clone()).expect("the app's context")
}

/// The app's context, if there is one (none before the window, or as the app goes).
pub fn try_ctx() -> Option<Rc<Ctx>> {
    CTX.try_with(|c| c.try_borrow().ok().and_then(|c| c.clone())).ok().flatten()
}

pub fn start_in_background() {
    BACKGROUND_START.with(|b| b.set(true));
}

impl Ctx {
    pub fn api(&self) -> Option<Api> {
        self.api.borrow().clone()
    }

    pub fn toast(&self, text: &str) {
        let toast = adw::Toast::new(text);
        toast.set_timeout(4);
        self.toasts.add_toast(toast);
    }

    /// Something went wrong: a toast, or back to signing in if the sign-in is gone.
    pub fn failed(&self, error: &anyhow::Error) {
        if is_unauthorized(error) {
            self.toast("Your sign-in ended: sign in again");
            self.sign_out_here();
        } else {
            self.toast(&error.to_string());
        }
    }

    pub fn mail(&self) -> Option<Rc<mail::MailUi>> {
        self.mail.borrow().clone()
    }

    /// Signed in: keeps it, and shows the mail.
    pub fn signed_in(self: &Rc<Self>, server: &str, token: String, user: crate::api::User) {
        {
            let mut config = self.config.borrow_mut();
            config.server = Some(server.to_string());
            config.token = Some(token.clone());
            config.user = Some(user);
            config.new_mail_cursor = None;
            config.save();
        }
        self.api.replace(Some(Api::new(server, Some(token))));
        self.show_mail();
    }

    /// Signs out on the server too, then here.
    pub fn sign_out(self: &Rc<Self>) {
        if let Some(api) = self.api() {
            glib::spawn_future_local(async move {
                let _ = bg(async move { api.logout().await }).await;
            });
        }
        self.sign_out_here();
    }

    fn sign_out_here(&self) {
        self.api.replace(None);
        self.mail.replace(None);
        {
            let mut config = self.config.borrow_mut();
            config.token = None;
            config.user = None;
            config.new_mail_cursor = None;
            config.save();
        }
        self.toasts.set_child(Some(&login::view()));
    }

    fn show_mail(self: &Rc<Self>) {
        let ui = mail::MailUi::new();
        self.toasts.set_child(Some(&ui.root));
        self.mail.replace(Some(ui.clone()));
        ui.start();
        self.check_mail();
    }

    pub fn device_name() -> String {
        format!("Linux desktop ({})", glib::host_name())
    }

    /// Writes to [to] (from a mail link): from the account being looked at, with its signature.
    pub fn write_new(self: &Rc<Self>, to: Vec<crate::api::Address>, subject: String, body: String) {
        let Some(ui) = self.mail() else {
            self.present();
            self.toast("Sign in first, then open the link again");
            return;
        };
        let accounts = ui.accounts();
        let Some(account) = ui.current_account().or_else(|| accounts.first().map(|a| a.id.clone())) else {
            self.present();
            self.toast("There's no mail account to write from yet");
            return;
        };
        let signature = accounts
            .iter()
            .find(|a| a.id == account)
            .map(|a| a.signature.trim().to_string())
            .unwrap_or_default();
        let text = match (body.is_empty(), signature.is_empty()) {
            (_, true) => body,
            (true, false) => format!("\n\n-- \n{signature}"),
            (false, false) => format!("{body}\n\n-- \n{signature}"),
        };
        self.write(Some(Draft {
            account_id: account,
            to,
            subject,
            text,
            ..Default::default()
        }));
    }

    /// Writes a message (in a window of its own).
    pub fn write(self: &Rc<Self>, draft: Option<Draft>) {
        let Some(api) = self.api() else {
            self.present();
            self.toast("Sign in first");
            return;
        };
        let Some(ui) = self.mail() else { return };
        let accounts = ui.accounts();
        if accounts.is_empty() {
            self.present();
            self.toast("There's no mail account to write from yet");
            return;
        }
        let draft = draft.unwrap_or_else(|| {
            let account = ui.current_account().unwrap_or_else(|| accounts[0].id.clone());
            let signature = accounts
                .iter()
                .find(|a| a.id == account)
                .map(|a| a.signature.trim().to_string())
                .unwrap_or_default();
            Draft {
                account_id: account,
                text: if signature.is_empty() {
                    String::new()
                } else {
                    format!("\n\n-- \n{signature}")
                },
                ..Default::default()
            }
        });
        compose::open(api, accounts, draft);
    }

    pub fn present(&self) {
        self.window.present();
    }

    /// Asks the server what's new: refreshes what's shown, and announces new mail when the window
    /// isn't in front (or is closed).
    pub fn check_mail(self: &Rc<Self>) {
        let Some(api) = self.api() else { return };
        if self.checking.replace(true) {
            return;
        }
        let cursor = self.config.borrow().new_mail_cursor.clone();
        let ctx = self.clone();
        glib::spawn_future_local(async move {
            let result = bg(async move { api.new_mail(cursor.as_deref()).await }).await;
            ctx.checking.set(false);
            let news = match result {
                Ok(news) => news,
                Err(e) => {
                    if is_unauthorized(&e) {
                        ctx.failed(&e);
                    }
                    return;
                }
            };
            let first = {
                let mut config = ctx.config.borrow_mut();
                let first = config.new_mail_cursor.is_none();
                config.new_mail_cursor = Some(news.cursor.clone());
                config.save();
                first
            };
            if let Some(ui) = ctx.mail() {
                ui.set_unread(&news.unread);
                if !news.messages.is_empty() {
                    ui.new_mail(&news.messages);
                }
            }
            let looking = ctx.window.is_visible() && ctx.window.is_active();
            if !first && !looking && ctx.config.borrow().notify {
                for message in news.messages.iter().take(5) {
                    notify(&ctx.app, message);
                }
            }
        });
    }

    /// Keeps the app running without a window (and starts it so after signing in to the desktop).
    pub fn set_background(&self, on: bool) {
        if on {
            if self.hold.borrow().is_none() {
                self.hold.replace(Some(self.app.hold()));
            }
        } else {
            self.hold.replace(None);
        }
        let verb = if on { "enable" } else { "disable" };
        // The timer is installed with the package; run from a build, there's none, which is fine.
        let _ = gio::Subprocess::newv(
            &[
                std::ffi::OsStr::new("systemctl"),
                std::ffi::OsStr::new("--user"),
                std::ffi::OsStr::new(verb),
                std::ffi::OsStr::new("--now"),
                std::ffi::OsStr::new("superawesomemagicalemail-background.timer"),
            ],
            gio::SubprocessFlags::STDOUT_SILENCE | gio::SubprocessFlags::STDERR_SILENCE,
        );
    }
}

/// A new message, as a notification: open it, archive it, or mark it read.
fn notify(app: &adw::Application, message: &crate::api::MessageSummary) {
    let from = message.from.as_ref().map(|a| a.shown()).unwrap_or_else(|| "New mail".into());
    let notification = gio::Notification::new(&from);
    let body = [message.subject.as_str(), message.preview.as_str()]
        .iter()
        .filter(|s| !s.is_empty())
        .copied()
        .collect::<Vec<_>>()
        .join("\n");
    notification.set_body(Some(&body));
    notification.set_icon(&gio::ThemedIcon::new(crate::APP_ID));
    notification.set_category(Some("email.arrived"));
    let id = message.id.to_variant();
    notification.set_default_action_and_target_value("app.open-message", Some(&id));
    notification.add_button_with_target_value("Archive", "app.archive-message", Some(&id));
    notification.add_button_with_target_value("Mark read", "app.read-message", Some(&id));
    app.send_notification(Some(&message.id), &notification);
}

/// The app's own icons (an inbox and an archive box, which the usual icon themes lack: installed
/// with the app, and kept in the cache folder for runs from a build), and the app's icon when it
/// isn't installed (or the icon theme's cache is old).
fn register_icons() {
    let Some(display) = gdk::Display::default() else { return };
    let theme = gtk::IconTheme::for_display(&display);
    let dir = glib::user_cache_dir().join("SuperAwesomeMagicalEmail").join("icons");
    // As a theme folder (not loose files), so they're drawn in the text's colour.
    let actions = dir.join("hicolor").join("scalable").join("actions");
    let mut files: Vec<(std::path::PathBuf, &[u8])> = vec![
        (
            actions.join("same-inbox-symbolic.svg"),
            include_bytes!("../../data/icons/hicolor/scalable/actions/same-inbox-symbolic.svg"),
        ),
        (
            actions.join("same-archive-symbolic.svg"),
            include_bytes!("../../data/icons/hicolor/scalable/actions/same-archive-symbolic.svg"),
        ),
    ];
    if !theme.has_icon(crate::APP_ID) {
        files.push((
            dir.join(format!("{}.png", crate::APP_ID)),
            include_bytes!("../../data/icons/hicolor/256x256/apps/io.github._603madison.SuperAwesomeMagicalEmail.png"),
        ));
    }
    if std::fs::create_dir_all(&actions).is_err() {
        return;
    }
    for (path, bytes) in files {
        if std::fs::read(&path).ok().as_deref() != Some(bytes) {
            let _ = std::fs::write(&path, bytes);
        }
    }
    theme.add_search_path(&dir);
}

/// The window (made once; opening the app again brings it up).
pub fn activate(app: &adw::Application) {
    if let Some(ctx) = try_ctx() {
        ctx.present();
        return;
    }
    register_icons();
    let config = Config::load();
    theme::apply(&config.palette, &config.style, config.bold_text);
    let window = adw::ApplicationWindow::builder()
        .application(app)
        .title("SuperAwesomeMagicalEmail")
        .icon_name(crate::APP_ID)
        .default_width(config.width)
        .default_height(config.height)
        .width_request(360)
        .height_request(480)
        .build();
    if config.maximized {
        window.maximize();
    }
    let toasts = adw::ToastOverlay::new();
    window.set_content(Some(&toasts));
    let background = config.background;
    let context = Rc::new(Ctx {
        app: app.clone(),
        window: window.clone(),
        toasts,
        config: RefCell::new(config),
        api: RefCell::new(None),
        mail: RefCell::new(None),
        pending_browser_sign_in: RefCell::new(None),
        hold: RefCell::new(None),
        checking: Cell::new(false),
    });
    CTX.with(|c| c.replace(Some(context.clone())));
    if background || BACKGROUND_START.with(|b| b.get()) {
        context.hold.replace(Some(app.hold()));
    }

    let (server, token) = {
        let config = context.config.borrow();
        (config.server.clone(), config.token.clone())
    };
    match (server, token) {
        (Some(server), Some(token)) => {
            context.api.replace(Some(Api::new(&server, Some(token))));
            context.show_mail();
        }
        _ => context.toasts.set_child(Some(&login::view())),
    }

    // New mail, every minute (and when the window comes back to the front).
    glib::timeout_add_seconds_local(60, || {
        if let Some(ctx) = try_ctx() {
            ctx.check_mail();
        }
        glib::ControlFlow::Continue
    });
    window.connect_is_active_notify(|window| {
        if window.is_active()
            && let Some(ctx) = try_ctx()
        {
            ctx.check_mail();
        }
    });

    window.connect_close_request(move |window| {
        let ctx = ctx();
        {
            let mut config = ctx.config.borrow_mut();
            config.maximized = window.is_maximized();
            if !window.is_maximized() {
                let (w, h) = window.default_size();
                config.width = w;
                config.height = h;
            }
            config.save();
        }
        // Telling about new mail with the window closed: it hides rather than goes.
        if ctx.config.borrow().background {
            window.set_visible(false);
            return glib::Propagation::Stop;
        }
        glib::Propagation::Proceed
    });
    install_actions(app);
    if !BACKGROUND_START.with(|b| b.get()) {
        window.present();
    }
}

fn install_actions(app: &adw::Application) {
    let action = |name: &str, accels: &[&str], run: fn(&Rc<Ctx>)| {
        let a = gio::SimpleAction::new(name, None);
        a.connect_activate(move |_, _| run(&ctx()));
        app.add_action(&a);
        app.set_accels_for_action(&format!("app.{name}"), accels);
    };
    action("write", &["<Primary>n"], |c| c.write(None));
    action("refresh", &["F5", "<Primary>r"], |c| {
        if let Some(ui) = c.mail() {
            ui.refresh();
        }
        c.check_mail();
    });
    action("search", &["<Primary>f"], |c| {
        if let Some(ui) = c.mail() {
            ui.focus_search();
        }
    });
    action("quit", &["<Primary>q"], |c| {
        c.hold.replace(None);
        c.window.destroy();
        c.app.quit();
    });
    action("preferences", &["<Primary>comma"], prefs::preferences);
    action("about", &[], prefs::about);
    action("shortcuts", &["<Primary>question"], prefs::shortcuts);
    action("sign-out", &[], |c| c.sign_out());

    // From notifications: open a message, archive it, or mark it read.
    let with_id = |name: &str, run: fn(&Rc<Ctx>, String)| {
        let a = gio::SimpleAction::new(name, Some(glib::VariantTy::STRING));
        a.connect_activate(move |_, parameter| {
            if let Some(id) = parameter.and_then(|p| p.get::<String>()) {
                run(&ctx(), id);
            }
        });
        app.add_action(&a);
    };
    with_id("open-message", |c, id| {
        c.present();
        if let Some(ui) = c.mail() {
            ui.open_message_by_id(&id);
        }
    });
    with_id("archive-message", |c, id| act_quietly(c, id, "ARCHIVE"));
    with_id("read-message", |c, id| act_quietly(c, id, "READ"));
}

fn act_quietly(ctx: &Rc<Ctx>, id: String, action: &'static str) {
    let Some(api) = ctx.api() else { return };
    let ctx = ctx.clone();
    glib::spawn_future_local(async move {
        let ids = vec![id.clone()];
        let result = bg(async move { api.act(&ids, action, None).await }).await;
        ctx.app.withdraw_notification(&id);
        match result {
            Ok(()) => {
                if let Some(ui) = ctx.mail() {
                    ui.refresh();
                }
            }
            Err(e) => ctx.failed(&e),
        }
    });
}

/// Links the app is opened with: `samemail://signed-in?code=…&state=…` (the browser handing back
/// a sign-in), or `mailto:` (write to someone).
pub fn open_uris(app: &adw::Application, files: &[gio::File]) {
    activate(app);
    let ctx = ctx();
    for file in files {
        let uri = file.uri().to_string();
        if uri.starts_with("mailto:") {
            let (to, subject, body) = crate::api::mailto(&uri);
            ctx.present();
            write_when_ready(ctx.clone(), to, subject, body, 0);
        } else if let Ok(url) = url::Url::parse(&uri)
            && url.scheme() == "samemail"
            && url.host_str() == Some("signed-in")
        {
            let query: std::collections::HashMap<_, _> = url.query_pairs().into_owned().collect();
            login::finish_browser_sign_in(
                query.get("code").cloned().unwrap_or_default(),
                query.get("state").cloned().unwrap_or_default(),
            );
        }
    }
}

/// A mail link opened as the app starts: written once the accounts are known (they load first).
fn write_when_ready(ctx: Rc<Ctx>, to: Vec<crate::api::Address>, subject: String, body: String, tries: u32) {
    let ready = ctx.mail().is_some_and(|ui| !ui.accounts().is_empty());
    if ready || tries >= 40 || ctx.api().is_none() {
        ctx.write_new(to, subject, body);
        return;
    }
    glib::timeout_add_local_once(std::time::Duration::from_millis(250), move || {
        write_when_ready(ctx, to, subject, body, tries + 1)
    });
}
