//! Small pieces the screens share: circles for people, empty states, dates and sizes.

use adw::prelude::*;
use gtk::glib;

use crate::api::Address;

pub fn clear(container: &impl IsA<gtk::Widget>) {
    let container = container.as_ref();
    while let Some(child) = container.first_child() {
        child.unparent();
    }
}

pub fn clear_list(list: &gtk::ListBox) {
    while let Some(child) = list.first_child() {
        list.remove(&child);
    }
}

pub fn clear_box(holder: &gtk::Box) {
    while let Some(child) = holder.first_child() {
        holder.remove(&child);
    }
}

/// The first letter of a name, for its circle.
pub fn initial(address: Option<&Address>) -> String {
    let shown = address.map(Address::shown).unwrap_or_default();
    shown
        .chars()
        .find(|c| c.is_alphanumeric())
        .map(|c| c.to_uppercase().collect())
        .unwrap_or_else(|| "?".into())
}

/// A person's circle: their initial, in the colour the web app gives them.
pub fn avatar(address: Option<&Address>, size: i32) -> gtk::Widget {
    let seed = address.map(|a| a.email.to_lowercase()).unwrap_or_default();
    let label = gtk::Label::builder()
        .label(initial(address))
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .hexpand(true)
        .build();
    let circle = gtk::Box::builder()
        .width_request(size)
        .height_request(size)
        .valign(gtk::Align::Start)
        .halign(gtk::Align::Center)
        // Its letter fills it; the circle itself stays its size.
        .hexpand(false)
        .build();
    circle.add_css_class("avatar");
    circle.add_css_class(&crate::ui::theme::avatar_class(&seed));
    let attrs = gtk::pango::AttrList::new();
    attrs.insert(gtk::pango::AttrSize::new(size * gtk::pango::SCALE * 2 / 5));
    label.set_attributes(Some(&attrs));
    circle.append(&label);
    circle.upcast()
}

/// An empty page's circle, title and line, as the web app's.
pub fn status(icon: &str, title: &str, description: &str) -> gtk::Box {
    let empty = gtk::Box::builder()
        .orientation(gtk::Orientation::Vertical)
        .spacing(10)
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .vexpand(true)
        .hexpand(true)
        .margin_top(48)
        .margin_bottom(48)
        .margin_start(24)
        .margin_end(24)
        .build();
    let circle = gtk::Box::builder().halign(gtk::Align::Center).build();
    circle.add_css_class("empty-icon");
    circle.append(
        &gtk::Image::builder()
            .icon_name(icon)
            .pixel_size(32)
            .hexpand(true)
            .vexpand(true)
            .build(),
    );
    empty.append(&circle);
    let name = gtk::Label::builder()
        .label(title)
        .wrap(true)
        .justify(gtk::Justification::Center)
        .build();
    name.add_css_class("empty-title");
    empty.append(&name);
    if !description.is_empty() {
        let line = gtk::Label::builder()
            .label(description)
            .wrap(true)
            .max_width_chars(52)
            .justify(gtk::Justification::Center)
            .build();
        line.add_css_class("empty-text");
        empty.append(&line);
    }
    empty
}

pub fn loading() -> gtk::Widget {
    let spinner = gtk::Spinner::builder()
        .spinning(true)
        .width_request(32)
        .height_request(32)
        .halign(gtk::Align::Center)
        .valign(gtk::Align::Center)
        .vexpand(true)
        .hexpand(true)
        .build();
    spinner.upcast()
}

pub fn plain_row(title: &str, subtitle: &str) -> adw::ActionRow {
    let row = adw::ActionRow::new();
    row.set_use_markup(false);
    row.set_title(title);
    row.set_subtitle(subtitle);
    row.set_title_lines(2);
    row.set_subtitle_lines(1);
    row
}

fn local(ms: i64) -> Option<glib::DateTime> {
    glib::DateTime::from_unix_local(ms / 1000).ok()
}

/// A message's date in a list: the time today, the weekday this week, the day this year, else
/// the date (as the web app shows it).
pub fn short_date(ms: i64) -> String {
    if ms <= 0 {
        return String::new();
    }
    let (Some(date), Ok(now)) = (local(ms), glib::DateTime::now_local()) else {
        return String::new();
    };
    let same_day = date.ymd() == now.ymd();
    let format = if same_day {
        "%-l:%M %p"
    } else {
        let days = (now.to_unix() - date.to_unix()) as f64 / 86_400.0;
        if days > 0.0 && days < 6.0 {
            "%a"
        } else if date.year() == now.year() {
            "%-d %b"
        } else {
            "%-d %b %Y"
        }
    };
    date.format(format).map(|s| s.trim().to_string()).unwrap_or_default()
}

/// A message's date in full, for reading it.
pub fn full_date(ms: i64) -> String {
    if ms <= 0 {
        return String::new();
    }
    local(ms)
        .and_then(|d| d.format("%a %-d %b %Y, %-l:%M %p").ok())
        .map(|s| s.to_string())
        .unwrap_or_default()
}

pub fn file_size(bytes: i64) -> String {
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{} KB", (bytes as f64 / 1024.0).round() as i64)
    } else if bytes < 10 * 1024 * 1024 {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    } else {
        format!("{} MB", (bytes as f64 / (1024.0 * 1024.0)).round() as i64)
    }
}

/// The local time zone's name, for replies' "On … wrote".
pub fn zone() -> String {
    std::env::var("TZ")
        .ok()
        .filter(|z| !z.is_empty() && !z.starts_with(':'))
        .or_else(|| {
            std::fs::read_link("/etc/localtime")
                .ok()
                .and_then(|p| p.to_str().and_then(|s| s.split("zoneinfo/").nth(1).map(str::to_string)))
        })
        .unwrap_or_else(|| "UTC".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn initials_and_sizes() {
        assert_eq!(
            initial(Some(&Address {
                email: "jo@example.com".into(),
                name: Some("  émile".into())
            })),
            "É"
        );
        assert_eq!(initial(Some(&Address::new("\"x\"@example.com"))), "X");
        assert_eq!(initial(None), "?");
        assert_eq!(file_size(512), "512 B");
        assert_eq!(file_size(2048), "2 KB");
        assert_eq!(file_size(3 * 1024 * 1024 / 2), "1.5 MB");
        assert_eq!(file_size(25 * 1024 * 1024), "25 MB");
    }
}
