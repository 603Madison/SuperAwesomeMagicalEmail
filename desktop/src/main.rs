//! SuperAwesomeMagicalEmail for the Linux desktop: your server's mail in a native GTK 4 app.

mod api;
mod config;
mod rt;
mod ui;

use adw::prelude::*;
use gtk::{gio, glib};

/// The app's id: its D-Bus name, its .desktop file, its icon, and its windows' class (for
/// Hyprland's window rules on SuperAwesomeMagicalLinux).
pub const APP_ID: &str = "io.github._603madison.SuperAwesomeMagicalEmail";

fn main() -> glib::ExitCode {
    glib::set_prgname(Some(APP_ID));
    glib::set_application_name("SuperAwesomeMagicalEmail");
    // Lexend, before any text is drawn.
    ui::theme::register_fonts();
    let app = adw::Application::builder()
        .application_id(APP_ID)
        .flags(gio::ApplicationFlags::HANDLES_OPEN)
        .build();
    app.add_main_option(
        "background",
        glib::Char::from(b'b'),
        glib::OptionFlags::NONE,
        glib::OptionArg::None,
        "Start without a window, to tell about new mail",
        None,
    );
    app.connect_handle_local_options(|app, options| {
        if options.contains("background") {
            // Already running: nothing to do (the running app is already telling about mail).
            if app.register(gio::Cancellable::NONE).is_ok() && app.is_remote() {
                return std::ops::ControlFlow::Break(glib::ExitCode::SUCCESS);
            }
            ui::start_in_background();
        }
        std::ops::ControlFlow::Continue(())
    });
    app.connect_activate(ui::activate);
    app.connect_open(|app, files, _| ui::open_uris(app, files));
    app.run()
}
