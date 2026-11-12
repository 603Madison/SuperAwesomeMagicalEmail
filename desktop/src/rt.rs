//! Network work runs on a small Tokio runtime; its results come back to the GTK main thread,
//! where the widgets are, by awaiting [`bg`] from `glib::spawn_future_local`.

use std::future::Future;
use std::sync::OnceLock;

fn runtime() -> &'static tokio::runtime::Runtime {
    static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .thread_name("same-net")
            .enable_all()
            .build()
            .expect("the network runtime")
    })
}

/// Runs [future] on the network runtime; awaiting it (on the main thread) gives its result.
pub async fn bg<F, T>(future: F) -> T
where
    F: Future<Output = T> + Send + 'static,
    T: Send + 'static,
{
    runtime().spawn(future).await.expect("a network task panicked")
}
