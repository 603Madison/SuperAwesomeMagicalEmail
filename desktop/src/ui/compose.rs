//! Writing a message, in a window of its own (so you can read while you write): who it's from and
//! to, what about, what to say, and files to send with it. Sent through the server, which keeps a
//! copy in Sent; closing it with something written offers to keep it in Drafts.

use std::cell::{Cell, RefCell};
use std::rc::Rc;

use adw::prelude::*;
use gtk::{gdk, gio, glib};

use crate::api::{Address, Api, AttachmentRef, Draft, MailAccount};
use crate::rt::bg;
use crate::ui::{ctx, widgets};

/// A file going with the message: uploaded here, or kept from the one being forwarded.
#[derive(Clone)]
enum File {
    Uploading(u64, String),
    Uploaded { id: String, filename: String, size: i64 },
    Kept(AttachmentRef),
}

struct Compose {
    window: adw::Window,
    toasts: adw::ToastOverlay,
    api: Api,
    accounts: Vec<MailAccount>,
    from: Option<gtk::DropDown>,
    to: gtk::Entry,
    cc: gtk::Entry,
    bcc: gtk::Entry,
    subject: gtk::Entry,
    body: gtk::TextView,
    files: RefCell<Vec<File>>,
    chips: gtk::FlowBox,
    send_button: gtk::Button,
    draft: RefCell<Draft>,
    /// What it said when opened (or last kept): closing asks only if it's changed.
    kept: RefCell<String>,
    next_upload: Cell<u64>,
    done: Cell<bool>,
}

fn field(title: &str, entry: &impl IsA<gtk::Widget>) -> gtk::Box {
    let row = gtk::Box::builder().spacing(8).margin_start(22).margin_end(16).build();
    row.add_css_class("compose-field");
    let label = gtk::Label::builder().label(title).xalign(0.0).build();
    label.add_css_class("compose-label");
    row.append(&label);
    row.append(entry);
    row
}

fn entry(placeholder: &str) -> gtk::Entry {
    let entry = gtk::Entry::builder().hexpand(true).placeholder_text(placeholder).build();
    entry.add_css_class("compose-entry");
    entry
}

fn addresses(list: &[Address]) -> String {
    list.iter().map(Address::full).collect::<Vec<_>>().join(", ")
}

/// Opens a window to write [draft] (a new message, a reply, a forward, or a draft to finish).
pub fn open(api: Api, accounts: Vec<MailAccount>, draft: Draft) {
    let app = ctx().app.clone();
    let window = adw::Window::builder()
        .application(&app)
        .title(if draft.subject.is_empty() {
            "New message".to_string()
        } else {
            draft.subject.clone()
        })
        .icon_name(crate::APP_ID)
        .default_width(760)
        .default_height(680)
        .width_request(360)
        .height_request(400)
        .build();
    let toasts = adw::ToastOverlay::new();
    let toolbar = adw::ToolbarView::new();
    let header = adw::HeaderBar::new();
    let title = adw::WindowTitle::new(&window.title().unwrap_or_default(), "");
    header.set_title_widget(Some(&title));
    let send_button = gtk::Button::builder().tooltip_text("Send (Ctrl+Enter)").build();
    send_button.set_child(Some(
        &adw::ButtonContent::builder().icon_name("mail-send-symbolic").label("Send").build(),
    ));
    send_button.add_css_class("suggested-action");
    send_button.add_css_class("pill");
    header.pack_end(&send_button);
    let attach = gtk::Button::builder()
        .icon_name("mail-attachment-symbolic")
        .tooltip_text("Attach files")
        .build();
    header.pack_start(&attach);
    let save = gtk::Button::builder()
        .icon_name("document-save-symbolic")
        .tooltip_text("Save to Drafts (Ctrl+S)")
        .build();
    header.pack_start(&save);
    toolbar.add_top_bar(&header);

    let column = gtk::Box::builder().orientation(gtk::Orientation::Vertical).build();
    // From: one of your mailboxes.
    let from = if accounts.len() > 1 {
        let names: Vec<String> = accounts
            .iter()
            .map(|a| {
                if a.name.trim().is_empty() {
                    a.email.clone()
                } else {
                    format!("{} <{}>", a.name.trim(), a.email)
                }
            })
            .collect();
        let dropdown = gtk::DropDown::from_strings(&names.iter().map(String::as_str).collect::<Vec<_>>());
        dropdown.set_hexpand(true);
        dropdown.add_css_class("flat");
        dropdown.set_selected(accounts.iter().position(|a| a.id == draft.account_id).unwrap_or(0) as u32);
        column.append(&field("From", &dropdown));
        Some(dropdown)
    } else {
        let only = accounts.first().map(|a| a.email.clone()).unwrap_or_default();
        let label = gtk::Label::builder().label(only).xalign(0.0).hexpand(true).selectable(true).build();
        column.append(&field("From", &label));
        None
    };
    let to = entry("Names or addresses, with commas between");
    let more = gtk::ToggleButton::builder().label("Cc Bcc").valign(gtk::Align::Center).build();
    more.add_css_class("flat");
    let to_row = field("To", &to);
    to_row.append(&more);
    column.append(&to_row);
    let cc = entry("");
    let bcc = entry("");
    let cc_row = field("Cc", &cc);
    let bcc_row = field("Bcc", &bcc);
    let show_copies = !draft.cc.is_empty() || !draft.bcc.is_empty();
    more.set_active(show_copies);
    cc_row.set_visible(show_copies);
    bcc_row.set_visible(show_copies);
    more.bind_property("active", &cc_row, "visible").build();
    more.bind_property("active", &bcc_row, "visible").build();
    column.append(&cc_row);
    column.append(&bcc_row);
    let subject = entry("");
    column.append(&field("Subject", &subject));
    let chips = gtk::FlowBox::builder()
        .selection_mode(gtk::SelectionMode::None)
        .column_spacing(6)
        .row_spacing(6)
        .max_children_per_line(6)
        .margin_start(18)
        .margin_end(18)
        .margin_top(10)
        .visible(false)
        .build();
    column.append(&chips);
    let body = gtk::TextView::builder()
        .wrap_mode(gtk::WrapMode::WordChar)
        .top_margin(16)
        .bottom_margin(24)
        .left_margin(22)
        .right_margin(22)
        .accepts_tab(false)
        .vexpand(true)
        .build();
    body.add_css_class("compose-body");
    column.append(
        &gtk::ScrolledWindow::builder()
            .hscrollbar_policy(gtk::PolicyType::Never)
            .child(&body)
            .vexpand(true)
            .build(),
    );
    toolbar.set_content(Some(&column));
    toasts.set_child(Some(&toolbar));
    window.set_content(Some(&toasts));

    to.set_text(&addresses(&draft.to));
    cc.set_text(&addresses(&draft.cc));
    bcc.set_text(&addresses(&draft.bcc));
    subject.set_text(&draft.subject);
    body.buffer().set_text(&draft.text);
    let files: Vec<File> = draft.attachments.iter().cloned().map(File::Kept).collect();

    let compose = Rc::new(Compose {
        window: window.clone(),
        toasts,
        api,
        accounts,
        from,
        to: to.clone(),
        cc,
        bcc,
        subject: subject.clone(),
        body: body.clone(),
        files: RefCell::new(files),
        chips,
        send_button: send_button.clone(),
        draft: RefCell::new(draft.clone()),
        kept: RefCell::new(String::new()),
        next_upload: Cell::new(0),
        done: Cell::new(false),
    });
    compose.kept.replace(compose.fingerprint());
    compose.show_files();
    for entry in [&compose.to, &compose.cc, &compose.bcc] {
        suggest_contacts(&compose.api, entry);
    }

    subject.connect_changed(move |s| {
        let text = s.text();
        title.set_title(if text.is_empty() { "New message" } else { text.as_str() });
    });
    let c = compose.clone();
    send_button.connect_clicked(move |_| c.send());
    let c = compose.clone();
    save.connect_clicked(move |_| c.save(false));
    let c = compose.clone();
    attach.connect_clicked(move |_| c.pick_files());

    // Files dropped on the window go with it.
    let drop = gtk::DropTarget::new(gdk::FileList::static_type(), gdk::DragAction::COPY);
    let c = compose.clone();
    drop.connect_drop(move |_, value, _, _| {
        let Ok(list) = value.get::<gdk::FileList>() else { return false };
        for file in list.files() {
            c.upload(file);
        }
        true
    });
    window.add_controller(drop);

    // Before the text being written sees them (it would take Ctrl+Enter as a new line).
    let keys = gtk::ShortcutController::new();
    keys.set_propagation_phase(gtk::PropagationPhase::Capture);
    let c = compose.clone();
    keys.add_shortcut(gtk::Shortcut::new(
        gtk::ShortcutTrigger::parse_string("<Primary>Return|<Primary>KP_Enter"),
        Some(gtk::CallbackAction::new(move |_, _| {
            c.send();
            glib::Propagation::Stop
        })),
    ));
    let c = compose.clone();
    keys.add_shortcut(gtk::Shortcut::new(
        gtk::ShortcutTrigger::parse_string("<Primary>s"),
        Some(gtk::CallbackAction::new(move |_, _| {
            c.save(false);
            glib::Propagation::Stop
        })),
    ));
    let w = window.clone();
    keys.add_shortcut(gtk::Shortcut::new(
        gtk::ShortcutTrigger::parse_string("<Primary>w"),
        Some(gtk::CallbackAction::new(move |_, _| {
            w.close();
            glib::Propagation::Stop
        })),
    ));
    window.add_controller(keys);

    let c = compose.clone();
    window.connect_close_request(move |_| {
        if c.done.get() || c.fingerprint() == *c.kept.borrow() {
            return glib::Propagation::Proceed;
        }
        c.ask_to_keep();
        glib::Propagation::Stop
    });

    window.present();
    // Where to start writing: the people for something new or forwarded, else above the quote.
    if draft.to.is_empty() {
        to.grab_focus();
    } else {
        let buffer = body.buffer();
        buffer.place_cursor(&buffer.start_iter());
        body.grab_focus();
    }
}

impl Compose {
    fn account(&self) -> String {
        match &self.from {
            Some(dropdown) => self
                .accounts
                .get(dropdown.selected() as usize)
                .map(|a| a.id.clone())
                .unwrap_or_default(),
            None => self.accounts.first().map(|a| a.id.clone()).unwrap_or_default(),
        }
    }

    fn text(&self) -> String {
        let buffer = self.body.buffer();
        buffer.text(&buffer.start_iter(), &buffer.end_iter(), false).to_string()
    }

    /// Everything written, to tell whether it's changed.
    fn fingerprint(&self) -> String {
        let files: Vec<String> = self
            .files
            .borrow()
            .iter()
            .map(|f| match f {
                File::Uploading(n, _) => format!("u{n}"),
                File::Uploaded { id, .. } => id.clone(),
                File::Kept(r) => r.part.clone(),
            })
            .collect();
        [
            self.to.text().to_string(),
            self.cc.text().to_string(),
            self.bcc.text().to_string(),
            self.subject.text().to_string(),
            self.text(),
            files.join(","),
        ]
        .join("\u{1}")
    }

    fn toast(&self, text: &str) {
        let toast = adw::Toast::new(text);
        toast.set_timeout(5);
        self.toasts.add_toast(toast);
    }

    /// The message as written ([checked]: with every address making sense, and someone to send to).
    fn collect(&self, checked: bool) -> Option<Draft> {
        let mut draft = self.draft.borrow().clone();
        let mut wrong = Vec::new();
        let mut parse = |entry: &gtk::Entry| {
            let (good, bad) = Address::parse_list(&entry.text());
            wrong.extend(bad);
            good
        };
        draft.to = parse(&self.to);
        draft.cc = parse(&self.cc);
        draft.bcc = parse(&self.bcc);
        if checked {
            if let Some(bad) = wrong.first() {
                self.toast(&format!("“{bad}” isn't an email address"));
                return None;
            }
            if draft.to.is_empty() && draft.cc.is_empty() && draft.bcc.is_empty() {
                self.toast("Who's it to?");
                self.to.grab_focus();
                return None;
            }
            if self.files.borrow().iter().any(|f| matches!(f, File::Uploading(..))) {
                self.toast("A file is still uploading");
                return None;
            }
        }
        draft.account_id = self.account();
        draft.subject = self.subject.text().to_string();
        draft.text = self.text();
        draft.uploads = self
            .files
            .borrow()
            .iter()
            .filter_map(|f| match f {
                File::Uploaded { id, .. } => Some(id.clone()),
                _ => None,
            })
            .collect();
        draft.attachments = self
            .files
            .borrow()
            .iter()
            .filter_map(|f| match f {
                File::Kept(r) => Some(r.clone()),
                _ => None,
            })
            .collect();
        Some(draft)
    }

    fn send(self: &Rc<Self>) {
        if !self.send_button.is_sensitive() {
            return;
        }
        let Some(draft) = self.collect(true) else { return };
        if draft.subject.trim().is_empty() && draft.text.trim().is_empty() && draft.uploads.is_empty() && draft.attachments.is_empty() {
            self.toast("There's nothing to send yet");
            return;
        }
        self.send_button.set_sensitive(false);
        let api = self.api.clone();
        let this = self.clone();
        glib::spawn_future_local(async move {
            let result = bg(async move { api.send_mail(&draft).await }).await;
            match result {
                Ok(sent) => {
                    this.done.set(true);
                    this.window.close();
                    let ctx = ctx();
                    ctx.toast(&sent.note.unwrap_or_else(|| "Sent".into()));
                    if let Some(ui) = ctx.mail() {
                        ui.refresh();
                    }
                }
                Err(e) => {
                    this.send_button.set_sensitive(true);
                    if crate::api::is_unauthorized(&e) {
                        this.toast("Your sign-in ended: sign in again, then send it");
                    } else {
                        this.toast(&e.to_string());
                    }
                }
            }
        });
    }

    /// Keeps it in Drafts (and [then_close] the window once kept).
    fn save(self: &Rc<Self>, then_close: bool) {
        let Some(draft) = self.collect(false) else { return };
        let fingerprint = self.fingerprint();
        let api = self.api.clone();
        let this = self.clone();
        glib::spawn_future_local(async move {
            match bg(async move { api.save_draft(&draft).await }).await {
                Ok(saved) => {
                    this.draft.borrow_mut().draft_id = saved.id;
                    this.kept.replace(fingerprint);
                    if then_close {
                        this.done.set(true);
                        this.window.close();
                        ctx().toast("Kept in Drafts");
                    } else {
                        this.toast("Kept in Drafts");
                    }
                    if let Some(ui) = ctx().mail() {
                        ui.refresh();
                    }
                }
                Err(e) => this.toast(&format!("Couldn't keep it: {e}")),
            }
        });
    }

    fn ask_to_keep(self: &Rc<Self>) {
        let dialog = adw::AlertDialog::new(
            Some("Keep this message?"),
            Some("It isn't sent. You can keep it in Drafts to finish later."),
        );
        dialog.add_responses(&[("discard", "Discard"), ("cancel", "Keep writing"), ("save", "Keep in Drafts")]);
        dialog.set_response_appearance("discard", adw::ResponseAppearance::Destructive);
        dialog.set_response_appearance("save", adw::ResponseAppearance::Suggested);
        dialog.set_default_response(Some("save"));
        dialog.set_close_response("cancel");
        let this = self.clone();
        dialog.connect_response(None, move |_, response| match response {
            "discard" => {
                this.done.set(true);
                this.window.close();
            }
            "save" => this.save(true),
            _ => {}
        });
        dialog.present(Some(&self.window));
    }

    fn pick_files(self: &Rc<Self>) {
        let dialog = gtk::FileDialog::builder().title("Attach files").modal(true).build();
        let this = self.clone();
        dialog.open_multiple(Some(&self.window), gio::Cancellable::NONE, move |result| {
            let Ok(list) = result else { return };
            for i in 0..list.n_items() {
                if let Some(file) = list.item(i).and_downcast::<gio::File>() {
                    this.upload(file);
                }
            }
        });
    }

    fn upload(self: &Rc<Self>, file: gio::File) {
        let Some(path) = file.path() else {
            self.toast("Only files on this computer can be attached");
            return;
        };
        let filename = path
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "file".into());
        let n = self.next_upload.get() + 1;
        self.next_upload.set(n);
        self.files.borrow_mut().push(File::Uploading(n, filename.clone()));
        self.show_files();
        let api = self.api.clone();
        let this = self.clone();
        glib::spawn_future_local(async move {
            let name = filename.clone();
            let result = bg(async move {
                let bytes = tokio::fs::read(&path).await?;
                let size = bytes.len() as i64;
                anyhow::Ok((api.upload(&name, bytes).await?, size))
            })
            .await;
            let mut files = this.files.borrow_mut();
            let Some(at) = files.iter().position(|f| matches!(f, File::Uploading(m, _) if *m == n)) else {
                return;
            };
            match result {
                Ok((upload, size)) => {
                    files[at] = File::Uploaded {
                        id: upload.id,
                        filename: upload.filename,
                        size,
                    }
                }
                Err(e) => {
                    files.remove(at);
                    drop(files);
                    this.toast(&format!("Couldn't attach {filename}: {e}"));
                    this.show_files();
                    return;
                }
            }
            drop(files);
            this.show_files();
        });
    }

    fn show_files(self: &Rc<Self>) {
        widgets::clear(&self.chips);
        let files = self.files.borrow().clone();
        self.chips.set_visible(!files.is_empty());
        for file in &files {
            let (name, detail) = match file {
                File::Uploading(_, name) => (name.clone(), "Uploading…".to_string()),
                File::Uploaded { filename, size, .. } => (filename.clone(), widgets::file_size(*size)),
                File::Kept(r) => (
                    r.filename.clone(),
                    if r.size > 0 { widgets::file_size(r.size) } else { String::new() },
                ),
            };
            let chip = gtk::Box::builder().spacing(6).build();
            chip.add_css_class("file-chip");
            chip.append(&gtk::Image::from_icon_name("mail-attachment-symbolic"));
            let label = gtk::Label::builder()
                .label(&name)
                .ellipsize(gtk::pango::EllipsizeMode::Middle)
                .max_width_chars(26)
                .build();
            chip.append(&label);
            if !detail.is_empty() {
                let size = gtk::Label::new(Some(&detail));
                size.add_css_class("attachment-size");
                chip.append(&size);
            }
            let remove = gtk::Button::builder()
                .icon_name("window-close-symbolic")
                .tooltip_text(format!("Remove {name}"))
                .build();
            remove.add_css_class("flat");
            remove.add_css_class("circular");
            let this = self.clone();
            let file = file.clone();
            remove.connect_clicked(move |_| {
                let mut files = this.files.borrow_mut();
                let same = |f: &File| match (f, &file) {
                    (File::Uploading(a, _), File::Uploading(b, _)) => a == b,
                    (File::Uploaded { id: a, .. }, File::Uploaded { id: b, .. }) => a == b,
                    (File::Kept(a), File::Kept(b)) => a.part == b.part && a.message_id == b.message_id,
                    _ => false,
                };
                if let Some(at) = files.iter().position(same) {
                    files.remove(at);
                }
                drop(files);
                this.show_files();
            });
            chip.append(&remove);
            self.chips.append(&chip);
        }
    }
}

/// People you've written to or heard from, offered as you type an address.
fn suggest_contacts(api: &Api, entry: &gtk::Entry) {
    let list = gtk::ListBox::builder().selection_mode(gtk::SelectionMode::Browse).build();
    list.add_css_class("navigation-sidebar");
    let popover = gtk::Popover::builder()
        .child(&list)
        .autohide(false)
        .has_arrow(false)
        .position(gtk::PositionType::Bottom)
        .build();
    popover.set_parent(entry);
    let found: Rc<RefCell<Vec<Address>>> = Rc::default();
    let asked = Rc::new(Cell::new(0u64));
    let quiet = Rc::new(Cell::new(false));

    // The address being typed: after the last comma.
    fn typing(text: &str) -> (&str, &str) {
        match text.rfind([',', ';']) {
            Some(at) => (&text[..=at], text[at + 1..].trim_start()),
            None => ("", text.trim_start()),
        }
    }

    let choose = {
        let (entry, popover, found, quiet) = (entry.clone(), popover.clone(), found.clone(), quiet.clone());
        Rc::new(move |index: usize| {
            let Some(address) = found.borrow().get(index).cloned() else {
                return;
            };
            let text = entry.text().to_string();
            let (before, _) = typing(&text);
            let before = before.trim_end();
            let joined = if before.is_empty() {
                format!("{}, ", address.full())
            } else {
                format!("{before} {}, ", address.full())
            };
            quiet.set(true);
            entry.set_text(&joined);
            entry.set_position(-1);
            quiet.set(false);
            popover.popdown();
            entry.grab_focus();
        })
    };
    let c = choose.clone();
    list.connect_row_activated(move |_, row| c(row.index() as usize));

    let (api, list_c, popover_c, found_c) = (api.clone(), list.clone(), popover.clone(), found.clone());
    entry.connect_changed(move |entry| {
        if quiet.get() {
            return;
        }
        let text = entry.text().to_string();
        let (_, query) = typing(&text);
        let query = query.to_string();
        if query.chars().count() < 2 {
            popover_c.popdown();
            return;
        }
        let n = asked.get() + 1;
        asked.set(n);
        let (api, list, popover, found, asked) = (api.clone(), list_c.clone(), popover_c.clone(), found_c.clone(), asked.clone());
        glib::spawn_future_local(async move {
            // A moment's pause, so typing quickly asks once.
            glib::timeout_future(std::time::Duration::from_millis(180)).await;
            if asked.get() != n {
                return;
            }
            let Ok(contacts) = bg(async move { api.contacts(&query).await }).await else {
                return;
            };
            if asked.get() != n {
                return;
            }
            widgets::clear_list(&list);
            let people: Vec<Address> = contacts
                .into_iter()
                .take(8)
                .map(|c| Address {
                    email: c.email,
                    name: c.name,
                })
                .collect();
            for person in &people {
                let row = gtk::Box::builder()
                    .orientation(gtk::Orientation::Vertical)
                    .margin_top(4)
                    .margin_bottom(4)
                    .build();
                row.append(&gtk::Label::builder().label(person.shown()).xalign(0.0).build());
                if person.name.as_deref().is_some_and(|n| !n.trim().is_empty()) {
                    let email = gtk::Label::builder().label(&person.email).xalign(0.0).build();
                    email.add_css_class("dim-label");
                    email.add_css_class("caption");
                    row.append(&email);
                }
                list.append(&row);
            }
            let any = !people.is_empty();
            found.replace(people);
            if any {
                popover.popup();
            } else {
                popover.popdown();
            }
        });
    });

    // Down into the suggestions, Enter for the first, Escape to put them away.
    let keys = gtk::EventControllerKey::new();
    keys.set_propagation_phase(gtk::PropagationPhase::Capture);
    let (popover_k, list_k, choose_k) = (popover.clone(), list.clone(), choose.clone());
    keys.connect_key_pressed(move |_, key, _, _| {
        if !popover_k.is_visible() {
            return glib::Propagation::Proceed;
        }
        match key {
            gdk::Key::Down => {
                if let Some(row) = list_k.row_at_index(0) {
                    list_k.select_row(Some(&row));
                    row.grab_focus();
                }
                glib::Propagation::Stop
            }
            gdk::Key::Return | gdk::Key::KP_Enter | gdk::Key::Tab => {
                choose_k(0);
                glib::Propagation::Stop
            }
            gdk::Key::Escape => {
                popover_k.popdown();
                glib::Propagation::Stop
            }
            _ => glib::Propagation::Proceed,
        }
    });
    entry.add_controller(keys);
    let focus = gtk::EventControllerFocus::new();
    let (popover_f, list_f) = (popover.clone(), list.clone());
    focus.connect_leave(move |_| {
        let (popover, list) = (popover_f.clone(), list_f.clone());
        // Unless the focus went into the suggestions.
        glib::timeout_add_local_once(std::time::Duration::from_millis(150), move || {
            if !list.has_focus() && list.focus_child().is_none() {
                popover.popdown();
            }
        });
    });
    entry.add_controller(focus);
    // The popover goes with the entry.
    entry.connect_destroy(move |_| popover.unparent());
}
