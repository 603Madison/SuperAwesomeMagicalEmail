//! The mail, laid out as the web app's: every inbox and each account's folders down the side, the
//! list of messages, and the one open beside it (one pane at a time when the window is narrow).
//!
//! Messages are drawn by WebKit with no scripts, no storage and nothing kept between messages.
//! The server has already cleaned them (and only fetches images from the web through itself, when
//! asked). Links open in the browser; mail links start a message here.

use std::cell::{Cell, RefCell};
use std::collections::HashMap;
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, gio, glib};
use webkit::prelude::*;

use crate::api::{Address, Attachment, Folder, MailAccount, Message, MessagePage, MessageSummary, is_unauthorized};
use crate::rt::bg;
use crate::ui::widgets::{self, avatar, status};
use crate::ui::{ctx, theme};

/// Every inbox together (as the server calls it).
pub const ALL: &str = "all";

/// A folder to list: one account's, or every inbox.
#[derive(Clone, Debug, PartialEq)]
struct Place {
    account: String,
    folder: String,
    role: String,
    title: String,
}

pub struct MailUi {
    pub root: gtk::Widget,
    outer: adw::OverlaySplitView,
    inner: adw::NavigationSplitView,
    // Down the side.
    nav: gtk::ListBox,
    places: RefCell<Vec<Option<Place>>>,
    accounts: RefCell<Vec<MailAccount>>,
    folders: RefCell<HashMap<String, Vec<Folder>>>,
    place: RefCell<Option<Place>>,
    write_button: gtk::Button,
    quiet_nav: Cell<bool>,
    // The list.
    list_title: gtk::Label,
    list_sub: gtk::Label,
    search: gtk::SearchEntry,
    quiet_search: Cell<bool>,
    banner: adw::Banner,
    list: gtk::ListBox,
    list_scroll: gtk::ScrolledWindow,
    list_stack: gtk::Stack,
    list_status: gtk::Box,
    messages: RefCell<Vec<MessageSummary>>,
    next: RefCell<Option<String>>,
    loading_more: Cell<bool>,
    list_generation: Cell<u64>,
    quiet_list: Cell<bool>,
    // The message open.
    reader_stack: gtk::Stack,
    reader_status: gtk::Box,
    header: gtk::Box,
    paper: gtk::Box,
    web: webkit::WebView,
    open: RefCell<Option<Message>>,
    images_for: RefCell<Option<String>>,
    open_generation: Cell<u64>,
    actions: gio::SimpleActionGroup,
    keys: gtk::ShortcutController,
    reply_buttons: Vec<gtk::Button>,
    edit_draft: gtk::Button,
    archive_button: gtk::Button,
    trash_button: gtk::Button,
    star_button: gtk::Button,
    move_menu: gio::Menu,
    move_button: gtk::MenuButton,
    more_menu: gio::Menu,
    more_button: gtk::MenuButton,
}

fn label(text: &str, class: &str) -> gtk::Label {
    let label = gtk::Label::builder()
        .label(text)
        .xalign(0.0)
        .ellipsize(gtk::pango::EllipsizeMode::End)
        .build();
    if !class.is_empty() {
        label.add_css_class(class);
    }
    label
}

fn icon_button(icon: &str, tooltip: &str, action: &str) -> gtk::Button {
    gtk::Button::builder()
        .icon_name(icon)
        .tooltip_text(tooltip)
        .action_name(action)
        .build()
}

fn main_menu() -> gtk::MenuButton {
    let menu = gio::Menu::new();
    menu.append(Some("Preferences"), Some("app.preferences"));
    menu.append(Some("Keyboard shortcuts"), Some("app.shortcuts"));
    menu.append(Some("About SuperAwesomeMagicalEmail"), Some("app.about"));
    let account = gio::Menu::new();
    account.append(Some("Sign out"), Some("app.sign-out"));
    menu.append_section(None, &account);
    let quit = gio::Menu::new();
    quit.append(Some("Quit"), Some("app.quit"));
    menu.append_section(None, &quit);
    gtk::MenuButton::builder()
        .icon_name("open-menu-symbolic")
        .menu_model(&menu)
        .tooltip_text("Menu")
        .primary(true)
        .build()
}

/// The webview messages are drawn in: no scripts, nothing kept, links handed out.
fn message_view() -> webkit::WebView {
    let session = webkit::NetworkSession::new_ephemeral();
    let view = webkit::WebView::builder()
        .network_session(&session)
        .hexpand(true)
        .vexpand(true)
        .build();
    if let Some(settings) = WebViewExt::settings(&view) {
        settings.set_enable_javascript(false);
        settings.set_enable_javascript_markup(false);
        settings.set_javascript_can_open_windows_automatically(false);
        settings.set_auto_load_images(true);
        settings.set_enable_developer_extras(false);
        settings.set_enable_page_cache(false);
        settings.set_enable_html5_local_storage(false);
        settings.set_enable_html5_database(false);
        settings.set_enable_webgl(false);
        settings.set_enable_webaudio(false);
        settings.set_enable_media(false);
        settings.set_enable_back_forward_navigation_gestures(false);
        settings.set_default_font_family("Lexend");
        settings.set_sans_serif_font_family("Lexend");
    }
    view.connect_decide_policy(|view, decision, kind| {
        if !matches!(
            kind,
            webkit::PolicyDecisionType::NavigationAction | webkit::PolicyDecisionType::NewWindowAction
        ) {
            return false;
        }
        let Some(navigation) = decision.downcast_ref::<webkit::NavigationPolicyDecision>() else {
            return false;
        };
        let Some(action) = navigation.navigation_action() else {
            return false;
        };
        let uri = action.request().and_then(|r| r.uri()).map(|u| u.to_string()).unwrap_or_default();
        let kind_of = action.navigation_type();
        let here = view.uri().map(|u| u.to_string()).unwrap_or_default();
        // A jump within the message (#section).
        if kind != webkit::PolicyDecisionType::NewWindowAction
            && !here.is_empty()
            && let (Some((page, _)), Some(this)) = (uri.split_once('#'), here.split('#').next())
            && page == this
        {
            decision.use_();
            return true;
        }
        if kind == webkit::PolicyDecisionType::NewWindowAction || kind_of == webkit::NavigationType::LinkClicked {
            decision.ignore();
            open_link(&uri);
            return true;
        }
        // The message itself (loaded from here); anything else (forms, reloads) isn't followed.
        if kind_of == webkit::NavigationType::Other {
            decision.use_();
        } else {
            decision.ignore();
        }
        true
    });
    // Nothing to inspect or reload.
    view.connect_context_menu(|_, menu, _| {
        for item in menu.items() {
            use webkit::ContextMenuAction as A;
            if matches!(
                item.stock_action(),
                A::Reload
                    | A::GoBack
                    | A::GoForward
                    | A::Stop
                    | A::InspectElement
                    | A::OpenLinkInNewWindow
                    | A::OpenImageInNewWindow
                    | A::OpenFrameInNewWindow
            ) {
                menu.remove(&item);
            }
        }
        false
    });
    view
}

/// A link in a message (or a person's name): mail links start a message here; the web's open in
/// the browser.
pub fn open_link(uri: &str) {
    let lower = uri.to_ascii_lowercase();
    if lower.starts_with("mailto:") {
        let (to, subject, body) = crate::api::mailto(uri);
        ctx().write_new(to, subject, body);
    } else if lower.starts_with("https://") || lower.starts_with("http://") {
        gtk::UriLauncher::new(uri).launch(Some(&ctx().window), gio::Cancellable::NONE, |result| {
            if let Err(e) = result {
                ctx().toast(&format!("Couldn't open the link: {e}"));
            }
        });
    }
}

fn escape(text: &str) -> String {
    text.replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
}

/// Text with its web addresses as links (escaped for HTML).
fn linkify(text: &str) -> String {
    let mut out = String::new();
    let mut rest = text;
    loop {
        let found = [rest.find("https://"), rest.find("http://")].into_iter().flatten().min();
        let Some(at) = found else {
            out.push_str(&escape(rest));
            return out;
        };
        out.push_str(&escape(&rest[..at]));
        let tail = &rest[at..];
        let end = tail
            .find(|c: char| c.is_whitespace() || matches!(c, '<' | '>' | '"' | '\''))
            .unwrap_or(tail.len());
        let mut url = &tail[..end];
        while let Some(last) = url.chars().last().filter(|c| ".,;:!?)]".contains(*c)) {
            url = &url[..url.len() - last.len_utf8()];
        }
        if url == "https://" || url == "http://" {
            out.push_str(&escape(url));
        } else {
            let safe = escape(url);
            out.push_str(&format!("<a href=\"{safe}\">{safe}</a>"));
        }
        rest = &tail[url.len()..];
    }
}

/// Plain-text mail as a page in the app's own colours, with quotes set apart (as the web app
/// shows it).
fn plain_html(text: &str, colours: &theme::ReadingColours, bold: bool) -> String {
    let text = text.replace("\r\n", "\n");
    let mut blocks: Vec<(bool, Vec<&str>)> = Vec::new();
    for line in text.split('\n') {
        let quoted = line.starts_with('>');
        match blocks.last_mut() {
            Some((q, lines)) if *q == quoted => lines.push(line),
            _ => blocks.push((quoted, vec![line])),
        }
    }
    let body: String = blocks
        .iter()
        .map(|(quoted, lines)| {
            if *quoted {
                let inner: Vec<&str> = lines
                    .iter()
                    .map(|l| l.strip_prefix("> ").or_else(|| l.strip_prefix('>')).unwrap_or(l))
                    .collect();
                format!("<blockquote>{}</blockquote>", linkify(&inner.join("\n")))
            } else {
                format!("<div>{}</div>", linkify(&lines.join("\n")))
            }
        })
        .collect();
    format!(
        r#"<!doctype html><html><head><meta charset="utf-8"><style>
html, body {{ margin: 0; background: {bg}; color: {text}; }}
body {{ padding: 4px 24px 28px; font: {weight} 15px/1.6 "Lexend", sans-serif; white-space: pre-wrap; overflow-wrap: anywhere; }}
a {{ color: {link}; }}
blockquote {{ margin: 8px 0; padding: 2px 0 2px 14px; border-left: 3px solid {muted}; color: {muted}; }}
</style></head><body>{body}</body></html>"#,
        bg = colours.background,
        text = colours.text,
        link = colours.link,
        muted = colours.muted,
        weight = if bold { 700 } else { 400 },
    )
}

/// "To Jo, Sam" for a header line, each name a link that writes to them.
fn people_markup(title: &str, people: &[Address]) -> String {
    let names: Vec<String> = people
        .iter()
        .map(|a| {
            format!(
                "<a href=\"{}\" title=\"{}\">{}</a>",
                glib::markup_escape_text(&format!("mailto:{}", a.email)),
                glib::markup_escape_text(&a.full()),
                glib::markup_escape_text(&a.shown())
            )
        })
        .collect();
    format!("{}  {}", glib::markup_escape_text(title), names.join(", "))
}

fn folder_count(folder: &Folder) -> Option<(i64, bool)> {
    if folder.role == "DRAFTS" && folder.total > 0 {
        Some((folder.total, true))
    } else if folder.unread > 0 && !matches!(folder.role.as_str(), "SENT" | "TRASH" | "JUNK") {
        Some((folder.unread, false))
    } else {
        None
    }
}

fn nav_row(icon: &str, title: &str, count: Option<(i64, bool)>, depth: i32, enabled: bool) -> gtk::ListBoxRow {
    let content = gtk::Box::builder().spacing(14).margin_start(depth.clamp(0, 4) * 14).build();
    content.append(&gtk::Image::from_icon_name(icon));
    content.append(&label(title, ""));
    if let Some((n, quiet)) = count {
        let count = gtk::Label::builder().label(n.to_string()).hexpand(true).xalign(1.0).build();
        count.add_css_class("count");
        if quiet {
            count.add_css_class("quiet");
        }
        content.append(&count);
    }
    let row = gtk::ListBoxRow::builder()
        .child(&content)
        .selectable(enabled)
        .activatable(enabled)
        .build();
    if !enabled {
        row.add_css_class("dim-label");
    }
    row
}

fn heading_row(account: &MailAccount, problem: Option<&str>) -> gtk::ListBoxRow {
    let content = gtk::Box::builder().spacing(6).build();
    let name = label(&account.shown().to_uppercase(), "");
    name.set_hexpand(true);
    name.set_tooltip_text(Some(&account.email));
    content.append(&name);
    if let Some(problem) = problem {
        let warning = gtk::Image::from_icon_name("dialog-warning-symbolic");
        warning.add_css_class("problem");
        warning.set_tooltip_text(Some(problem));
        content.append(&warning);
    }
    let row = gtk::ListBoxRow::builder()
        .child(&content)
        .selectable(false)
        .activatable(false)
        .build();
    row.add_css_class("heading-row");
    row
}

impl MailUi {
    pub fn new() -> Rc<MailUi> {
        // ---- Down the side: the brand, Write, every inbox, each account's folders, who's signed in.
        let nav = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Single).build();
        nav.add_css_class("nav-list");
        let write_button = gtk::Button::builder()
            .action_name("app.write")
            .tooltip_text("Write a message (Ctrl+N)")
            .build();
        write_button.set_child(Some(
            &adw::ButtonContent::builder()
                .icon_name("document-edit-symbolic")
                .label("Write")
                .build(),
        ));
        write_button.add_css_class("suggested-action");
        write_button.add_css_class("write-button");
        let column = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .margin_bottom(12)
            .build();
        column.append(&write_button);
        column.append(&nav);
        let sidebar = adw::ToolbarView::new();
        let sidebar_header = adw::HeaderBar::builder().show_title(false).build();
        let brand = gtk::Box::builder().spacing(10).margin_start(6).build();
        brand.append(&gtk::Image::builder().icon_name(crate::APP_ID).pixel_size(32).build());
        brand.append(&label("SuperAwesome\nMagicalEmail", "brand-name"));
        sidebar_header.pack_start(&brand);
        sidebar_header.pack_end(&main_menu());
        sidebar.add_top_bar(&sidebar_header);
        sidebar.set_content(Some(
            &gtk::ScrolledWindow::builder()
                .hscrollbar_policy(gtk::PolicyType::Never)
                .child(&column)
                .vexpand(true)
                .build(),
        ));
        let who = ctx().config.borrow().user.as_ref().map(|u| {
            if u.display_name.is_empty() {
                u.username.clone()
            } else {
                u.display_name.clone()
            }
        });
        if let Some(who) = who {
            let user = label(&who, "sidebar-user");
            sidebar.add_bottom_bar(&user);
        }

        // ---- The list: where you are, search, and the messages.
        let list_header = adw::HeaderBar::builder().show_title(false).build();
        let sidebar_toggle = gtk::ToggleButton::builder()
            .icon_name("sidebar-show-symbolic")
            .tooltip_text("Folders")
            .build();
        list_header.pack_start(&sidebar_toggle);
        let narrow_write = icon_button("document-edit-symbolic", "Write a message (Ctrl+N)", "app.write");
        list_header.pack_start(&narrow_write);
        list_header.pack_end(&icon_button("view-refresh-symbolic", "Check for mail (F5)", "app.refresh"));
        let list_title = label("Mail", "list-title");
        let list_sub = label("", "list-sub");
        let titles = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .margin_start(20)
            .margin_end(20)
            .margin_bottom(10)
            .build();
        titles.append(&list_title);
        titles.append(&list_sub);
        let search = gtk::SearchEntry::builder()
            .placeholder_text("Search mail")
            .search_delay(400)
            .margin_start(12)
            .margin_end(12)
            .margin_bottom(8)
            .build();
        search.add_css_class("search-field");
        let banner = adw::Banner::new("");
        let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Single).build();
        list.add_css_class("message-list");
        let list_scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&list)
            .vexpand(true)
            .build();
        let list_status = gtk::Box::builder().orientation(gtk::Orientation::Vertical).vexpand(true).build();
        let list_stack = gtk::Stack::builder()
            .vexpand(true)
            .transition_type(gtk::StackTransitionType::Crossfade)
            .build();
        list_stack.add_named(&list_scroll, Some("list"));
        list_stack.add_named(&widgets::loading(), Some("loading"));
        list_stack.add_named(&list_status, Some("status"));
        let list_column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        list_column.append(&titles);
        list_column.append(&search);
        list_column.append(&banner);
        list_column.append(&list_stack);
        let list_pane = adw::ToolbarView::new();
        list_pane.add_css_class("list-pane");
        list_pane.add_top_bar(&list_header);
        list_pane.set_content(Some(&list_column));

        // ---- The message: what can be done with it, then who it's from, then what it says.
        let reader_header = adw::HeaderBar::builder().show_title(false).build();
        let reply = icon_button("mail-reply-sender-symbolic", "Reply (R)", "msg.reply");
        let reply_all = icon_button("mail-reply-all-symbolic", "Reply to all (A)", "msg.reply-all");
        let forward = icon_button("mail-forward-symbolic", "Forward (F)", "msg.forward");
        let edit_draft = gtk::Button::builder()
            .label("Edit draft")
            .action_name("msg.edit-draft")
            .visible(false)
            .build();
        edit_draft.add_css_class("suggested-action");
        edit_draft.add_css_class("pill");
        // Reply all and Forward (and Mark unread, Star and Move) give way on a narrow window: they're
        // in the ⋮ menu too.
        let wide_start = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        wide_start.append(&reply_all);
        wide_start.append(&forward);
        reader_header.pack_start(&reply);
        reader_header.pack_start(&wide_start);
        reader_header.pack_start(&edit_draft);
        let more_menu = gio::Menu::new();
        let more = gtk::MenuButton::builder()
            .icon_name("view-more-symbolic")
            .tooltip_text("More")
            .menu_model(&more_menu)
            .build();
        let move_menu = gio::Menu::new();
        let move_button = gtk::MenuButton::builder()
            .icon_name("folder-open-symbolic")
            .tooltip_text("Move to")
            .menu_model(&move_menu)
            .build();
        let star_button = icon_button("non-starred-symbolic", "Star (S)", "msg.star");
        let unread = icon_button("mail-unread-symbolic", "Mark unread (Shift+U)", "msg.unread");
        let trash_button = icon_button("user-trash-symbolic", "Delete (Delete)", "msg.trash");
        let archive_button = icon_button("same-archive-symbolic", "Archive (E)", "msg.archive");
        let wide_end = gtk::Box::new(gtk::Orientation::Horizontal, 0);
        wide_end.append(&unread);
        wide_end.append(&star_button);
        wide_end.append(&move_button);
        for button in [
            more.upcast_ref::<gtk::Widget>(),
            wide_end.upcast_ref(),
            trash_button.upcast_ref(),
            archive_button.upcast_ref(),
        ] {
            reader_header.pack_end(button);
        }
        let header = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(14)
            .margin_start(24)
            .margin_end(24)
            .margin_top(6)
            .margin_bottom(14)
            .build();
        let header_scroll = gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .propagate_natural_height(true)
            .max_content_height(380)
            .child(&header)
            .build();
        let web = message_view();
        let paper = gtk::Box::builder()
            .margin_start(12)
            .margin_end(12)
            .margin_bottom(12)
            .vexpand(true)
            .build();
        paper.set_overflow(gtk::Overflow::Hidden);
        paper.append(&web);
        let message_column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
        message_column.append(&header_scroll);
        message_column.append(&paper);
        let reader_status = gtk::Box::builder().orientation(gtk::Orientation::Vertical).vexpand(true).build();
        let reader_stack = gtk::Stack::builder()
            .vexpand(true)
            .transition_type(gtk::StackTransitionType::Crossfade)
            .build();
        reader_stack.add_named(&reader_status, Some("status"));
        reader_stack.add_named(&widgets::loading(), Some("loading"));
        reader_stack.add_named(&message_column, Some("message"));
        let reader_pane = adw::ToolbarView::new();
        reader_pane.add_top_bar(&reader_header);
        reader_pane.set_content(Some(&reader_stack));

        let inner = adw::NavigationSplitView::builder()
            .sidebar(&adw::NavigationPage::builder().title("Messages").child(&list_pane).build())
            .content(&adw::NavigationPage::builder().title("Message").child(&reader_pane).build())
            .min_sidebar_width(300.0)
            .max_sidebar_width(460.0)
            .sidebar_width_fraction(0.4)
            .build();
        let outer = adw::OverlaySplitView::builder()
            .sidebar(&sidebar)
            .content(&inner)
            .min_sidebar_width(220.0)
            .max_sidebar_width(280.0)
            .build();
        outer
            .bind_property("show-sidebar", &sidebar_toggle, "active")
            .bidirectional()
            .sync_create()
            .build();
        outer.bind_property("collapsed", &sidebar_toggle, "visible").sync_create().build();
        outer.bind_property("collapsed", &narrow_write, "visible").sync_create().build();

        // Narrower: the folders slide in when asked for; narrower still, one pane at a time.
        let root = adw::BreakpointBin::builder()
            .width_request(360)
            .height_request(400)
            .child(&outer)
            .build();
        let middle = adw::Breakpoint::new(adw::BreakpointCondition::parse("max-width: 980sp").expect("a breakpoint"));
        middle.add_setter(&outer, "collapsed", Some(&true.to_value()));
        middle.add_setter(&outer, "show-sidebar", Some(&false.to_value()));
        root.add_breakpoint(middle);
        let narrow = adw::Breakpoint::new(adw::BreakpointCondition::parse("max-width: 640sp").expect("a breakpoint"));
        narrow.add_setter(&outer, "collapsed", Some(&true.to_value()));
        narrow.add_setter(&outer, "show-sidebar", Some(&false.to_value()));
        narrow.add_setter(&inner, "collapsed", Some(&true.to_value()));
        narrow.add_setter(&wide_start, "visible", Some(&false.to_value()));
        narrow.add_setter(&wide_end, "visible", Some(&false.to_value()));
        root.add_breakpoint(narrow);

        // What can be done with the message open: on the window, so its keys work wherever you
        // are in it (but not while typing).
        let actions = gio::SimpleActionGroup::new();
        let window = ctx().window.clone();
        window.insert_action_group("msg", Some(&actions));
        let keys = gtk::ShortcutController::new();
        window.add_controller(keys.clone());

        let ui = Rc::new(MailUi {
            root: root.upcast(),
            outer,
            inner,
            nav,
            places: RefCell::default(),
            accounts: RefCell::default(),
            folders: RefCell::default(),
            place: RefCell::default(),
            write_button,
            quiet_nav: Cell::new(false),
            list_title,
            list_sub,
            search,
            quiet_search: Cell::new(false),
            banner,
            list,
            list_scroll,
            list_stack,
            list_status,
            messages: RefCell::default(),
            next: RefCell::default(),
            loading_more: Cell::new(false),
            list_generation: Cell::new(0),
            quiet_list: Cell::new(false),
            reader_stack,
            reader_status,
            header,
            paper,
            web,
            open: RefCell::default(),
            images_for: RefCell::default(),
            open_generation: Cell::new(0),
            actions,
            keys,
            reply_buttons: vec![reply, reply_all, forward],
            edit_draft,
            archive_button,
            trash_button,
            star_button,
            move_menu,
            move_button,
            more_menu,
            more_button: more,
        });
        ui.wire();
        ui.close_reader();
        ui
    }

    fn wire(self: &Rc<Self>) {
        let weak = Rc::downgrade(self);
        self.nav.connect_row_selected(move |_, row| {
            let (Some(this), Some(row)) = (weak.upgrade(), row) else { return };
            if this.quiet_nav.get() {
                return;
            }
            let place = this.places.borrow().get(row.index() as usize).cloned().flatten();
            if let Some(place) = place {
                this.go(place);
                if this.outer.is_collapsed() {
                    this.outer.set_show_sidebar(false);
                }
            }
        });

        let weak = Rc::downgrade(self);
        self.search.connect_search_changed(move |_| {
            if let Some(this) = weak.upgrade()
                && !this.quiet_search.get()
            {
                this.close_reader();
                this.load_list(false, false);
            }
        });
        let weak = Rc::downgrade(self);
        self.search.connect_stop_search(move |entry| {
            entry.set_text("");
            if let Some(this) = weak.upgrade() {
                this.list.grab_focus();
            }
        });

        let weak = Rc::downgrade(self);
        self.list.connect_row_selected(move |_, row| {
            let (Some(this), Some(row)) = (weak.upgrade(), row) else { return };
            if this.quiet_list.get() {
                return;
            }
            let id = this.messages.borrow().get(row.index() as usize).map(|m| m.id.clone());
            if let Some(id) = id {
                this.open_message(id, false);
            }
        });
        let weak = Rc::downgrade(self);
        self.list.connect_row_activated(move |_, _| {
            if let Some(this) = weak.upgrade() {
                this.inner.set_show_content(true);
            }
        });
        // More as the list's end comes into view.
        let weak = Rc::downgrade(self);
        self.list_scroll.connect_edge_reached(move |_, edge| {
            if edge == gtk::PositionType::Bottom
                && let Some(this) = weak.upgrade()
            {
                this.load_list(true, true);
            }
        });

        // What can be done with the message open, from its buttons and keys.
        let add = |name: &str, run: fn(&Rc<MailUi>)| {
            let action = gio::SimpleAction::new(name, None);
            let weak = Rc::downgrade(self);
            action.connect_activate(move |_, _| {
                if let Some(this) = weak.upgrade() {
                    run(&this);
                }
            });
            self.actions.add_action(&action);
        };
        add("reply", |t| t.compose_from("REPLY"));
        add("reply-all", |t| t.compose_from("REPLY_ALL"));
        add("forward", |t| t.compose_from("FORWARD"));
        add("edit-draft", |t| t.compose_from("DRAFT"));
        add("archive", |t| t.act("ARCHIVE", None));
        add("trash", |t| t.act("TRASH", None));
        add("unread", |t| t.act("UNREAD", None));
        add("star", |t| t.toggle_star());
        add("junk", |t| t.act("JUNK", None));
        add("not-junk", |t| t.act("NOT_JUNK", None));
        add("save-original", |t| t.save_original());
        add("unsubscribe", |t| t.unsubscribe());
        add("show-images", |t| t.show_images());
        let move_to = gio::SimpleAction::new("move", Some(glib::VariantTy::STRING));
        let weak = Rc::downgrade(self);
        move_to.connect_activate(move |_, target| {
            if let (Some(this), Some(folder)) = (weak.upgrade(), target.and_then(|t| t.get::<String>())) {
                this.act("MOVE", Some(folder));
            }
        });
        self.actions.add_action(&move_to);

        for (trigger, action) in [
            ("r", "msg.reply"),
            ("a", "msg.reply-all"),
            ("f", "msg.forward"),
            ("e", "msg.archive"),
            ("y", "msg.archive"),
            ("Delete", "msg.trash"),
            ("numbersign", "msg.trash"),
            ("s", "msg.star"),
            ("<Shift>u", "msg.unread"),
            ("<Shift>i", "msg.show-images"),
        ] {
            if let Some(trigger) = gtk::ShortcutTrigger::parse_string(trigger) {
                self.keys
                    .add_shortcut(gtk::Shortcut::new(Some(trigger), Some(gtk::NamedAction::new(action))));
            }
        }

        // Plain-text messages are drawn in the theme's colours: again when it changes.
        let weak = Rc::downgrade(self);
        theme::on_change(move || {
            if let Some(this) = weak.upgrade() {
                let open = this.open.borrow().clone();
                if let Some(message) = open.filter(|m| m.html.is_none()) {
                    this.show_body(&message);
                }
            }
        });
    }

    // ---- What other parts of the app ask.

    pub fn accounts(&self) -> Vec<MailAccount> {
        self.accounts.borrow().clone()
    }

    /// The account being looked at (none for every inbox together).
    pub fn current_account(&self) -> Option<String> {
        self.place.borrow().as_ref().map(|p| p.account.clone()).filter(|a| a != ALL)
    }

    pub fn start(self: &Rc<Self>) {
        self.list_stack.set_visible_child_name("loading");
        self.load_accounts(true);
    }

    /// Everything again: the accounts, their folders and the list.
    pub fn refresh(self: &Rc<Self>) {
        self.load_accounts(false);
        self.load_list(false, true);
    }

    pub fn focus_search(&self) {
        if self.inner.is_collapsed() {
            self.inner.set_show_content(false);
        }
        self.search.grab_focus();
    }

    pub fn open_message_by_id(self: &Rc<Self>, id: &str) {
        self.open_message(id.to_string(), true);
    }

    /// Inboxes' unread counts, from the check for new mail.
    pub fn set_unread(self: &Rc<Self>, unread: &HashMap<String, i64>) {
        let mut changed = false;
        {
            let mut folders = self.folders.borrow_mut();
            for (account, count) in unread {
                if let Some(inbox) = folders
                    .get_mut(account)
                    .and_then(|list| list.iter_mut().find(|f| f.role == "INBOX"))
                    && inbox.unread != *count
                {
                    inbox.unread = *count;
                    changed = true;
                }
            }
        }
        if changed {
            self.rebuild_sidebar();
            self.show_titles();
        }
    }

    /// New mail came: the inbox being looked at shows it.
    pub fn new_mail(self: &Rc<Self>, arrived: &[MessageSummary]) {
        let mut accounts: Vec<&str> = arrived.iter().map(|m| m.account_id.as_str()).collect();
        accounts.dedup();
        for account in &accounts {
            self.load_folders(account);
        }
        let showing_inbox = self
            .place
            .borrow()
            .as_ref()
            .is_some_and(|p| p.role == "INBOX" && (p.account == ALL || accounts.contains(&p.account.as_str())));
        if showing_inbox && self.search.text().is_empty() {
            self.load_list(false, true);
        }
    }

    // ---- Down the side.

    fn load_accounts(self: &Rc<Self>, first: bool) {
        let Some(api) = ctx().api() else { return };
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let result = bg(async move { api.accounts().await }).await;
            let Some(this) = weak.upgrade() else { return };
            match result {
                Ok(accounts) => {
                    let ids: Vec<String> = accounts.iter().map(|a| a.id.clone()).collect();
                    this.write_button.set_sensitive(!accounts.is_empty());
                    let several = accounts.len() > 1;
                    this.accounts.replace(accounts);
                    this.folders.borrow_mut().retain(|id, _| ids.contains(id));
                    this.rebuild_sidebar();
                    for id in &ids {
                        this.load_folders(id);
                    }
                    let gone = this
                        .place
                        .borrow()
                        .as_ref()
                        .is_none_or(|p| if p.account == ALL { !several } else { !ids.contains(&p.account) });
                    if first || gone {
                        match ids.first() {
                            None => this.no_accounts(),
                            Some(id) if !several => this.go(Place {
                                account: id.clone(),
                                folder: "INBOX".into(),
                                role: "INBOX".into(),
                                title: "Inbox".into(),
                            }),
                            Some(_) => this.go(Place {
                                account: ALL.into(),
                                folder: "INBOX".into(),
                                role: "INBOX".into(),
                                title: "All inboxes".into(),
                            }),
                        }
                    }
                }
                Err(e) => {
                    if first {
                        this.show_list_status("dialog-warning-symbolic", "Couldn't load your mail", &e.to_string());
                    }
                    if is_unauthorized(&e) || !first {
                        ctx().failed(&e);
                    }
                }
            }
        });
    }

    fn load_folders(self: &Rc<Self>, account: &str) {
        let Some(api) = ctx().api() else { return };
        let weak = Rc::downgrade(self);
        let account = account.to_string();
        glib::spawn_future_local(async move {
            let id = account.clone();
            let result = bg(async move { api.folders(&id).await }).await;
            let Some(this) = weak.upgrade() else { return };
            match result {
                Ok(list) => {
                    this.folders.borrow_mut().insert(account, list);
                    this.rebuild_sidebar();
                    this.show_titles();
                }
                Err(e) => {
                    if is_unauthorized(&e) {
                        ctx().failed(&e);
                    } else {
                        eprintln!("Couldn't list {account}'s folders: {e}");
                    }
                }
            }
        });
    }

    fn rebuild_sidebar(&self) {
        self.quiet_nav.set(true);
        widgets::clear_list(&self.nav);
        let accounts = self.accounts.borrow();
        let folders = self.folders.borrow();
        let mut places = Vec::new();
        let inbox_unread = |account: &str| {
            folders
                .get(account)
                .and_then(|l| l.iter().find(|f| f.role == "INBOX"))
                .map(|f| f.unread)
                .unwrap_or(0)
        };
        if accounts.len() > 1 {
            let total: i64 = accounts.iter().map(|a| inbox_unread(&a.id)).sum();
            self.nav.append(&nav_row(
                "same-inbox-symbolic",
                "All inboxes",
                (total > 0).then_some((total, false)),
                0,
                true,
            ));
            places.push(Some(Place {
                account: ALL.into(),
                folder: "INBOX".into(),
                role: "INBOX".into(),
                title: "All inboxes".into(),
            }));
        }
        for account in accounts.iter() {
            self.nav.append(&heading_row(account, account.problem.as_deref()));
            places.push(None);
            match folders.get(&account.id) {
                Some(list) => {
                    for folder in list {
                        let parent = !folder.selectable
                            && list
                                .iter()
                                .any(|c| c.depth > folder.depth && c.path != folder.path && c.path.starts_with(&folder.path));
                        if !folder.selectable && !parent {
                            continue;
                        }
                        self.nav.append(&nav_row(
                            folder.icon(),
                            &folder.shown(),
                            folder_count(folder),
                            folder.depth,
                            folder.selectable,
                        ));
                        places.push(folder.selectable.then(|| Place {
                            account: account.id.clone(),
                            folder: folder.path.clone(),
                            role: folder.role.clone(),
                            title: folder.shown(),
                        }));
                    }
                }
                None => {
                    self.nav.append(&nav_row("same-inbox-symbolic", "Inbox", None, 0, true));
                    places.push(Some(Place {
                        account: account.id.clone(),
                        folder: "INBOX".into(),
                        role: "INBOX".into(),
                        title: "Inbox".into(),
                    }));
                }
            }
        }
        let current = self.place.borrow().clone();
        let at = current.and_then(|c| places.iter().position(|p| p.as_ref() == Some(&c)));
        self.places.replace(places);
        match at.and_then(|i| self.nav.row_at_index(i as i32)) {
            Some(row) => self.nav.select_row(Some(&row)),
            None => self.nav.unselect_all(),
        }
        self.quiet_nav.set(false);
    }

    fn no_accounts(&self) {
        self.place.replace(None);
        self.list_title.set_label("Mail");
        self.list_sub.set_label("");
        self.search.set_sensitive(false);
        let server = ctx().config.borrow().server.clone().unwrap_or_default();
        let empty = status(
            "mail-unread-symbolic",
            "No mailboxes yet",
            "Ask whoever runs your server to give you one, or add your own in the web app's settings.",
        );
        if !server.is_empty() {
            let open = gtk::Button::builder()
                .label("Open settings in the browser")
                .halign(gtk::Align::Center)
                .margin_top(8)
                .build();
            open.add_css_class("pill");
            open.add_css_class("suggested-action");
            open.connect_clicked(move |_| open_link(&format!("{server}/settings")));
            empty.append(&open);
        }
        widgets::clear_box(&self.list_status);
        self.list_status.append(&empty);
        self.list_stack.set_visible_child_name("status");
    }

    fn go(self: &Rc<Self>, place: Place) {
        self.place.replace(Some(place));
        self.search.set_sensitive(true);
        if !self.search.text().is_empty() {
            self.quiet_search.set(true);
            self.search.set_text("");
            self.quiet_search.set(false);
        }
        self.rebuild_sidebar();
        self.show_titles();
        self.close_reader();
        self.load_list(false, false);
    }

    fn show_titles(&self) {
        let Some(place) = self.place.borrow().clone() else { return };
        self.list_title.set_label(&place.title);
        let folders = self.folders.borrow();
        let accounts = self.accounts.borrow();
        let unread: i64 = if place.account == ALL {
            accounts
                .iter()
                .filter_map(|a| {
                    folders
                        .get(&a.id)
                        .and_then(|l| l.iter().find(|f| f.role == "INBOX"))
                        .map(|f| f.unread)
                })
                .sum()
        } else {
            folders
                .get(&place.account)
                .and_then(|l| l.iter().find(|f| f.path == place.folder))
                .map(|f| f.unread)
                .unwrap_or(0)
        };
        let whose = if place.account == ALL {
            format!("{} accounts", accounts.len())
        } else {
            accounts
                .iter()
                .find(|a| a.id == place.account)
                .map(|a| a.email.clone())
                .unwrap_or_default()
        };
        let counted = unread > 0 && !matches!(place.role.as_str(), "SENT" | "TRASH" | "JUNK" | "DRAFTS");
        self.list_sub
            .set_label(&if counted { format!("{whose} · {unread} unread") } else { whose });
    }

    // ---- The list.

    fn show_list_status(&self, icon: &str, title: &str, text: &str) {
        widgets::clear_box(&self.list_status);
        self.list_status.append(&status(icon, title, text));
        self.list_stack.set_visible_child_name("status");
    }

    /// The first page (afresh, or [quietly] in place), or the next one ([more]).
    fn load_list(self: &Rc<Self>, more: bool, quietly: bool) {
        let Some(place) = self.place.borrow().clone() else { return };
        let Some(api) = ctx().api() else { return };
        let cursor = if more {
            let Some(cursor) = self.next.borrow().clone() else { return };
            if self.loading_more.replace(true) {
                return;
            }
            Some(cursor)
        } else {
            None
        };
        let generation = if more {
            self.list_generation.get()
        } else {
            self.list_generation.set(self.list_generation.get() + 1);
            self.loading_more.set(false);
            self.list_generation.get()
        };
        if !more && !quietly {
            self.list_stack.set_visible_child_name("loading");
        }
        let query = self.search.text().to_string();
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let q = query.clone();
            let result = bg(async move { api.messages(&place.account, &place.folder, cursor.as_deref(), Some(&q)).await }).await;
            let Some(this) = weak.upgrade() else { return };
            if this.list_generation.get() != generation {
                return;
            }
            if more {
                this.loading_more.set(false);
            }
            match result {
                Ok(page) => this.show_page(page, more, &query),
                Err(e) => {
                    if is_unauthorized(&e) {
                        ctx().failed(&e);
                    } else if !more && (!quietly || this.messages.borrow().is_empty()) {
                        this.show_list_status("dialog-warning-symbolic", "Couldn't load your mail", &e.to_string());
                    } else {
                        ctx().toast(&e.to_string());
                    }
                }
            }
        });
    }

    fn show_page(self: &Rc<Self>, page: MessagePage, more: bool, query: &str) {
        let open_id = self.open.borrow().as_ref().map(|m| m.summary.id.clone());
        self.quiet_list.set(true);
        if !more {
            widgets::clear_list(&self.list);
            self.messages.borrow_mut().clear();
        }
        for message in page.messages {
            if more && self.messages.borrow().iter().any(|m| m.id == message.id) {
                continue;
            }
            let row = gtk::ListBoxRow::new();
            self.fill_row(&row, &message);
            self.list.append(&row);
            self.messages.borrow_mut().push(message);
        }
        if let Some(i) = open_id.and_then(|id| self.messages.borrow().iter().position(|m| m.id == id))
            && let Some(row) = self.list.row_at_index(i as i32)
        {
            self.list.select_row(Some(&row));
        }
        self.quiet_list.set(false);
        self.next.replace(page.next);

        let accounts = self.accounts.borrow().clone();
        let problems: Vec<String> = page
            .problems
            .iter()
            .map(|(id, problem)| {
                let name = accounts
                    .iter()
                    .find(|a| &a.id == id)
                    .map(|a| a.shown().to_string())
                    .unwrap_or_else(|| id.clone());
                format!("{name}: {problem}")
            })
            .collect();
        if !problems.is_empty() {
            self.banner
                .set_title(&glib::markup_escape_text(&format!("Couldn't reach {}", problems.join("; "))));
            self.banner.set_revealed(true);
        } else if !more {
            self.banner.set_revealed(false);
        }

        if self.messages.borrow().is_empty() {
            let place = self.place.borrow().clone();
            let title = place.as_ref().map(|p| p.title.clone()).unwrap_or_default();
            if !query.trim().is_empty() {
                self.show_list_status(
                    "system-search-symbolic",
                    "Nothing found",
                    &format!("Nothing in {title} matches “{}”.", query.trim()),
                );
            } else if place.is_some_and(|p| p.role == "INBOX") {
                self.show_list_status("same-inbox-symbolic", "All caught up", "There's nothing in your inbox.");
            } else {
                self.show_list_status("folder-symbolic", "Nothing here", &format!("{title} is empty."));
            }
        } else {
            self.list_stack.set_visible_child_name("list");
            // Keys are shortcuts (not typed into the search) until someone searches. (Not into the
            // list: focusing a row would open it.)
            let window = ctx().window.clone();
            if !more && self.search.text().is_empty() && GtkWindowExt::focus(&window).is_some_and(|f| f.is_ancestor(&self.search)) {
                GtkWindowExt::set_focus(&window, None::<&gtk::Widget>);
            }
            // A short page doesn't reach the list's end: the next one comes now.
            let weak = Rc::downgrade(self);
            glib::idle_add_local_once(move || {
                if let Some(this) = weak.upgrade() {
                    let adjustment = this.list_scroll.vadjustment();
                    if adjustment.upper() <= adjustment.page_size() + 1.0 {
                        this.load_list(true, true);
                    }
                }
            });
        }
    }

    fn fill_row(&self, row: &gtk::ListBoxRow, m: &MessageSummary) {
        let place = self.place.borrow().clone();
        let accounts = self.accounts.borrow();
        let outgoing = m.draft || place.as_ref().is_some_and(|p| matches!(p.role.as_str(), "SENT" | "DRAFTS"));
        let tag = place
            .as_ref()
            .filter(|p| p.account == ALL && accounts.len() > 1)
            .and_then(|_| accounts.iter().find(|a| a.id == m.account_id))
            .map(|a| a.shown().to_string());

        let content = gtk::Box::builder().spacing(10).build();
        let dot = gtk::Box::builder()
            .width_request(7)
            .height_request(7)
            .valign(gtk::Align::Center)
            .build();
        dot.add_css_class("unread-dot");
        dot.set_opacity(if m.unread { 1.0 } else { 0.0 });
        content.append(&dot);
        let person = if outgoing { m.to.first() } else { m.from.as_ref() };
        content.append(&avatar(person, 40));
        let text = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(1)
            .hexpand(true)
            .build();
        let top = gtk::Box::builder().spacing(6).build();
        let who = if outgoing {
            let names: Vec<String> = m.to.iter().map(Address::shown).collect();
            if names.is_empty() {
                if m.draft { "Draft".to_string() } else { "(no one)".to_string() }
            } else {
                format!("To {}", names.join(", "))
            }
        } else {
            m.from.as_ref().map(Address::shown).unwrap_or_else(|| "(unknown sender)".into())
        };
        let from = label(&who, "message-from");
        from.set_hexpand(true);
        top.append(&from);
        if m.has_attachments {
            let clip = gtk::Image::builder()
                .icon_name("mail-attachment-symbolic")
                .pixel_size(14)
                .tooltip_text("Has attachments")
                .build();
            clip.add_css_class("message-date");
            top.append(&clip);
        }
        if m.answered {
            let replied = gtk::Image::builder()
                .icon_name("mail-replied-symbolic")
                .pixel_size(14)
                .tooltip_text("Replied")
                .build();
            replied.add_css_class("message-date");
            top.append(&replied);
        }
        if m.flagged {
            let star = gtk::Image::builder()
                .icon_name("starred-symbolic")
                .pixel_size(14)
                .tooltip_text("Starred")
                .build();
            star.add_css_class("starred");
            top.append(&star);
        }
        top.append(&label(&widgets::short_date(m.date), "message-date"));
        text.append(&top);
        let subject = if m.subject.trim().is_empty() {
            "(no subject)"
        } else {
            m.subject.as_str()
        };
        text.append(&label(subject, "message-subject"));
        // Which account it came to (for every inbox together), then the start of what it says.
        let line = gtk::Box::builder().spacing(6).build();
        if let Some(tag) = tag {
            let chip = label(&tag, "account-tag");
            chip.set_valign(gtk::Align::Center);
            chip.set_max_width_chars(16);
            line.append(&chip);
        }
        if !m.preview.is_empty() {
            line.append(&label(&m.preview, "message-preview"));
        }
        if line.first_child().is_some() {
            text.append(&line);
        }
        content.append(&text);
        row.set_child(Some(&content));
        if m.unread {
            row.add_css_class("unread");
        } else {
            row.remove_css_class("unread");
        }
    }

    fn update_summary(&self, id: &str, change: impl FnOnce(&mut MessageSummary)) {
        let found = {
            let mut messages = self.messages.borrow_mut();
            messages.iter().position(|m| m.id == id).map(|i| {
                change(&mut messages[i]);
                (i, messages[i].clone())
            })
        };
        if let Some((i, message)) = found
            && let Some(row) = self.list.row_at_index(i as i32)
        {
            self.fill_row(&row, &message);
        }
    }

    fn remove_message(self: &Rc<Self>, id: &str) {
        let at = self.messages.borrow().iter().position(|m| m.id == id);
        if let Some(i) = at {
            self.messages.borrow_mut().remove(i);
            if let Some(row) = self.list.row_at_index(i as i32) {
                // GTK would move the focus to another row, which picks (and opens) it.
                let window = ctx().window.clone();
                if GtkWindowExt::focus(&window).is_some_and(|f| f == row || f.is_ancestor(&row)) {
                    GtkWindowExt::set_focus(&window, None::<&gtk::Widget>);
                }
                self.quiet_list.set(true);
                self.list.remove(&row);
                self.quiet_list.set(false);
            }
        }
        if self.messages.borrow().is_empty() {
            self.show_page(
                MessagePage {
                    messages: vec![],
                    next: self.next.borrow().clone(),
                    problems: HashMap::new(),
                },
                true,
                &self.search.text(),
            );
        }
    }

    // ---- The message.

    fn close_reader(&self) {
        self.open_generation.set(self.open_generation.get() + 1);
        self.open.replace(None);
        widgets::clear_box(&self.reader_status);
        self.reader_status
            .append(&status("mail-read-symbolic", "No message open", "Pick a message to read it here."));
        self.reader_stack.set_visible_child_name("status");
        self.set_actions_enabled(false);
        self.move_button.set_visible(false);
        self.quiet_list.set(true);
        self.list.unselect_all();
        self.quiet_list.set(false);
        // (Also when wide: narrowing the window then shows the list, not an empty page.)
        self.inner.set_show_content(false);
    }

    fn set_actions_enabled(&self, on: bool) {
        self.more_button.set_sensitive(on);
        for name in self.actions.list_actions() {
            if let Some(action) = self
                .actions
                .lookup_action(&name)
                .and_then(|a| a.downcast::<gio::SimpleAction>().ok())
            {
                action.set_enabled(on);
            }
        }
    }

    fn enable(&self, name: &str, on: bool) {
        if let Some(action) = self
            .actions
            .lookup_action(name)
            .and_then(|a| a.downcast::<gio::SimpleAction>().ok())
        {
            action.set_enabled(on);
        }
    }

    fn open_message(self: &Rc<Self>, id: String, show: bool) {
        let Some(api) = ctx().api() else { return };
        self.open_generation.set(self.open_generation.get() + 1);
        let generation = self.open_generation.get();
        let images = ctx().config.borrow().always_show_images || self.images_for.borrow().as_deref() == Some(id.as_str());
        let already = self.open.borrow().as_ref().is_some_and(|m| m.summary.id == id);
        if !already {
            self.reader_stack.set_visible_child_name("loading");
            self.set_actions_enabled(false);
        }
        if show {
            self.inner.set_show_content(true);
        }
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let result = bg(async move { api.message(&id, images).await }).await;
            let Some(this) = weak.upgrade() else { return };
            if this.open_generation.get() != generation {
                return;
            }
            match result {
                Ok(message) => this.show_message(message),
                Err(e) => {
                    if is_unauthorized(&e) {
                        ctx().failed(&e);
                    }
                    widgets::clear_box(&this.reader_status);
                    this.reader_status
                        .append(&status("dialog-warning-symbolic", "Couldn't open this message", &e.to_string()));
                    this.reader_stack.set_visible_child_name("status");
                }
            }
        });
    }

    fn folder_role(&self, account: &str, path: &str) -> String {
        if path.eq_ignore_ascii_case("INBOX") {
            return "INBOX".into();
        }
        self.folders
            .borrow()
            .get(account)
            .and_then(|l| l.iter().find(|f| f.path == path))
            .map(|f| f.role.clone())
            .unwrap_or_default()
    }

    fn show_message(self: &Rc<Self>, message: Message) {
        let s = message.summary.clone();
        let at = self.messages.borrow().iter().position(|m| m.id == s.id);
        if let Some(row) = at.and_then(|i| self.list.row_at_index(i as i32))
            && !row.is_selected()
        {
            self.quiet_list.set(true);
            self.list.select_row(Some(&row));
            self.quiet_list.set(false);
        }
        let was_unread = self.messages.borrow().iter().find(|m| m.id == s.id).is_some_and(|m| m.unread);
        self.update_summary(&s.id, |m| {
            m.unread = false;
            m.flagged = s.flagged;
        });
        if was_unread {
            self.load_folders(&s.account_id);
            ctx().app.withdraw_notification(&s.id);
        }
        self.fill_header(&message);
        self.show_body(&message);

        // What can be done with it.
        let role = self.folder_role(&s.account_id, &s.folder);
        self.set_actions_enabled(true);
        for button in &self.reply_buttons {
            button.set_visible(!s.draft);
        }
        self.enable("reply", !s.draft);
        self.enable("reply-all", !s.draft);
        self.enable("forward", !s.draft);
        self.edit_draft.set_visible(s.draft);
        self.enable("edit-draft", s.draft);
        self.archive_button.set_visible(role != "ARCHIVE");
        self.enable("archive", role != "ARCHIVE");
        self.trash_button.set_tooltip_text(Some(if role == "TRASH" {
            "Delete for good (Delete)"
        } else {
            "Delete (Delete)"
        }));
        self.show_star(s.flagged);
        self.enable("show-images", message.remote_images && !message.images_shown);
        self.enable("unsubscribe", message.unsubscribe.is_some());

        self.move_menu.remove_all();
        let folders = self.folders.borrow().get(&s.account_id).cloned().unwrap_or_default();
        for folder in folders.iter().filter(|f| f.selectable && f.path != s.folder) {
            let item = gio::MenuItem::new(Some(&folder.shown()), None);
            item.set_action_and_target_value(Some("msg.move"), Some(&folder.path.to_variant()));
            self.move_menu.append_item(&item);
        }
        self.move_button.set_visible(self.move_menu.n_items() > 0);

        self.more_menu.remove_all();
        let replies = gio::Menu::new();
        if !s.draft {
            replies.append(Some("Reply to all"), Some("msg.reply-all"));
            replies.append(Some("Forward"), Some("msg.forward"));
        }
        replies.append(Some("Mark unread"), Some("msg.unread"));
        replies.append(Some(if s.flagged { "Unstar" } else { "Star" }), Some("msg.star"));
        self.more_menu.append_section(None, &replies);
        if self.move_menu.n_items() > 0 {
            self.more_menu.append_submenu(Some("Move to"), &self.move_menu);
        }
        if role == "JUNK" {
            self.more_menu.append(Some("Not junk"), Some("msg.not-junk"));
        } else {
            self.more_menu.append(Some("Junk"), Some("msg.junk"));
        }
        self.more_menu.append(Some("Save the original (.eml)"), Some("msg.save-original"));
        if message.unsubscribe.is_some() {
            self.more_menu.append(Some("Unsubscribe"), Some("msg.unsubscribe"));
        }

        self.open.replace(Some(message));
        self.reader_stack.set_visible_child_name("message");
    }

    fn show_star(&self, flagged: bool) {
        self.star_button
            .set_icon_name(if flagged { "starred-symbolic" } else { "non-starred-symbolic" });
        self.star_button
            .set_tooltip_text(Some(if flagged { "Unstar (S)" } else { "Star (S)" }));
        if flagged {
            self.star_button.add_css_class("starred");
        } else {
            self.star_button.remove_css_class("starred");
        }
    }

    fn fill_header(self: &Rc<Self>, message: &Message) {
        widgets::clear_box(&self.header);
        let s = &message.summary;
        let subject = gtk::Label::builder()
            .label(if s.subject.trim().is_empty() {
                "(no subject)"
            } else {
                s.subject.as_str()
            })
            .wrap(true)
            .wrap_mode(gtk::pango::WrapMode::WordChar)
            .xalign(0.0)
            .selectable(true)
            .build();
        subject.add_css_class("reader-subject");
        self.header.append(&subject);

        let from_row = gtk::Box::builder().spacing(12).build();
        from_row.append(&avatar(s.from.as_ref(), 44));
        let people = gtk::Box::builder()
            .orientation(gtk::Orientation::Vertical)
            .spacing(2)
            .hexpand(true)
            .build();
        let sender = match &s.from {
            Some(from) if from.name.as_deref().is_some_and(|n| !n.trim().is_empty()) => format!(
                "<b>{}</b>  <span alpha=\"70%\">&lt;{}&gt;</span>",
                glib::markup_escape_text(&from.shown()),
                glib::markup_escape_text(&from.email)
            ),
            Some(from) => format!("<b>{}</b>", glib::markup_escape_text(&from.email)),
            None => "<b>(unknown sender)</b>".into(),
        };
        let sender = gtk::Label::builder()
            .label(sender)
            .use_markup(true)
            .xalign(0.0)
            .wrap(true)
            .wrap_mode(gtk::pango::WrapMode::WordChar)
            .selectable(true)
            .build();
        people.append(&sender);
        // When, under who (it fits a narrow window there).
        let date = gtk::Label::builder()
            .label(widgets::full_date(s.date))
            .xalign(0.0)
            .wrap(true)
            .build();
        date.add_css_class("reader-meta");
        people.append(&date);
        for (title, list) in [
            ("To", &s.to),
            ("Cc", &message.cc),
            ("Bcc", &message.bcc),
            ("Replies go to", &message.reply_to),
        ] {
            if list.is_empty() {
                continue;
            }
            let line = gtk::Label::builder()
                .label(people_markup(title, list))
                .use_markup(true)
                .xalign(0.0)
                .wrap(true)
                .wrap_mode(gtk::pango::WrapMode::WordChar)
                .build();
            line.add_css_class("reader-meta");
            line.connect_activate_link(|_, uri| {
                open_link(uri);
                glib::Propagation::Stop
            });
            people.append(&line);
        }
        from_row.append(&people);
        self.header.append(&from_row);

        if message.remote_images && !message.images_shown {
            let banner = gtk::Box::builder().spacing(10).build();
            banner.add_css_class("images-banner");
            banner.append(&gtk::Image::from_icon_name("image-x-generic-symbolic"));
            let text = gtk::Label::builder()
                .label("Images from the web are hidden, so the sender can't tell when you read this.")
                .wrap(true)
                .xalign(0.0)
                .hexpand(true)
                .build();
            banner.append(&text);
            let show = gtk::Button::builder()
                .label("Show images")
                .action_name("msg.show-images")
                .valign(gtk::Align::Center)
                .build();
            show.add_css_class("flat");
            banner.append(&show);
            self.header.append(&banner);
        }

        let attachments: Vec<&Attachment> = message.attachments.iter().filter(|a| !a.inline).collect();
        if !attachments.is_empty() {
            let flow = gtk::FlowBox::builder()
                .selection_mode(gtk::SelectionMode::None)
                .column_spacing(8)
                .row_spacing(8)
                .min_children_per_line(1)
                .max_children_per_line(4)
                .build();
            for attachment in attachments {
                let content = gtk::Box::builder().spacing(10).build();
                let kind = if attachment.content_type.starts_with("image/") {
                    "image-x-generic-symbolic"
                } else {
                    "text-x-generic-symbolic"
                };
                content.append(&gtk::Image::builder().icon_name(kind).pixel_size(20).build());
                let words = gtk::Box::builder().orientation(gtk::Orientation::Vertical).hexpand(true).build();
                let name = label(&attachment.filename, "");
                name.set_ellipsize(gtk::pango::EllipsizeMode::Middle);
                name.set_max_width_chars(28);
                words.append(&name);
                let size = if attachment.size > 0 {
                    widgets::file_size(attachment.size)
                } else {
                    attachment.content_type.clone()
                };
                words.append(&label(&size, "attachment-size"));
                content.append(&words);
                content.append(&gtk::Image::from_icon_name("document-save-symbolic"));
                let button = gtk::Button::builder()
                    .child(&content)
                    .tooltip_text(format!("Save {}", attachment.filename))
                    .build();
                button.add_css_class("flat");
                button.add_css_class("attachment");
                let attachment = attachment.clone();
                button.connect_clicked(move |_| save_link(&attachment.url, &attachment.filename));
                flow.append(&button);
            }
            self.header.append(&flow);
        }
    }

    fn show_body(&self, message: &Message) {
        let base = ctx()
            .api()
            .map(|a| format!("{}/", a.base()))
            .unwrap_or_else(|| "about:blank".into());
        match &message.html {
            Some(html) => {
                self.paper.add_css_class("paper");
                self.web.set_background_color(&gdk::RGBA::WHITE);
                self.web.load_html(html, Some(&base));
            }
            None => {
                self.paper.remove_css_class("paper");
                let colours = theme::reading_colours();
                let background = gdk::RGBA::parse(colours.background.as_str()).unwrap_or(gdk::RGBA::WHITE);
                self.web.set_background_color(&background);
                let bold = ctx().config.borrow().bold_text;
                self.web.load_html(&plain_html(&message.text, &colours, bold), Some(&base));
            }
        }
    }

    // ---- Doing things with it.

    fn compose_from(self: &Rc<Self>, mode: &'static str) {
        let Some(id) = self.open.borrow().as_ref().map(|m| m.summary.id.clone()) else {
            return;
        };
        let Some(api) = ctx().api() else { return };
        glib::spawn_future_local(async move {
            let zone = widgets::zone();
            match bg(async move { api.compose(&id, mode, &zone).await }).await {
                Ok(draft) => ctx().write(Some(draft)),
                Err(e) => ctx().failed(&e),
            }
        });
    }

    fn act(self: &Rc<Self>, action: &'static str, folder: Option<String>) {
        let Some(summary) = self.open.borrow().as_ref().map(|m| m.summary.clone()) else {
            return;
        };
        let Some(api) = ctx().api() else { return };
        let role = self.folder_role(&summary.account_id, &summary.folder);
        let target_name = folder.as_ref().map(|path| {
            self.folders
                .borrow()
                .get(&summary.account_id)
                .and_then(|l| l.iter().find(|f| &f.path == path))
                .map(|f| f.shown())
                .unwrap_or_else(|| path.clone())
        });
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let ids = vec![summary.id.clone()];
            let result = bg(async move { api.act(&ids, action, folder.as_deref()).await }).await;
            let Some(this) = weak.upgrade() else { return };
            match result {
                Ok(()) => {
                    if action == "UNREAD" {
                        this.update_summary(&summary.id, |m| m.unread = true);
                    } else {
                        this.remove_message(&summary.id);
                    }
                    if this.open.borrow().as_ref().is_some_and(|m| m.summary.id == summary.id) {
                        this.close_reader();
                    }
                    this.load_folders(&summary.account_id);
                    let done = match action {
                        "ARCHIVE" => "Archived".to_string(),
                        "TRASH" if role == "TRASH" => "Deleted for good".to_string(),
                        "TRASH" => "Moved to Trash".to_string(),
                        "JUNK" => "Moved to Junk".to_string(),
                        "NOT_JUNK" => "Moved to your inbox".to_string(),
                        "MOVE" => format!("Moved to {}", target_name.unwrap_or_default()),
                        "UNREAD" => "Marked unread".to_string(),
                        _ => String::new(),
                    };
                    if !done.is_empty() {
                        ctx().toast(&done);
                    }
                }
                Err(e) => ctx().failed(&e),
            }
        });
    }

    fn toggle_star(self: &Rc<Self>) {
        let Some(summary) = self.open.borrow().as_ref().map(|m| m.summary.clone()) else {
            return;
        };
        let Some(api) = ctx().api() else { return };
        let flag = !summary.flagged;
        let weak = Rc::downgrade(self);
        glib::spawn_future_local(async move {
            let ids = vec![summary.id.clone()];
            let result = bg(async move { api.act(&ids, if flag { "FLAG" } else { "UNFLAG" }, None).await }).await;
            let Some(this) = weak.upgrade() else { return };
            match result {
                Ok(()) => {
                    if let Some(open) = this.open.borrow_mut().as_mut().filter(|m| m.summary.id == summary.id) {
                        open.summary.flagged = flag;
                    }
                    this.update_summary(&summary.id, |m| m.flagged = flag);
                    this.show_star(flag);
                }
                Err(e) => ctx().failed(&e),
            }
        });
    }

    fn show_images(self: &Rc<Self>) {
        let Some(id) = self.open.borrow().as_ref().map(|m| m.summary.id.clone()) else {
            return;
        };
        self.images_for.replace(Some(id.clone()));
        self.open_message(id, false);
    }

    fn save_original(&self) {
        let Some(message) = self.open.borrow().clone() else { return };
        let name: String = message
            .summary
            .subject
            .chars()
            .map(|c| if matches!(c, '/' | '\\' | '\0') { '-' } else { c })
            .take(80)
            .collect();
        let name = if name.trim().is_empty() {
            "message".to_string()
        } else {
            name.trim().to_string()
        };
        save_link(&message.raw_url, &format!("{name}.eml"));
    }

    fn unsubscribe(&self) {
        let Some(link) = self.open.borrow().as_ref().and_then(|m| m.unsubscribe.clone()) else {
            return;
        };
        if link.to_ascii_lowercase().starts_with("mailto:") {
            open_link(&link);
            ctx().toast("Send that message to unsubscribe");
        } else {
            open_link(&link);
        }
    }
}

impl Drop for MailUi {
    fn drop(&mut self) {
        // Signed out: the keys go with the mail (the actions are replaced by the next sign-in's).
        if let Some(ctx) = crate::ui::try_ctx() {
            ctx.window.remove_controller(&self.keys);
        }
    }
}

/// Saves a signed link's file (an attachment, or a message's original) where the person picks.
fn save_link(url: &str, filename: &str) {
    let dialog = gtk::FileDialog::builder().title("Save").initial_name(filename).modal(true).build();
    let url = url.to_string();
    let shown = filename.to_string();
    dialog.save(Some(&ctx().window), gio::Cancellable::NONE, move |result| {
        let Ok(file) = result else { return };
        let Some(path) = file.path() else { return };
        let Some(api) = ctx().api() else { return };
        glib::spawn_future_local(async move {
            let target = path.clone();
            let result = bg(async move {
                let bytes = api.download(&url).await?;
                tokio::fs::write(&target, bytes).await?;
                anyhow::Ok(())
            })
            .await;
            match result {
                Ok(()) => {
                    let toast = adw::Toast::builder()
                        .title(format!("Saved {shown}"))
                        .button_label("Open")
                        .timeout(5)
                        .build();
                    toast.connect_button_clicked(move |_| {
                        gtk::FileLauncher::new(Some(&gio::File::for_path(&path))).launch(
                            Some(&ctx().window),
                            gio::Cancellable::NONE,
                            |_| {},
                        );
                    });
                    ctx().toasts.add_toast(toast);
                }
                Err(e) => ctx().failed(&e),
            }
        });
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn web_addresses_become_links() {
        assert_eq!(
            linkify("See https://example.com/a?b=1&c=2. Or <http://x.org>"),
            "See <a href=\"https://example.com/a?b=1&amp;c=2\">https://example.com/a?b=1&amp;c=2</a>. Or &lt;<a href=\"http://x.org\">http://x.org</a>&gt;"
        );
        assert_eq!(linkify("just https:// alone"), "just https:// alone");
        assert_eq!(
            linkify("(https://example.com)"),
            "(<a href=\"https://example.com\">https://example.com</a>)"
        );
    }

    #[test]
    fn plain_text_keeps_quotes_apart() {
        let colours = theme::ReadingColours {
            text: "#111".into(),
            muted: "#666".into(),
            link: "#60f".into(),
            background: "#fff".into(),
        };
        let html = plain_html("Hi <you>\n> quoted\n>more\nbye", &colours, false);
        assert!(html.contains("<div>Hi &lt;you&gt;</div><blockquote>quoted\nmore</blockquote><div>bye</div>"));
        assert!(!html.contains("<you>"));
    }

    #[test]
    fn header_names_are_escaped() {
        let markup = people_markup(
            "To",
            &[Address {
                email: "a@example.com".into(),
                name: Some("<Jo & Sam>".into()),
            }],
        );
        assert!(markup.contains("&lt;Jo &amp; Sam&gt;"));
        assert!(markup.starts_with("To  <a href=\"mailto:a@example.com\""));
    }
}
