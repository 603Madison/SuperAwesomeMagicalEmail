import { useState } from "react";
import { AlertTriangle, Copy, LogOut, Monitor, Pencil, Plus, SlidersHorizontal, Smartphone, Trash2, Zap } from "lucide-react";
import { api } from "../lib/api";
import { mail, useMail } from "../lib/mail";
import { PALETTES, usePrefs } from "../lib/prefs";
import { session, useSession } from "../lib/session";
import { when } from "../lib/format";
import type { MailAccount, ProviderPreset, Providers } from "../lib/types";
import { AccountDialog, ProviderBadge, ProviderPicker } from "../components/AccountDialog";
import { Button, Field, IconButton, Modal, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";

export function Settings() {
  const user = useSession((s) => s.user)!;
  return (
    <div className="page-scroll">
      <div className="page settings">
        <PageHeader title="Settings" />

        <section className="panel">
          <h2>Mail accounts</h2>
          <Accounts />
        </section>

        <section className="panel">
          <h2>Reading</h2>
          <ReadingSettings />
        </section>

        <section className="panel">
          <h2>Appearance in this browser</h2>
          <AppearanceSettings />
        </section>

        <section className="panel">
          <h2>The apps</h2>
          <Apps />
        </section>

        <section className="panel">
          <h2>Your account</h2>
          <NameForm />
          {user.hasPassword && <PasswordChange />}
          {user.oidc && !user.hasPassword && <p className="muted">You sign in through single sign-on; your password is managed there.</p>}
        </section>

        <section className="panel">
          <h2>Signed in on</h2>
          <SignIns />
        </section>
      </div>
    </div>
  );
}

function Accounts() {
  const accounts = useMail((s) => s.accounts);
  const providers = useLoad(() => api.providers(), []);
  const [picking, setPicking] = useState(false);
  const [editing, setEditing] = useState<{ preset: ProviderPreset; account?: MailAccount } | null>(null);
  const [signing, setSigning] = useState<MailAccount | null>(null);
  const [removing, setRemoving] = useState<MailAccount | null>(null);
  if (!accounts) return <Spinner />;
  const presetOf = (p: Providers | undefined, account: MailAccount) => p?.presets.find((x) => x.provider === account.provider);

  return (
    <div className="form-stack">
      {accounts.length === 0 && <p className="muted">No accounts yet.</p>}
      <ul className="source-list">
        {accounts.map((a) => (
          <li key={a.id} className="source-row">
            <ProviderBadge provider={a.provider} label={presetOf(providers.data, a)?.label ?? a.email} />
            <div className="source-text">
              <div className="source-name">{a.label || a.email}{a.managed && <span className="badge">From your admin</span>}</div>
              <div className="source-sub">{a.email}{a.name ? ` · sent as ${a.name}` : ""}</div>
              {a.problem && <div className="source-problem"><AlertTriangle size={13} /> {a.problem}</div>}
            </div>
            <div className="source-actions">
              <IconButton label="Check it signs in" onClick={async () => {
                try {
                  const check = await api.checkSavedAccount(a.id);
                  toast(check.ok ? "It signs in fine" : `${check.imap} · ${check.smtp}`, check.ok ? "info" : "error");
                } catch (e) { errorToast(e); }
              }}><Zap size={18} /></IconButton>
              <IconButton label="Signature" onClick={() => setSigning(a)}><Pencil size={18} /></IconButton>
              {a.editable && !a.managed && providers.data && (
                <IconButton label="Change its settings" onClick={() => setEditing({ preset: presetOf(providers.data, a)!, account: a })}><SlidersHorizontal size={18} /></IconButton>
              )}
              {!a.managed && <IconButton label="Remove" onClick={() => setRemoving(a)}><Trash2 size={18} /></IconButton>}
            </div>
          </li>
        ))}
      </ul>
      {providers.data?.canAddPersonal ? (
        <div><Button onClick={() => setPicking(true)}><Plus size={18} /> Add an account</Button></div>
      ) : providers.data && <p className="muted">This server's admins set up the mail accounts.</p>}

      {picking && providers.data && (
        <ProviderPicker title="Add an account" presets={providers.data.presets} onClose={() => setPicking(false)} onPick={(preset) => { setPicking(false); setEditing({ preset }); }} />
      )}
      {editing && (
        <AccountDialog
          preset={editing.preset}
          existing={editing.account}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); toast("Saved"); void mail.loadAccounts(); }}
        />
      )}
      {signing && <SignatureDialog account={signing} onClose={() => setSigning(null)} />}
      {removing && (
        <Modal title={`Remove ${removing.email}?`} onClose={() => setRemoving(null)} footer={<>
          <Button variant="ghost" onClick={() => setRemoving(null)}>Cancel</Button>
          <Button variant="danger" onClick={async () => {
            try { await api.removeAccount(removing.id); setRemoving(null); void mail.loadAccounts(); } catch (e) { errorToast(e); }
          }}>Remove</Button>
        </>}>
          <p>Its mail stays where it is, at {removing.imap.host}; this server just stops showing it and forgets its password.</p>
        </Modal>
      )}
    </div>
  );
}

function SignatureDialog({ account, onClose }: { account: MailAccount; onClose: () => void }) {
  const [signature, setSignature] = useState(account.signature);
  const [busy, setBusy] = useState(false);
  return (
    <Modal title={`Signature for ${account.email}`} onClose={onClose} footer={<>
      <Button variant="ghost" onClick={onClose}>Cancel</Button>
      <Button variant="primary" busy={busy} onClick={async () => {
        setBusy(true);
        try { await api.setSignature(account.id, signature); toast("Saved"); void mail.loadAccounts(); onClose(); } catch (e) { errorToast(e); setBusy(false); }
      }}>Save</Button>
    </>}>
      <Field label="Signature" help="Added below what you write in new messages and replies.">
        <textarea rows={5} value={signature} onChange={(e) => setSignature(e.target.value)} placeholder={"Maddie Norton\n603 Norton"} autoFocus />
      </Field>
    </Modal>
  );
}

function ReadingSettings() {
  const markRead = usePrefs((p) => p.markReadOnOpen);
  const images = usePrefs((p) => p.alwaysShowImages);
  const notify = usePrefs((p) => p.notify);
  const supported = typeof Notification !== "undefined";
  return (
    <div className="form-stack">
      <Toggle label="Mark messages read when opened" checked={markRead} onChange={(v) => usePrefs.setState({ markReadOnOpen: v })} />
      <Toggle label="Always show images from the web" help="Off: they're hidden until you ask, so senders can't tell when (or where) you read their mail. When shown, they come through this server, which keeps where you are private." checked={images} onChange={(v) => usePrefs.setState({ alwaysShowImages: v })} />
      <Toggle
        label="Tell me about new mail"
        help={supported ? "While this site is open in a tab. The phone and desktop apps tell you even when it's closed." : "This browser can't show notifications."}
        checked={notify && supported && Notification.permission === "granted"}
        disabled={!supported}
        onChange={async (v) => {
          if (!v) { usePrefs.setState({ notify: false }); return; }
          const permission = await Notification.requestPermission();
          usePrefs.setState({ notify: permission === "granted" });
          if (permission !== "granted") toast("Notifications are blocked for this site in the browser's settings", "error");
        }}
      />
    </div>
  );
}

function AppearanceSettings() {
  const boldText = usePrefs((p) => p.boldText);
  const palette = usePrefs((p) => p.palette);
  const scheme = usePrefs((p) => p.scheme);
  return (
    <div className="form-stack">
      <Field label="Theme">
        <div className="theme-picker" role="radiogroup" aria-label="Theme">
          {PALETTES.map((p) => (
            <button key={p.id} type="button" role="radio" aria-checked={palette === p.id} className={`theme-choice ${palette === p.id ? "is-selected" : ""}`} onClick={() => usePrefs.setState({ palette: p.id })}>
              <span className="theme-swatch" style={{ background: `linear-gradient(135deg, ${p.swatch[0]} 0 50%, ${p.swatch[1]} 50% 100%)` }} />
              {p.name}
            </button>
          ))}
        </div>
      </Field>
      <Field label="Style">
        <select value={scheme} onChange={(e) => usePrefs.setState({ scheme: e.target.value as "system" | "light" | "dark" })}>
          <option value="system">As the system is</option>
          <option value="light">Light</option>
          <option value="dark">Dark</option>
        </select>
      </Field>
      <Toggle label="Bold text" help="All text in Lexend Bold, the way a phone's Bold text setting draws it." checked={boldText} onChange={(v) => usePrefs.setState({ boldText: v })} />
    </div>
  );
}

function Apps() {
  const address = window.location.origin;
  return (
    <div className="form-stack">
      <p className="muted">
        The SuperAwesomeMagicalEmail apps for Android and Linux (Fedora, Ubuntu and SuperAwesomeMagicalLinux) show your mail here and tell you when
        new mail comes. Install one, enter this server's address, and sign in the way you do here.
      </p>
      <div className="copy-row">
        <code>{address}</code>
        <Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(address); toast("Copied"); }}><Copy size={16} /> Copy</Button>
      </div>
    </div>
  );
}

function NameForm() {
  const user = useSession((s) => s.user)!;
  const [name, setName] = useState(user.displayName);
  const [busy, setBusy] = useState(false);
  return (
    <form className="inline-form" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try { session.setUser(await api.updateName(name)); toast("Saved"); } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <Field label="Name" help={`Username: ${user.username}${user.role === "ADMIN" ? " · Admin" : ""}`}>
        <input value={name} onChange={(e) => setName(e.target.value)} maxLength={64} />
      </Field>
      <Button busy={busy} disabled={name.trim() === user.displayName} type="submit">Save</Button>
    </form>
  );
}

function PasswordChange() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <form className="form-stack" onSubmit={async (e) => {
      e.preventDefault();
      setBusy(true);
      try {
        await api.changePassword(current, next);
        setCurrent("");
        setNext("");
        toast("Password changed. Other devices were signed out.");
      } catch (err) { errorToast(err); }
      setBusy(false);
    }}>
      <h3>Change password</h3>
      <div className="two-col">
        <Field label="Current password"><input type="password" autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} required /></Field>
        <Field label="New password" help="At least 8 characters."><input type="password" autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} minLength={8} required /></Field>
      </div>
      <div><Button busy={busy} type="submit">Change password</Button></div>
    </form>
  );
}

function SignIns() {
  const { data, error, loading, setData } = useLoad(() => api.sessions(), []);
  if (loading && !data) return <Spinner />;
  if (error) return <div className="notice notice-error">{error}</div>;
  return (
    <>
      <ul className="device-list">
        {(data ?? []).map((s) => (
          <li key={s.id} className="device-row">
            {s.kind === "APP" ? (/linux|desktop/i.test(s.name) ? <Monitor size={20} /> : <Smartphone size={20} />) : <Monitor size={20} />}
            <div className="source-text">
              <div className="source-name">{s.name}{s.current && <span className="badge">This browser</span>}</div>
              <div className="source-sub">Signed in {when(s.createdAt)} · last used {when(s.lastSeenAt)}</div>
            </div>
            {!s.current && (
              <IconButton label="Sign out this device" onClick={async () => {
                try { await api.revokeSession(s.id); setData((data ?? []).filter((x) => x.id !== s.id)); } catch (e) { errorToast(e); }
              }}><LogOut size={18} /></IconButton>
            )}
          </li>
        ))}
      </ul>
      <div><Button variant="ghost" onClick={() => session.signOut()}><LogOut size={18} /> Sign out here</Button></div>
    </>
  );
}
