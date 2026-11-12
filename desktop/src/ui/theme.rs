//! The look, shared with the web app and the phone app (and SuperAwesomeMagicalMusic's desktop
//! app): Lexend, cool slate neutrals, rounded cards, and a soft glow of the theme at the top.
//! The themes are the same as theirs: Aurora (purple and green, the default), Violet, Ocean and
//! Ember, each light and dark. On SuperAwesomeMagicalLinux there's one more, "Desktop", the
//! default there: the desktop's own colour scheme (the one its shell and `caelestia scheme`
//! set), followed as it changes, light or dark as the desktop is.

use std::cell::RefCell;
use std::collections::HashMap;
use std::ffi::{CString, c_char, c_int, c_void};
use std::path::PathBuf;

use gtk::prelude::*;
use gtk::{gdk, gio, glib};

/// A theme's colours in one scheme.
#[derive(Clone)]
struct Tones {
    primary: String,
    on_primary: String,
    primary_container: String,
    on_primary_container: String,
    accent: String,
    accent_container: String,
    glow_a: String,
    glow_b: String,
}

pub struct Palette {
    pub id: &'static str,
    pub name: &'static str,
    /// The two colours shown for it in Preferences.
    pub swatch: (&'static str, &'static str),
    dark: [&'static str; 8],
    light: [&'static str; 8],
}

impl Palette {
    fn tones(&self, dark: bool) -> Tones {
        let t = if dark { self.dark } else { self.light };
        Tones {
            primary: t[0].into(),
            on_primary: t[1].into(),
            primary_container: t[2].into(),
            on_primary_container: t[3].into(),
            accent: t[4].into(),
            accent_container: t[5].into(),
            glow_a: t[6].into(),
            glow_b: t[7].into(),
        }
    }
}

/// primary, on primary, primary container, on primary container, accent, accent container, glows.
pub const PALETTES: [Palette; 4] = [
    Palette {
        id: "aurora",
        name: "Aurora",
        swatch: ("#6a3fd6", "#12a37c"),
        dark: [
            "#cdbdff", "#37197f", "#4f2fb8", "#e8deff", "#62e0b3", "#00513b", "#5b3fd1", "#12a37c",
        ],
        light: [
            "#6a3fd6", "#ffffff", "#e8deff", "#22005d", "#00805e", "#b5f2d9", "#b9a6ff", "#8fe6c8",
        ],
    },
    Palette {
        id: "violet",
        name: "Violet",
        swatch: ("#5b3fd1", "#8e3f5c"),
        dark: [
            "#cfbdff", "#341a86", "#4b33a8", "#e9ddff", "#ffb1c8", "#7b2949", "#5b3fd1", "#8e3f5c",
        ],
        light: [
            "#5b3fd1", "#ffffff", "#e9ddff", "#1d0060", "#8e3f5c", "#ffd9e2", "#c8b8ff", "#ffc2d4",
        ],
    },
    Palette {
        id: "ocean",
        name: "Ocean",
        swatch: ("#1d5fc9", "#0e8fa3"),
        dark: [
            "#a6c8ff", "#00315e", "#1f4f8f", "#d5e3ff", "#5dd5e8", "#004f58", "#1d5fc9", "#0e8fa3",
        ],
        light: [
            "#1d5fc9", "#ffffff", "#d8e6ff", "#001b3f", "#00788a", "#a8eefc", "#a9c7ff", "#93e3ef",
        ],
    },
    Palette {
        id: "ember",
        name: "Ember",
        swatch: ("#b4471a", "#9c3f60"),
        dark: [
            "#ffb59a", "#5a1c00", "#8a3410", "#ffdbcf", "#ffb1c8", "#7b2949", "#c2410c", "#9c3f60",
        ],
        light: [
            "#b4471a", "#ffffff", "#ffdbcf", "#3a0b00", "#9c3f60", "#ffd9e2", "#ffc4ab", "#ffc2d4",
        ],
    },
];

pub fn palette(id: &str) -> &'static Palette {
    PALETTES.iter().find(|p| p.id == id).unwrap_or(&PALETTES[0])
}

/// The slate neutrals of the family's apps (navy in the dark).
#[derive(Clone)]
struct Neutrals {
    bg: String,
    sidebar: String,
    card: String,
    raised: String,
    text: String,
    muted: String,
    faint: String,
    outline: String,
    shade: String,
    surface2: String,
    surface3: String,
    surface4: String,
    glow_strength: (f32, f32),
}

fn slate(dark: bool) -> Neutrals {
    let s = |v: &str| v.to_string();
    if dark {
        Neutrals {
            bg: s("#0b1220"),
            sidebar: s("#0f1828"),
            card: s("#131d30"),
            raised: s("#1a2539"),
            text: s("#e5e9f0"),
            muted: s("#a9b4c6"),
            faint: s("#7d889c"),
            outline: s("#263349"),
            shade: s("rgba(0, 0, 0, 0.36)"),
            surface2: s("#131d30"),
            surface3: s("#1a2539"),
            surface4: s("#223047"),
            glow_strength: (0.40, 0.28),
        }
    } else {
        Neutrals {
            bg: s("#f5f7fb"),
            sidebar: s("#eef2f8"),
            card: s("#ffffff"),
            raised: s("#ffffff"),
            text: s("#111827"),
            muted: s("#4b5565"),
            faint: s("#6b7585"),
            outline: s("#dde3ec"),
            shade: s("rgba(17, 24, 39, 0.10)"),
            surface2: s("#e8edf5"),
            surface3: s("#e1e7f0"),
            surface4: s("#d8dfea"),
            glow_strength: (0.80, 0.70),
        }
    }
}

// ---- SuperAwesomeMagicalLinux's colour scheme ----

/// Where SuperAwesomeMagicalLinux's desktop keeps its colour scheme, if this is one.
pub fn desktop_scheme_path() -> Option<PathBuf> {
    let state = std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .filter(|p| p.is_absolute())
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/state")))?;
    let path = state.join("caelestia").join("scheme.json");
    path.is_file().then_some(path)
}

/// The desktop's scheme: Material colour names (without '#') and whether it's light.
pub struct DesktopScheme {
    pub light: bool,
    colours: HashMap<String, String>,
}

impl DesktopScheme {
    pub fn read() -> Option<DesktopScheme> {
        Self::parse(&std::fs::read_to_string(desktop_scheme_path()?).ok()?)
    }

    pub fn parse(text: &str) -> Option<DesktopScheme> {
        let json: serde_json::Value = serde_json::from_str(text).ok()?;
        let colours = json
            .get("colours")?
            .as_object()?
            .iter()
            .filter_map(|(k, v)| {
                let hex = v.as_str()?.trim_start_matches('#');
                (hex.len() == 6 && hex.chars().all(|c| c.is_ascii_hexdigit())).then(|| (k.clone(), format!("#{hex}")))
            })
            .collect::<HashMap<_, _>>();
        colours.contains_key("primary").then(|| DesktopScheme {
            light: json.get("mode").and_then(|m| m.as_str()) == Some("light"),
            colours,
        })
    }

    fn get(&self, name: &str, fallback: &str) -> String {
        self.colours.get(name).cloned().unwrap_or_else(|| fallback.to_string())
    }

    fn tones(&self, fallback: &Tones) -> Tones {
        Tones {
            primary: self.get("primary", &fallback.primary),
            on_primary: self.get("onPrimary", &fallback.on_primary),
            primary_container: self.get("primaryContainer", &fallback.primary_container),
            on_primary_container: self.get("onPrimaryContainer", &fallback.on_primary_container),
            accent: self.get("secondary", &fallback.accent),
            accent_container: self.get("secondaryContainer", &fallback.accent_container),
            glow_a: self.get("primary", &fallback.glow_a),
            glow_b: self.get("secondary", &fallback.glow_b),
        }
    }

    fn neutrals(&self, fallback: &Neutrals) -> Neutrals {
        Neutrals {
            bg: self.get("background", &fallback.bg),
            sidebar: self.get("surfaceContainerLow", &fallback.sidebar),
            card: self.get("surfaceContainer", &fallback.card),
            raised: self.get("surfaceContainerHigh", &fallback.raised),
            text: self.get("onSurface", &fallback.text),
            muted: self.get("onSurfaceVariant", &fallback.muted),
            faint: self.get("outline", &fallback.faint),
            outline: self.get("outlineVariant", &fallback.outline),
            shade: fallback.shade.clone(),
            surface2: self.get("surfaceContainer", &fallback.surface2),
            surface3: self.get("surfaceContainerHigh", &fallback.surface3),
            surface4: self.get("surfaceContainerHighest", &fallback.surface4),
            glow_strength: if self.light { (0.45, 0.35) } else { (0.30, 0.22) },
        }
    }
}

/// The colours avatars are drawn in (the same as the web app's and the phone app's).
pub const AVATARS: [&str; 10] = [
    "#6a3fd6", "#0f9d77", "#1d5fc9", "#b4471a", "#8e3f5c", "#0e8fa3", "#7c5b12", "#4f6b1f", "#a3416a", "#3d57b3",
];

/// The avatar colour for [seed] (an address), as the web app picks it.
pub fn avatar_class(seed: &str) -> String {
    let mut hash: i32 = 0;
    for c in seed.encode_utf16() {
        hash = hash.wrapping_mul(31).wrapping_add(c as i32);
    }
    format!("avatar-{}", hash.unsigned_abs() as usize % AVATARS.len())
}

/// libadwaita's colours set to the theme, and the family's styles over them.
fn css(t: &Tones, n: &Neutrals, bold: bool) -> String {
    let (glow_a, glow_b) = n.glow_strength;
    let weight = |w: u32| if bold { 700 } else { w };
    let avatars: String = AVATARS
        .iter()
        .enumerate()
        .map(|(i, c)| format!(".avatar-{i} {{ background: {c}; }}\n"))
        .collect();
    let swatches: String = PALETTES
        .iter()
        .map(|p| {
            format!(
                ".swatch-{} {{ background-image: linear-gradient(135deg, {} 50%, {} 50%); }}\n",
                p.id, p.swatch.0, p.swatch.1
            )
        })
        .collect();
    format!(
        r#"
@define-color accent_color {primary};
@define-color accent_bg_color {primary};
@define-color accent_fg_color {on_primary};
@define-color window_bg_color {bg};
@define-color window_fg_color {text};
@define-color view_bg_color {bg};
@define-color view_fg_color {text};
@define-color headerbar_bg_color {bg};
@define-color headerbar_fg_color {text};
@define-color headerbar_backdrop_color {bg};
@define-color headerbar_border_color {outline};
@define-color headerbar_shade_color transparent;
@define-color sidebar_bg_color {sidebar};
@define-color sidebar_fg_color {text};
@define-color sidebar_backdrop_color {sidebar};
@define-color sidebar_shade_color {shade};
@define-color secondary_sidebar_bg_color {bg};
@define-color secondary_sidebar_fg_color {text};
@define-color secondary_sidebar_backdrop_color {bg};
@define-color secondary_sidebar_shade_color {shade};
@define-color card_bg_color {card};
@define-color card_fg_color {text};
@define-color card_shade_color {shade};
@define-color popover_bg_color {raised};
@define-color popover_fg_color {text};
@define-color dialog_bg_color {raised};
@define-color dialog_fg_color {text};
@define-color shade_color {shade};

window, dialog, popover, tooltip {{ font-family: "Lexend", "Cantarell", sans-serif; font-weight: {w400}; }}
window, tooltip {{ font-size: 11.25pt; }}
.heading, label.title, .title-1, .title-2, .title-3, .title-4, button {{ font-weight: {w600}; }}
{bold_labels}

/* Every page: the background, with two soft glows of the theme at the top, like an aurora. */
.page-bg {{
  background-color: {bg};
  background-image:
    radial-gradient(ellipse 900px 400px at 12% -120px, alpha({glow_a_color}, {glow_a}), alpha({glow_a_color}, 0) 70%),
    radial-gradient(ellipse 800px 380px at 88% -140px, alpha({glow_b_color}, {glow_b}), alpha({glow_b_color}, 0) 70%);
}}
.page-bg headerbar {{ background: none; box-shadow: none; }}

/* The sidebar, as on the web: the brand, writing, then each account's folders as pills. */
.brand-name {{ font-weight: 700; font-size: 0.95em; line-height: 1.1; }}
.write-button {{ min-height: 46px; border-radius: 999px; margin: 6px 12px 12px; font-weight: 700; box-shadow: 0 8px 22px alpha({primary}, 0.28); }}
.nav-list {{ background: none; }}
.nav-list > row {{ border-radius: 999px; margin: 1px 10px; padding: 8px 14px; color: {muted}; font-weight: {w500}; }}
.nav-list > row:hover {{ background: alpha(currentColor, 0.06); color: {text}; }}
.nav-list > row:selected {{ background: {primary_container}; color: {on_primary_container}; font-weight: {w600}; }}
.nav-list > row.heading-row {{ margin-top: 14px; padding: 4px 14px; color: {faint}; font-size: 0.74em; font-weight: 700; letter-spacing: 0.07em; background: none; }}
.count {{ color: {primary}; font-weight: 700; font-size: 0.82em; }}
.nav-list > row:selected .count {{ color: inherit; }}
.count.quiet {{ color: {faint}; font-weight: {w500}; }}
.sidebar-user {{ color: {faint}; font-size: 0.85em; margin: 12px 24px; }}
.problem {{ color: @error_color; }}

/* The messages, as the web app lists them. */
.message-list {{ background: none; }}
.message-list > row {{ border-radius: 16px; margin: 1px 8px; padding: 10px 10px 10px 6px; }}
.message-list > row:hover {{ background: {surface2}; }}
.message-list > row:selected {{ background: {primary_container}; color: {on_primary_container}; }}
.message-from {{ font-weight: {w500}; }}
.unread .message-from, .unread .message-subject {{ font-weight: 700; }}
.message-subject {{ font-size: 0.95em; }}
.message-preview {{ color: {muted}; font-size: 0.86em; }}
.message-date {{ color: {faint}; font-size: 0.8em; }}
.message-list > row:selected .message-preview, .message-list > row:selected .message-date {{ color: inherit; opacity: 0.85; }}
.unread-dot {{ min-width: 7px; min-height: 7px; border-radius: 9999px; background: {primary}; }}
.message-list > row:selected .unread-dot {{ background: {on_primary_container}; }}
.account-tag {{ color: {muted}; background: {surface3}; border-radius: 999px; padding: 0 7px; font-weight: {w600}; font-size: 0.72em; }}
.message-list > row:selected .account-tag {{ color: inherit; background: alpha(currentColor, 0.14); }}
.starred {{ color: #f2b01e; }}
.search-field {{ border-radius: 999px; min-height: 42px; padding: 0 16px; background: {surface3}; box-shadow: none; }}
.list-title {{ font-size: 1.5em; font-weight: 700; letter-spacing: -0.01em; }}
.list-sub {{ color: {muted}; font-size: 0.85em; }}

/* People's circles. */
.avatar {{ border-radius: 9999px; color: #ffffff; font-weight: 700; }}
{avatars}

/* Reading. */
.reader-subject {{ font-size: 1.55em; font-weight: 700; letter-spacing: -0.01em; }}
.reader-meta {{ color: {muted}; font-size: 0.88em; }}
.paper {{ border-radius: 18px; background: #ffffff; box-shadow: 0 0 0 1px alpha({outline}, 0.6); }}
.images-banner {{ border-radius: 14px; background: {surface2}; color: {muted}; padding: 6px 6px 6px 14px; }}
.attachment {{ border-radius: 14px; background: {surface2}; padding: 8px 12px; }}
.attachment:hover {{ background: {surface3}; }}
.attachment-size {{ color: {faint}; font-size: 0.8em; }}

/* Writing. */
.compose-field {{ border-bottom: 1px solid alpha({outline}, 0.9); min-height: 44px; }}
.compose-label {{ color: {faint}; min-width: 64px; }}
.compose-entry, .compose-entry:focus-within {{ background: none; box-shadow: none; border: none; outline: none; }}
.compose-body {{ background: none; font-size: 1.02em; }}
.compose-body text {{ background: none; }}
.file-chip {{ border-radius: 999px; background: {surface4}; padding: 2px 4px 2px 12px; }}

/* Buttons, as the web app's: pills, grey ("tonal") and text-only in the theme's colour. */
button.pill {{ min-height: 40px; padding: 0 18px; border-radius: 999px; font-weight: {w600}; }}
button.tonal {{ background: {surface4}; color: {text}; }}
button.tonal:hover {{ background: mix({surface4}, {primary}, 0.2); }}

/* Empty pages, and signing in. */
.empty-icon {{ min-width: 72px; min-height: 72px; border-radius: 9999px; background: {primary_container}; color: {on_primary_container}; }}
.empty-title {{ font-size: 1.25em; font-weight: 700; }}
.empty-text {{ color: {muted}; }}
.login-card {{ background: {card}; border-radius: 28px; padding: 34px 32px; box-shadow: 0 12px 40px {shade}; }}
.login-title {{ font-size: 1.6em; font-weight: 700; letter-spacing: -0.01em; }}
.login-sub {{ color: {muted}; }}

/* Preferences: the themes, as two-colour swatches. */
.swatch {{ min-width: 30px; min-height: 30px; border-radius: 9999px; }}
.swatch-desktop {{ background-image: linear-gradient(135deg, {primary} 50%, {accent} 50%); }}
.theme-choice {{ border-radius: 999px; padding: 6px 14px 6px 6px; }}
.theme-choice:checked {{ background: {primary_container}; color: {on_primary_container}; }}
{swatches}
"#,
        primary = t.primary,
        on_primary = t.on_primary,
        primary_container = t.primary_container,
        on_primary_container = t.on_primary_container,
        accent = t.accent,
        glow_a_color = t.glow_a,
        glow_b_color = t.glow_b,
        bg = n.bg,
        sidebar = n.sidebar,
        card = n.card,
        raised = n.raised,
        text = n.text,
        muted = n.muted,
        faint = n.faint,
        outline = n.outline,
        shade = n.shade,
        surface2 = n.surface2,
        surface3 = n.surface3,
        surface4 = n.surface4,
        w400 = weight(400),
        w500 = weight(500),
        w600 = weight(600),
        bold_labels = if bold { "label, text, entry { font-weight: 700; }" } else { "" },
    )
}

/// The colours for plain-text messages, which are drawn in the app's own look.
pub struct ReadingColours {
    pub text: String,
    pub muted: String,
    pub link: String,
    pub background: String,
}

thread_local! {
    static PROVIDER: RefCell<Option<gtk::CssProvider>> = const { RefCell::new(None) };
    static MONITOR: RefCell<Option<gio::FileMonitor>> = const { RefCell::new(None) };
    static CURRENT: RefCell<(String, String, bool)> = RefCell::new(("aurora".into(), "system".into(), false));
    static LISTENERS: RefCell<Vec<Box<dyn Fn()>>> = RefCell::new(Vec::new());
}

/// Tells [listener] whenever the theme changes (so message views can redraw plain text).
pub fn on_change(listener: impl Fn() + 'static) {
    LISTENERS.with(|l| l.borrow_mut().push(Box::new(listener)));
}

/// The colours plain-text messages are drawn in now.
pub fn reading_colours() -> ReadingColours {
    let (palette_id, _, _) = CURRENT.with(|c| c.borrow().clone());
    let dark = adw::StyleManager::default().is_dark();
    let (t, n) = resolve(&palette_id, dark);
    ReadingColours {
        text: n.text,
        muted: n.muted,
        link: t.primary,
        background: n.bg,
    }
}

fn resolve(palette_id: &str, dark: bool) -> (Tones, Neutrals) {
    if palette_id == "desktop"
        && let Some(scheme) = DesktopScheme::read()
    {
        let base = palette("aurora");
        return (scheme.tones(&base.tones(!scheme.light)), scheme.neutrals(&slate(!scheme.light)));
    }
    (palette(palette_id).tones(dark), slate(dark))
}

/// Applies the theme ([palette], light/dark/system [style], [bold]), and keeps it right when the
/// system's dark style, or SuperAwesomeMagicalLinux's colour scheme, changes.
pub fn apply(palette_id: &str, style: &str, bold: bool) {
    CURRENT.with(|c| c.replace((palette_id.to_string(), style.to_string(), bold)));
    let manager = adw::StyleManager::default();
    let desktop = if palette_id == "desktop" { DesktopScheme::read() } else { None };
    manager.set_color_scheme(match (desktop.as_ref().map(|d| d.light), style) {
        // The desktop's scheme says light or dark.
        (Some(true), _) => adw::ColorScheme::ForceLight,
        (Some(false), _) => adw::ColorScheme::ForceDark,
        (None, "light") => adw::ColorScheme::ForceLight,
        (None, "dark") => adw::ColorScheme::ForceDark,
        _ => adw::ColorScheme::Default,
    });
    let Some(display) = gdk::Display::default() else { return };
    let provider = PROVIDER.with(|p| {
        p.borrow_mut()
            .get_or_insert_with(|| {
                let provider = gtk::CssProvider::new();
                gtk::style_context_add_provider_for_display(&display, &provider, gtk::STYLE_PROVIDER_PRIORITY_APPLICATION);
                manager.connect_dark_notify(|_| reload());
                watch_desktop_scheme();
                provider
            })
            .clone()
    });
    let (t, n) = resolve(palette_id, manager.is_dark());
    provider.load_from_string(&css(&t, &n, bold));
    LISTENERS.with(|l| l.borrow().iter().for_each(|f| f()));
}

fn reload() {
    let (palette_id, style, bold) = CURRENT.with(|c| c.borrow().clone());
    apply(&palette_id, &style, bold);
}

/// SuperAwesomeMagicalLinux's colour scheme changing (from its dashboard or `caelestia scheme
/// set`) changes the app's colours too, when it follows the desktop.
fn watch_desktop_scheme() {
    let Some(path) = desktop_scheme_path() else { return };
    let Ok(monitor) = gio::File::for_path(&path).monitor_file(gio::FileMonitorFlags::NONE, gio::Cancellable::NONE) else {
        return;
    };
    monitor.connect_changed(|_, _, _, event| {
        if matches!(event, gio::FileMonitorEvent::ChangesDoneHint | gio::FileMonitorEvent::Created)
            && CURRENT.with(|c| c.borrow().0 == "desktop")
        {
            glib::idle_add_local_once(reload);
        }
    });
    MONITOR.with(|m| m.replace(Some(monitor)));
}

#[link(name = "fontconfig")]
unsafe extern "C" {
    fn FcConfigAppFontAddFile(config: *mut c_void, file: *const c_char) -> c_int;
}

/// Lexend (SIL Open Font License), for this app only (SuperAwesomeMagicalLinux has it already):
/// added to fontconfig before any text is drawn, from copies the app keeps in its cache folder.
pub fn register_fonts() {
    let fonts: [(&str, &[u8]); 4] = [
        ("Lexend-Regular.ttf", include_bytes!("../../data/fonts/lexend_regular.ttf")),
        ("Lexend-Medium.ttf", include_bytes!("../../data/fonts/lexend_medium.ttf")),
        ("Lexend-SemiBold.ttf", include_bytes!("../../data/fonts/lexend_semibold.ttf")),
        ("Lexend-Bold.ttf", include_bytes!("../../data/fonts/lexend_bold.ttf")),
    ];
    let dir = glib::user_cache_dir().join("SuperAwesomeMagicalEmail").join("fonts");
    if std::fs::create_dir_all(&dir).is_err() {
        return;
    }
    for (name, bytes) in fonts {
        let path = dir.join(name);
        let fresh = std::fs::metadata(&path).map(|m| m.len() == bytes.len() as u64).unwrap_or(false);
        if !fresh && std::fs::write(&path, bytes).is_err() {
            continue;
        }
        if let Ok(file) = CString::new(path.to_string_lossy().as_bytes()) {
            // SAFETY: a null config is fontconfig's current one; the path is a valid C string.
            unsafe {
                FcConfigAppFontAddFile(std::ptr::null_mut(), file.as_ptr());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_theme_makes_a_stylesheet_in_both_schemes() {
        for p in &PALETTES {
            for dark in [false, true] {
                let sheet = css(&p.tones(dark), &slate(dark), false);
                assert!(sheet.contains("@define-color accent_bg_color"), "{} {dark}", p.id);
                assert!(sheet.contains(&format!(".swatch-{}", p.id)));
                assert!(!sheet.contains("{{"), "unformatted braces in {}", p.id);
            }
        }
        assert_eq!(palette("nope").id, "aurora");
    }

    #[test]
    fn superawesomemagicallinux_schemes_are_followed() {
        // As `caelestia scheme set` writes it (SuperAwesomeMagicalLinux's default scheme, trimmed).
        let scheme = DesktopScheme::parse(
            r#"{"name":"superawesomemagical","flavour":"default","mode":"dark","variant":"tonalspot",
                "colours":{"primary":"cfbdff","onPrimary":"341a86","primaryContainer":"4b33a8","onPrimaryContainer":"e9ddff",
                "secondary":"6ef2b0","secondaryContainer":"005233","background":"0b1220","onSurface":"e5e9f0","bogus":"zz"}}"#,
        )
        .unwrap();
        assert!(!scheme.light);
        let tones = scheme.tones(&palette("aurora").tones(true));
        assert_eq!(tones.primary, "#cfbdff");
        assert_eq!(tones.accent, "#6ef2b0");
        let n = scheme.neutrals(&slate(true));
        assert_eq!(n.bg, "#0b1220");
        // What it doesn't give comes from the family's own slate.
        assert_eq!(n.sidebar, "#0f1828");
        assert!(css(&tones, &n, false).contains("@define-color accent_bg_color #cfbdff;"));
        assert!(DesktopScheme::parse(r#"{"mode":"light","colours":{}}"#).is_none());
        assert!(DesktopScheme::parse("not json").is_none());
    }

    #[test]
    fn avatars_match_the_web_app() {
        // Checked against web/src/lib/format.ts avatarColour().
        assert_eq!(avatar_class("?"), "avatar-3");
        assert!(avatar_class("jo.norton@example.com").starts_with("avatar-"));
    }
}
