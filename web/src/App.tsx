import { useEffect, useState } from "react";
import { BrowserRouter, Navigate, Route, Routes, useLocation } from "react-router-dom";
import { Menu as MenuIcon } from "lucide-react";
import { session, useSession } from "./lib/session";
import { mail, useMail } from "./lib/mail";
import { Spinner, Toasts } from "./components/ui";
import { Sidebar } from "./components/Sidebar";
import { Compose } from "./components/Compose";
import { Login } from "./pages/Login";
import { ConnectApp } from "./pages/ConnectApp";
import { Mail } from "./pages/Mail";
import { Settings } from "./pages/Settings";
import { Admin } from "./pages/Admin";

export function App() {
  useEffect(() => { void session.start(); }, []);
  return (
    <BrowserRouter>
      <Root />
      <Toasts />
    </BrowserRouter>
  );
}

function Root() {
  const loading = useSession((s) => s.loading);
  const user = useSession((s) => s.user);
  const location = useLocation();
  const here = `${location.pathname}${location.search}`;

  if (loading) return <div className="splash"><Spinner size={32} /></div>;
  if (location.pathname === "/login") return user ? <Navigate to={new URLSearchParams(location.search).get("return") ?? "/"} replace /> : <Login />;
  if (!user) return <Navigate to={`/login?return=${encodeURIComponent(here)}`} replace />;
  if (location.pathname === "/connect-app") return <ConnectApp />;
  return <Shell />;
}

/** How often to ask the server about new mail while the page is open. */
const POLL_MS = 60_000;

function Shell() {
  const user = useSession((s) => s.user)!;
  const unread = useMail((s) => s.unread);
  const [navOpen, setNavOpen] = useState(false);
  const location = useLocation();

  useEffect(() => {
    void mail.loadAccounts().then(() => mail.poll());
    const timer = setInterval(() => void mail.poll(), POLL_MS);
    const onVisible = () => { if (!document.hidden) void mail.poll(); };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      clearInterval(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [user.id]);

  // The tab says how much is unread.
  const total = Object.values(unread).reduce((a, b) => a + b, 0);
  useEffect(() => {
    document.title = total > 0 ? `(${total}) SuperAwesomeMagicalEmail` : "SuperAwesomeMagicalEmail";
  }, [total]);

  useEffect(() => setNavOpen(false), [location.pathname, location.search]);

  return (
    <div className={`shell ${navOpen ? "nav-open" : ""}`}>
      <Sidebar onNavigate={() => setNavOpen(false)} />
      {navOpen && <div className="nav-backdrop" onClick={() => setNavOpen(false)} />}
      <main className="content">
        <Routes>
          <Route path="/" element={<Mail onMenu={() => setNavOpen(true)} />} />
          <Route path="/settings" element={<Page onMenu={() => setNavOpen(true)}><Settings /></Page>} />
          {user.role === "ADMIN" && <Route path="/admin" element={<Page onMenu={() => setNavOpen(true)}><Admin /></Page>} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>
      <Compose />
    </div>
  );
}

/** Settings and Admin, with a way back to the mailboxes on phones. */
function Page({ onMenu, children }: { onMenu: () => void; children: React.ReactNode }) {
  return (
    <div className="page-frame">
      <button className="icon-btn page-menu phone-only" onClick={onMenu} aria-label="Mailboxes"><MenuIcon size={20} /></button>
      {children}
    </div>
  );
}
