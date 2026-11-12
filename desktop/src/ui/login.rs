//! Signing in: the server's address, then a password or the server's single sign-on (in the
//! browser, which hands the app a one-time code at `samemail://signed-in`, as the phone app does).

use std::rc::Rc;

use adw::prelude::*;
use base64::Engine;
use base64::engine::general_purpose::URL_SAFE_NO_PAD;
use gtk::glib;
use rand::RngExt;
use sha2::{Digest, Sha256};

use crate::api::{Api, AuthConfig, candidates};
use crate::rt::bg;
use crate::ui::{Ctx, ctx};

pub fn view() -> gtk::Widget {
    let toolbar = adw::ToolbarView::new();
    toolbar.add_css_class("page-bg");
    toolbar.set_top_bar_style(adw::ToolbarStyle::Flat);
    let header = adw::HeaderBar::builder().show_title(false).build();
    toolbar.add_top_bar(&header);

    // A card over the theme's glow, as the web app's sign-in.
    let card = gtk::Box::builder().orientation(gtk::Orientation::Vertical).spacing(14).build();
    card.add_css_class("login-card");
    let logo = gtk::Image::builder()
        .icon_name(crate::APP_ID)
        .pixel_size(72)
        .halign(gtk::Align::Center)
        .build();
    card.append(&logo);
    let title = gtk::Label::builder()
        .label("SuperAwesomeMagicalEmail")
        .wrap(true)
        .justify(gtk::Justification::Center)
        .build();
    title.add_css_class("login-title");
    card.append(&title);
    let sub = gtk::Label::builder()
        .label("Connect to your mail server to read your mail here.")
        .wrap(true)
        .justify(gtk::Justification::Center)
        .build();
    sub.add_css_class("login-sub");
    card.append(&sub);
    let form = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(14)
        .margin_top(6)
        .build();
    card.append(&form);
    let clamp = adw::Clamp::builder()
        .maximum_size(440)
        .child(&card)
        .valign(gtk::Align::Center)
        .margin_top(12)
        .margin_bottom(36)
        .margin_start(16)
        .margin_end(16)
        .build();
    let scroll = gtk::ScrolledWindow::builder()
        .hscrollbar_policy(gtk::PolicyType::Never)
        .child(&clamp)
        .vexpand(true)
        .build();
    toolbar.set_content(Some(&scroll));

    // The server's address.
    let address_group = adw::PreferencesGroup::new();
    let address = adw::EntryRow::builder().title("Server address").build();
    address.set_input_purpose(gtk::InputPurpose::Url);
    if let Some(server) = ctx().config.borrow().server.clone() {
        address.set_text(&server);
    }
    address_group.add(&address);
    form.append(&address_group);
    let error = gtk::Label::builder().wrap(true).xalign(0.5).visible(false).build();
    error.add_css_class("error");
    let next = gtk::Button::builder().label("Continue").halign(gtk::Align::Center).build();
    next.add_css_class("suggested-action");
    next.add_css_class("pill");
    let hint = gtk::Label::builder()
        .label("The address you open your mail at, like mail.603norton.org.")
        .wrap(true)
        .justify(gtk::Justification::Center)
        .build();
    hint.add_css_class("login-sub");
    hint.add_css_class("caption");

    // Then how to sign in, once the server says.
    let sign_in = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(12)
        .visible(false)
        .build();

    form.append(&next);
    form.append(&hint);
    form.append(&sign_in);
    form.append(&error);

    let connect = {
        let (address, next, error, sign_in, hint, address_group) = (
            address.clone(),
            next.clone(),
            error.clone(),
            sign_in.clone(),
            hint.clone(),
            address_group.clone(),
        );
        move || {
            let typed = address.text().to_string();
            let tries = candidates(&typed);
            if tries.is_empty() {
                show_error(&error, "That isn't a web address. Try something like mail.603norton.org.");
                return;
            }
            next.set_sensitive(false);
            next.set_label("Connecting…");
            error.set_visible(false);
            let (address, next, error, sign_in, hint, address_group) = (
                address.clone(),
                next.clone(),
                error.clone(),
                sign_in.clone(),
                hint.clone(),
                address_group.clone(),
            );
            glib::spawn_future_local(async move {
                let mut last = String::new();
                for origin in tries {
                    let api = Api::new(&origin, None);
                    match bg(async move { (api.auth_config().await, api) }).await {
                        (Ok(config), api) => {
                            address.set_text(api.base());
                            address_group.set_sensitive(false);
                            next.set_visible(false);
                            hint.set_visible(false);
                            build_sign_in(&sign_in, &error, api, config, &address_group, &next, &hint);
                            sign_in.set_visible(true);
                            return;
                        }
                        (Err(e), api) => last = format!("Couldn't reach {}: {e}", api.base()),
                    }
                }
                show_error(&error, &last);
                next.set_sensitive(true);
                next.set_label("Continue");
            });
        }
    };
    let c = connect.clone();
    address.connect_entry_activated(move |_| c());
    next.connect_clicked(move |_| connect());
    toolbar.upcast()
}

fn show_error(label: &gtk::Label, text: &str) {
    label.set_text(text);
    label.set_visible(true);
}

#[allow(clippy::too_many_arguments)]
fn build_sign_in(
    into: &gtk::Box,
    error: &gtk::Label,
    api: Api,
    config: AuthConfig,
    address_group: &adw::PreferencesGroup,
    next: &gtk::Button,
    hint: &gtk::Label,
) {
    while let Some(child) = into.first_child() {
        into.remove(&child);
    }
    let server = gtk::Label::builder()
        .label(format!("Sign in to {}", config.server_name))
        .wrap(true)
        .justify(gtk::Justification::Center)
        .build();
    server.add_css_class("title-4");
    into.append(&server);

    if let Some(oidc) = &config.oidc {
        let browser = gtk::Button::builder().label(&oidc.label).halign(gtk::Align::Center).build();
        browser.add_css_class("suggested-action");
        browser.add_css_class("pill");
        let api = api.clone();
        let error = error.clone();
        browser.connect_clicked(move |button| start_browser_sign_in(&api, button, &error));
        into.append(&browser);
    }
    if config.local_login || config.oidc.is_none() {
        let group = adw::PreferencesGroup::new();
        let username = adw::EntryRow::builder().title("Username").build();
        let password = adw::PasswordEntryRow::builder().title("Password").build();
        group.add(&username);
        group.add(&password);
        into.append(&group);
        let button = gtk::Button::builder().label("Sign in").halign(gtk::Align::Center).build();
        button.add_css_class("pill");
        if config.oidc.is_none() {
            button.add_css_class("suggested-action");
        }
        let submit = {
            let (api, username, password, button, error) = (api.clone(), username.clone(), password.clone(), button.clone(), error.clone());
            move || {
                let (user, pass) = (username.text().to_string(), password.text().to_string());
                if user.is_empty() || pass.is_empty() {
                    return;
                }
                button.set_sensitive(false);
                error.set_visible(false);
                let (api, button, error) = (api.clone(), button.clone(), error.clone());
                glib::spawn_future_local(async move {
                    let asking = api.clone();
                    match bg(async move { asking.login(&user, &pass, &Ctx::device_name()).await }).await {
                        Ok(signed) => match signed.token {
                            Some(token) => ctx().signed_in(api.base(), token, signed.user),
                            None => show_error(&error, "The server didn't give this app a sign-in. Update the server."),
                        },
                        Err(e) => show_error(&error, &e.to_string()),
                    }
                    button.set_sensitive(true);
                });
            }
        };
        let s = submit.clone();
        password.connect_entry_activated(move |_| s());
        button.connect_clicked(move |_| submit());
        into.append(&button);
    }

    let change = gtk::Button::builder().label("Change server").halign(gtk::Align::Center).build();
    change.add_css_class("flat");
    let (into_c, address_group, next, hint) = (into.clone(), address_group.clone(), next.clone(), hint.clone());
    change.connect_clicked(move |_| {
        into_c.set_visible(false);
        address_group.set_sensitive(true);
        next.set_visible(true);
        next.set_sensitive(true);
        next.set_label("Continue");
        hint.set_visible(true);
    });
    into.append(&change);
}

fn random_text(bytes: usize) -> String {
    let mut buffer = vec![0u8; bytes];
    rand::rng().fill(&mut buffer[..]);
    URL_SAFE_NO_PAD.encode(buffer)
}

/// Opens the server's sign-in in the browser; it comes back to [finish_browser_sign_in].
fn start_browser_sign_in(api: &Api, button: &gtk::Button, error: &gtk::Label) {
    let verifier = random_text(32);
    let challenge = URL_SAFE_NO_PAD.encode(Sha256::digest(verifier.as_bytes()));
    let state = random_text(16);
    ctx()
        .pending_browser_sign_in
        .replace(Some((api.base().to_string(), verifier, state.clone())));
    let url = format!(
        "{}/connect-app?challenge={}&state={}&device={}",
        api.base(),
        challenge,
        state,
        crate::api::enc(&Ctx::device_name())
    );
    let launcher = gtk::UriLauncher::new(&url);
    let error = error.clone();
    launcher.launch(Some(&ctx().window), gtk::gio::Cancellable::NONE, move |result| {
        if let Err(e) = result {
            show_error(&error, &format!("Couldn't open the browser: {e}"));
        }
    });
    button.set_label("Finish signing in in your browser…");
}

/// The browser's code: swapped (with this app's secret) for a sign-in.
pub fn finish_browser_sign_in(code: String, state: String) {
    let ctx: Rc<Ctx> = ctx();
    let Some((server, verifier, expected)) = ctx.pending_browser_sign_in.borrow().clone() else {
        ctx.toast("That sign-in wasn't started here: start it again");
        return;
    };
    if state != expected || code.is_empty() {
        ctx.toast("That sign-in didn't match: start it again");
        return;
    }
    ctx.pending_browser_sign_in.replace(None);
    glib::spawn_future_local(async move {
        let api = Api::new(&server, None);
        let asking = api.clone();
        match bg(async move { asking.app_token(&code, &verifier, &Ctx::device_name()).await }).await {
            Ok(signed) => match signed.token {
                Some(token) => {
                    ctx.signed_in(api.base(), token, signed.user);
                    ctx.window.present();
                }
                None => ctx.toast("The server didn't give this app a sign-in"),
            },
            Err(e) => ctx.toast(&e.to_string()),
        }
    });
}
