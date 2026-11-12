//! Preferences (the look, images, notifications, running in the background, the account) and
//! About.

use std::rc::Rc;

use adw::prelude::*;

use crate::ui::{Ctx, theme, widgets};

/// Saves a change to the look, and shows it.
fn restyle(change: impl FnOnce(&mut crate::config::Config)) {
    let ctx = crate::ui::ctx();
    let (palette, style, bold) = {
        let mut config = ctx.config.borrow_mut();
        change(&mut config);
        config.save();
        (config.palette.clone(), config.style.clone(), config.bold_text)
    };
    // Not while the settings are borrowed: redrawing reads them.
    theme::apply(&palette, &style, bold);
}

fn theme_choice(id: &str, name: &str, swatch_class: &str, active: bool, group: &mut Option<gtk::ToggleButton>) -> gtk::ToggleButton {
    let content = gtk::Box::builder().spacing(10).build();
    let swatch = gtk::Box::builder().valign(gtk::Align::Center).build();
    swatch.add_css_class("swatch");
    swatch.add_css_class(swatch_class);
    content.append(&swatch);
    content.append(&gtk::Label::new(Some(name)));
    let button = gtk::ToggleButton::builder().child(&content).tooltip_text(name).build();
    button.add_css_class("flat");
    button.add_css_class("theme-choice");
    match group {
        Some(first) => button.set_group(Some(first)),
        None => *group = Some(button.clone()),
    }
    button.set_active(active);
    let id = id.to_string();
    button.connect_toggled(move |b| {
        if b.is_active() {
            let id = id.clone();
            restyle(move |c| c.palette = id);
        }
    });
    button
}

/// The theme (as two-colour swatches, and the desktop's own on SuperAwesomeMagicalLinux), light or
/// dark, and bold text, as on the web and the phone.
fn appearance(ctx: &Rc<Ctx>) -> adw::PreferencesGroup {
    let group = adw::PreferencesGroup::builder().title("Appearance").build();
    let config = ctx.config.borrow();
    let themes = gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .column_spacing(8)
        .row_spacing(8)
        .min_children_per_line(2)
        .max_children_per_line(5)
        .margin_top(10)
        .margin_bottom(10)
        .margin_start(10)
        .margin_end(10)
        .build();
    let mut first: Option<gtk::ToggleButton> = None;
    if theme::desktop_scheme_path().is_some() {
        themes.append(&theme_choice(
            "desktop",
            "Desktop",
            "swatch-desktop",
            config.palette == "desktop",
            &mut first,
        ));
    }
    for palette in &theme::PALETTES {
        themes.append(&theme_choice(
            palette.id,
            palette.name,
            &format!("swatch-{}", palette.id),
            config.palette == palette.id,
            &mut first,
        ));
    }
    let theme_row = adw::PreferencesRow::builder().activatable(false).build();
    let theme_box = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    theme_box.append(
        &gtk::Label::builder()
            .label("Theme")
            .xalign(0.0)
            .margin_top(12)
            .margin_start(12)
            .build(),
    );
    if theme::desktop_scheme_path().is_some() {
        let hint = gtk::Label::builder()
            .label("Desktop follows SuperAwesomeMagicalLinux's colours as they change.")
            .xalign(0.0)
            .wrap(true)
            .margin_start(12)
            .margin_end(12)
            .build();
        hint.add_css_class("dim-label");
        hint.add_css_class("caption");
        theme_box.append(&hint);
    }
    theme_box.append(&themes);
    theme_row.set_child(Some(&theme_box));
    group.add(&theme_row);

    let styles = ["system", "light", "dark"];
    let style = adw::ComboRow::builder()
        .title("Style")
        .model(&gtk::StringList::new(&["As the system is", "Light", "Dark"]))
        .selected(styles.iter().position(|s| *s == config.style).unwrap_or(0) as u32)
        .build();
    let follows_desktop = config.palette == "desktop";
    style.set_sensitive(!follows_desktop);
    if follows_desktop {
        style.set_subtitle("The desktop's colours say light or dark");
    }
    style.connect_selected_notify(move |row| {
        let chosen = styles.get(row.selected() as usize).copied().unwrap_or("system");
        restyle(|c| c.style = chosen.to_string());
    });
    group.add(&style);

    let bold = adw::SwitchRow::builder()
        .title("Bold text")
        .subtitle("All text in Lexend Bold")
        .active(config.bold_text)
        .build();
    bold.connect_active_notify(move |row| {
        let on = row.is_active();
        restyle(|c| c.bold_text = on);
    });
    group.add(&bold);
    group
}

pub fn preferences(ctx: &Rc<Ctx>) {
    let dialog = adw::PreferencesDialog::new();
    let page = adw::PreferencesPage::new();
    page.add(&appearance(ctx));

    let reading = adw::PreferencesGroup::builder().title("Reading").build();
    let images = adw::SwitchRow::builder()
        .title("Always show images from the web")
        .subtitle("Senders can tell when you've opened their message from them")
        .active(ctx.config.borrow().always_show_images)
        .build();
    images.connect_active_notify(|row| {
        let ctx = crate::ui::ctx();
        let mut config = ctx.config.borrow_mut();
        config.always_show_images = row.is_active();
        config.save();
    });
    reading.add(&images);
    page.add(&reading);

    let alerts = adw::PreferencesGroup::builder().title("New mail").build();
    let notify = adw::SwitchRow::builder()
        .title("Notifications")
        .subtitle("For new mail in your inboxes, while you're not looking at the app")
        .active(ctx.config.borrow().notify)
        .build();
    notify.connect_active_notify(|row| {
        let ctx = crate::ui::ctx();
        let mut config = ctx.config.borrow_mut();
        config.notify = row.is_active();
        config.save();
    });
    alerts.add(&notify);
    let background = adw::SwitchRow::builder()
        .title("Keep running in the background")
        .subtitle("Tells you about new mail with the window closed, and starts when you sign in to the desktop")
        .active(ctx.config.borrow().background)
        .build();
    background.connect_active_notify(|row| {
        let ctx = crate::ui::ctx();
        let on = row.is_active();
        {
            let mut config = ctx.config.borrow_mut();
            config.background = on;
            config.save();
        }
        ctx.set_background(on);
    });
    alerts.add(&background);
    page.add(&alerts);

    let account = adw::PreferencesGroup::builder().title("Account").build();
    let (who, server) = {
        let config = ctx.config.borrow();
        let who = config
            .user
            .as_ref()
            .map(|u| {
                if u.display_name.is_empty() {
                    u.username.clone()
                } else {
                    format!("{} ({})", u.display_name, u.username)
                }
            })
            .unwrap_or_default();
        (who, config.server.clone().unwrap_or_default())
    };
    let row = widgets::plain_row(&who, &server);
    let sign_out = gtk::Button::builder().label("Sign out").valign(gtk::Align::Center).build();
    sign_out.add_css_class("destructive-action");
    let d = dialog.clone();
    sign_out.connect_clicked(move |_| {
        d.close();
        crate::ui::ctx().sign_out();
    });
    row.add_suffix(&sign_out);
    account.add(&row);
    let mailboxes = widgets::plain_row(
        "Mailboxes and signatures",
        "Add your own mailboxes and change signatures in the web app",
    );
    let open = gtk::Button::builder()
        .icon_name("send-to-symbolic")
        .tooltip_text("Open in the browser")
        .valign(gtk::Align::Center)
        .build();
    open.add_css_class("flat");
    let d = dialog.clone();
    open.connect_clicked(move |_| {
        d.close();
        crate::ui::mail::open_link(&format!("{server}/settings"));
    });
    mailboxes.add_suffix(&open);
    mailboxes.set_activatable_widget(Some(&open));
    account.add(&mailboxes);
    page.add(&account);

    dialog.add(&page);
    dialog.present(Some(&ctx.window));
}

pub fn about(ctx: &Rc<Ctx>) {
    let about = adw::AboutDialog::builder()
        .application_name("SuperAwesomeMagicalEmail")
        .application_icon(crate::APP_ID)
        .version(env!("CARGO_PKG_VERSION"))
        .comments("Your SuperAwesomeMagicalEmail server's mail on the Linux desktop.")
        .website("https://github.com/603Madison/SuperAwesomeMagicalEmail")
        .issue_url("https://github.com/603Madison/SuperAwesomeMagicalEmail/issues")
        .license_type(gtk::License::Gpl30Only)
        .build();
    about.add_legal_section(
        "Lexend",
        None,
        gtk::License::Custom,
        Some("The Lexend typeface is used under the SIL Open Font License 1.1."),
    );
    about.present(Some(&ctx.window));
}

/// The keyboard shortcuts, as a window of their own.
pub fn shortcuts(ctx: &Rc<Ctx>) {
    let groups: [(&str, &[(&str, &str)]); 3] = [
        (
            "General",
            &[
                ("<Primary>n", "Write a message"),
                ("<Primary>f", "Search"),
                ("F5", "Check for mail"),
                ("<Primary>comma", "Preferences"),
                ("<Primary>q", "Quit"),
            ],
        ),
        (
            "The message open",
            &[
                ("r", "Reply"),
                ("a", "Reply to all"),
                ("f", "Forward"),
                ("e", "Archive"),
                ("Delete", "Delete"),
                ("s", "Star or unstar"),
                ("<Shift>u", "Mark unread"),
                ("<Shift>i", "Show images"),
            ],
        ),
        (
            "Writing",
            &[
                ("<Primary>Return", "Send"),
                ("<Primary>s", "Keep in Drafts"),
                ("<Primary>w", "Close"),
            ],
        ),
    ];
    let section = gtk::ShortcutsSection::builder().section_name("shortcuts").build();
    for (title, keys) in groups {
        let group = gtk::ShortcutsGroup::builder().title(title).build();
        for (accelerator, what) in keys {
            group.add_shortcut(&gtk::ShortcutsShortcut::builder().accelerator(*accelerator).title(*what).build());
        }
        section.add_group(&group);
    }
    let window = gtk::ShortcutsWindow::builder().transient_for(&ctx.window).modal(true).build();
    window.add_section(&section);
    window.present();
}
