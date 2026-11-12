//! The app's settings: the server, the sign-in token and a few choices, as JSON in
//! `~/.config/SuperAwesomeMagicalEmail/settings.json` (readable by this user only: it holds the token).

use std::fs;
use std::io::Write;
use std::os::unix::fs::{OpenOptionsExt, PermissionsExt};
use std::path::PathBuf;

use serde::{Deserialize, Serialize};

use crate::api::User;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct Config {
    /// The server's origin, like `https://mail.example.com`.
    pub server: Option<String>,
    pub token: Option<String>,
    pub user: Option<User>,
    pub width: i32,
    pub height: i32,
    pub maximized: bool,
    /// The theme: "desktop" (SuperAwesomeMagicalLinux's own colours), aurora (the default
    /// elsewhere), violet, ocean or ember.
    pub palette: String,
    /// light, dark, or system.
    pub style: String,
    /// All text in Lexend Bold.
    pub bold_text: bool,
    /// Show images from the web in messages without asking.
    pub always_show_images: bool,
    /// Notifications for new mail while the app runs.
    pub notify: bool,
    /// Keep running without a window (and start so after signing in to the desktop), to tell
    /// about new mail.
    pub background: bool,
    /// Where the last check for new mail got to (the server's cursor).
    pub new_mail_cursor: Option<String>,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            server: None,
            token: None,
            user: None,
            width: 1240,
            height: 800,
            maximized: false,
            // On SuperAwesomeMagicalLinux, the desktop's own colours; elsewhere, Aurora.
            palette: if crate::ui::theme::desktop_scheme_path().is_some() {
                "desktop"
            } else {
                "aurora"
            }
            .into(),
            style: "system".into(),
            bold_text: false,
            always_show_images: false,
            notify: true,
            background: false,
            new_mail_cursor: None,
        }
    }
}

fn dir() -> PathBuf {
    gtk::glib::user_config_dir().join("SuperAwesomeMagicalEmail")
}

impl Config {
    pub fn load() -> Config {
        fs::read_to_string(dir().join("settings.json"))
            .ok()
            .and_then(|text| serde_json::from_str(&text).ok())
            .unwrap_or_default()
    }

    pub fn save(&self) {
        let result = (|| -> std::io::Result<()> {
            fs::create_dir_all(dir())?;
            let path = dir().join("settings.json");
            let mut file = fs::OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o600)
                .open(&path)?;
            file.set_permissions(fs::Permissions::from_mode(0o600))?;
            file.write_all(serde_json::to_string_pretty(self).unwrap_or_default().as_bytes())
        })();
        if let Err(e) = result {
            eprintln!("Couldn't save the settings: {e}");
        }
    }
}
