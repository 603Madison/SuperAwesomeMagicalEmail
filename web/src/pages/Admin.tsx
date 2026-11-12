import { useEffect, useState } from "react";
import {
  AlertTriangle, Copy, KeyRound, LogOut, MoreHorizontal, Pencil, Plus, Shield, ShieldOff, Trash2, UserCheck, UserX, Users, Zap,
} from "lucide-react";
import { api } from "../lib/api";
import { mail } from "../lib/mail";
import { when } from "../lib/format";
import { useSession } from "../lib/session";
import type { AdminSettings, Mailbox, Person, ProviderPreset, ServerSettings } from "../lib/types";
import { AccountDialog, ProviderBadge, ProviderPicker } from "../components/AccountDialog";
import { Button, Field, Menu, Modal, PageHeader, Spinner, Toggle, errorToast, toast, useLoad } from "../components/ui";

export function Admin() {
  const people = useLoad(() => api.people(), []);
  const mailboxes = useLoad(() => api.mailboxes(), []);
  const reload = () => { people.reload(); mailboxes.reload(); void mail.loadAccounts(); };
  return (
    <div className="page-scroll">
      <div className="page settings">
        <PageHeader title="Admin" subtitle="Mailboxes, the people who read them, and how they sign in" />
        <section className="panel">
          <h2>Mailboxes</h2>
          <p className="muted">
            Make a mailbox at PurelyMail (or another service), add it here, and choose who reads it. They'll find it in their inbox
            when they sign in, even if they haven't signed in before.
          </p>
          <Mailboxes mailboxes={mailboxes.data} error={mailboxes.error} people={people.data ?? []} onPeopleChanged={(p) => people.setData(p)} onChanged={reload} />
        </section>
        <section className="panel">
          <h2>People</h2>
          <PeopleTable people={people.data} error={people.error} mailboxes={mailboxes.data ?? []} onChanged={reload} setPeople={people.setData} />
        </section>
        <ServerPanels />
        <section className="panel">
          <h2>Server log</h2>
          <ServerLog />
        </section>
      </div>
    </div>
  );
}

function Mailboxes({ mailboxes, error, people, onPeopleChanged, onChanged }: {
  mailboxes?: Mailbox[];
  error: string | null;
  people: Person[];
  onPeopleChanged: (p: Person[]) => void;
  onChanged: () => void;
}) {
  const presets = useLoad(() => api.providers(), []);
  const [picking, setPicking] = useState(false);
  const [editing, setEditing] = useState<{ preset: ProviderPreset; mailbox?: Mailbox } | null>(null);
  const [removing, setRemoving] = useState<Mailbox | null>(null);
  if (error) return <div className="notice notice-error">{error}</div>;
  if (!mailboxes) return <Spinner />;
  const presetOf = (m: Mailbox) => presets.data?.presets.find((p) => p.provider === m.account.provider);

  return (
    <div className="form-stack">
      {mailboxes.length === 0 && <p className="muted">No mailboxes yet.</p>}
      <ul className="source-list">
        {mailboxes.map((m) => (
          <li key={m.account.id} className="source-row">
            <ProviderBadge provider={m.account.provider} label={presetOf(m)?.label ?? m.account.email} />
            <div className="source-text">
              <div className="source-name">{m.account.email}{m.account.label !== m.account.email && <span className="badge">{m.account.label}</span>}</div>
              <div className="source-sub">
                <Users size={12} />
                {m.members.length === 0 ? "Nobody reads it yet" : m.members.map((p) => p.displayName + (p.waitingForSignIn ? " (hasn't signed in yet)" : "")).join(", ")}
              </div>
              {m.account.problem && <div className="source-problem"><AlertTriangle size={13} /> {m.account.problem}</div>}
            </div>
            <div className="source-actions">
              <Button variant="ghost" onClick={async () => {
                try {
                  const check = await api.checkSavedMailbox(m.account.id);
                  toast(check.ok ? `${m.account.email} signs in fine` : `${check.imap} · ${check.smtp}`, check.ok ? "info" : "error");
                } catch (e) { errorToast(e); }
              }}><Zap size={16} /> Check</Button>
              <Menu label={`More for ${m.account.email}`} trigger={<MoreHorizontal size={20} />} items={[
                { label: "Change, or who reads it", icon: <Pencil size={18} />, onSelect: () => presetOf(m) && setEditing({ preset: presetOf(m)!, mailbox: m }) },
                { label: "Remove", icon: <Trash2 size={18} />, danger: true, onSelect: () => setRemoving(m) },
              ]} />
            </div>
          </li>
        ))}
      </ul>
      <div><Button variant="primary" onClick={() => setPicking(true)}><Plus size={18} /> Add a mailbox</Button></div>

      {picking && presets.data && (
        <ProviderPicker title="Add a mailbox" presets={presets.data.presets} onClose={() => setPicking(false)} onPick={(preset) => { setPicking(false); setEditing({ preset }); }} />
      )}
      {editing && (
        <AccountDialog
          admin
          preset={editing.preset}
          existing={editing.mailbox?.account}
          members={editing.mailbox?.members.map((p) => p.userId) ?? []}
          people={people}
          onPeopleChanged={onPeopleChanged}
          onClose={() => setEditing(null)}
          onSaved={() => { setEditing(null); toast("Saved"); onChanged(); }}
        />
      )}
      {removing && (
        <Modal title={`Remove ${removing.account.email}?`} onClose={() => setRemoving(null)} footer={<>
          <Button variant="ghost" onClick={() => setRemoving(null)}>Cancel</Button>
          <Button variant="danger" onClick={async () => {
            try { await api.removeMailbox(removing.account.id); setRemoving(null); onChanged(); } catch (e) { errorToast(e); }
          }}>Remove</Button>
        </>}>
          <p>Nobody here will see it any more, and its password is forgotten. Its mail stays at {removing.account.imap.host}.</p>
        </Modal>
      )}
    </div>
  );
}

function PeopleTable({ people, error, mailboxes, onChanged, setPeople }: {
  people?: Person[];
  error: string | null;
  mailboxes: Mailbox[];
  onChanged: () => void;
  setPeople: (p: Person[]) => void;
}) {
  const me = useSession((s) => s.user)!;
  const [adding, setAdding] = useState(false);
  const [resetting, setResetting] = useState<Person | null>(null);
  const [deleting, setDeleting] = useState<Person | null>(null);
  if (error) return <div className="notice notice-error">{error}</div>;
  if (!people) return <Spinner />;
  const replace = (p: Person) => setPeople(people.map((x) => (x.user.id === p.user.id ? p : x)));
  const change = async (p: Person, c: { role?: string; disabled?: boolean }) => {
    try { replace({ ...p, user: await api.updatePerson(p.user.id, c) }); } catch (e) { errorToast(e); }
  };
  const mailboxNames = (ids: string[]) => ids.map((id) => mailboxes.find((m) => m.account.id === id)?.account.email).filter(Boolean).join(", ");

  return (
    <>
      <div className="table-wrap">
        <table className="table">
          <thead><tr><th>Name</th><th>Signs in with</th><th>Mailboxes</th><th>Role</th><th>Last signed in</th><th /></tr></thead>
          <tbody>
            {people.map((p) => {
              const u = p.user;
              return (
                <tr key={u.id} className={u.disabled ? "is-off" : ""}>
                  <td>
                    <div className="source-name">{u.displayName}</div>
                    <div className="source-sub">{u.username}{u.email ? ` · ${u.email}` : ""}{u.breakGlass ? " · break-glass" : ""}{u.disabled ? " · turned off" : ""}</div>
                  </td>
                  <td>{u.oidc ? "Authentik" : u.hasPassword ? "Password" : <span className="badge badge-accent">Waiting for first sign-in</span>}</td>
                  <td className="muted">{p.mailboxes.length ? mailboxNames(p.mailboxes) : "—"}</td>
                  <td><span className={`badge ${u.role === "ADMIN" ? "badge-accent" : ""}`}>{u.role === "ADMIN" ? "Admin" : "User"}</span></td>
                  <td className="muted">{u.lastLoginAt ? when(u.lastLoginAt) : "Never"}</td>
                  <td className="cell-actions">
                    {u.id !== me.id && (
                      <Menu label={`More for ${u.username}`} trigger={<MoreHorizontal size={20} />} items={u.breakGlass ? [
                        { label: "Set a password", icon: <KeyRound size={18} />, onSelect: () => setResetting(p) },
                      ] : [
                        u.role === "ADMIN"
                          ? { label: "Make a user", icon: <ShieldOff size={18} />, onSelect: () => void change(p, { role: "USER" }) }
                          : { label: "Make an admin", icon: <Shield size={18} />, onSelect: () => void change(p, { role: "ADMIN" }) },
                        u.disabled
                          ? { label: "Turn on", icon: <UserCheck size={18} />, onSelect: () => void change(p, { disabled: false }) }
                          : { label: "Turn off (and sign out)", icon: <UserX size={18} />, onSelect: () => void change(p, { disabled: true }) },
                        ...(!u.oidc || u.hasPassword ? [{ label: "Set a password", icon: <KeyRound size={18} />, onSelect: () => setResetting(p) }] : []),
                        { label: "Sign out everywhere", icon: <LogOut size={18} />, onSelect: async () => { try { await api.signOutPerson(u.id); toast(`Signed ${u.username} out`); } catch (e) { errorToast(e); } } },
                        { label: "Delete", icon: <Trash2 size={18} />, danger: true, onSelect: () => setDeleting(p) },
                      ]} />
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
      <div><Button onClick={() => setAdding(true)}><Plus size={18} /> Add a person</Button></div>
      <p className="muted">
        People who sign in with Authentik get an account the first time (if that's on below). Add someone here to give them a mailbox before then:
        their first sign-in with that Authentik username, or email, is theirs.
      </p>
      {adding && <AddPerson onClose={() => setAdding(false)} onAdded={(p) => { setPeople([...people, p]); setAdding(false); onChanged(); }} />}
      {resetting && <ResetPassword person={resetting} onClose={() => setResetting(null)} />}
      {deleting && (
        <Modal title={`Delete ${deleting.user.username}?`} onClose={() => setDeleting(null)} footer={<>
          <Button variant="ghost" onClick={() => setDeleting(null)}>Cancel</Button>
          <Button variant="danger" onClick={async () => {
            try { await api.deletePerson(deleting.user.id); setPeople(people.filter((x) => x.user.id !== deleting.user.id)); setDeleting(null); onChanged(); } catch (e) { errorToast(e); }
          }}>Delete</Button>
        </>}>
          <p>Accounts they added themselves are removed from this server (their mail stays with its service). Mailboxes you gave them stay, to give to someone else.</p>
        </Modal>
      )}
    </>
  );
}

function AddPerson({ onClose, onAdded }: { onClose: () => void; onAdded: (p: Person) => void }) {
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [withPassword, setWithPassword] = useState(false);
  const [password, setPassword] = useState("");
  const [admin, setAdmin] = useState(false);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      onAdded(await api.addPerson({
        username: username.trim(), displayName: displayName.trim(), email: email.trim(), password: withPassword ? password : undefined, role: admin ? "ADMIN" : "USER",
      }));
    } catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title="Add a person" onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={() => void save()}>Add</Button></>}>
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Name"><input autoFocus value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></Field>
        <div className="two-col">
          <Field label={withPassword ? "Username" : "Authentik username"}><input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" required /></Field>
          <Field label="Email (optional)" help="Also links their Authentik sign-in."><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
        </div>
        <Field label="Signs in with">
          <select value={withPassword ? "password" : "sso"} onChange={(e) => setWithPassword(e.target.value === "password")}>
            <option value="sso">Authentik (single sign-on)</option>
            <option value="password">A password</option>
          </select>
        </Field>
        {withPassword ? (
          <Field label="Password" help="At least 8 characters. They can change it in Settings."><input type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} required /></Field>
        ) : (
          <p className="muted small">
            Their account is linked the first time they sign in with Authentik as this username (or email). Make sure people can't change
            their own username or email in Authentik (System › Settings), or anyone could take it.
          </p>
        )}
        <Toggle label="Admin" help="Admins manage mailboxes, people and these settings." checked={admin} onChange={setAdmin} />
        <button type="submit" hidden />
      </form>
    </Modal>
  );
}

function ResetPassword({ person, onClose }: { person: Person; onClose: () => void }) {
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try { await api.resetPassword(person.user.id, password); toast(`New password set for ${person.user.username}; they were signed out`); onClose(); }
    catch (e) { errorToast(e); setBusy(false); }
  };
  return (
    <Modal title={`New password for ${person.user.username}`} onClose={onClose} footer={<><Button variant="ghost" onClick={onClose}>Cancel</Button><Button variant="primary" busy={busy} onClick={() => void save()}>Set password</Button></>}>
      <form onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Password" help="At least 8 characters."><input autoFocus type="password" autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} minLength={8} /></Field>
      </form>
    </Modal>
  );
}

function ServerPanels() {
  const { data, error, loading, setData } = useLoad(() => api.adminSettings(), []);
  if (loading && !data) return <section className="panel"><Spinner /></section>;
  if (error || !data) return <section className="panel"><div className="notice notice-error">{error}</div></section>;
  return <ServerForm initial={data} onSaved={setData} />;
}

function ServerForm({ initial, onSaved }: { initial: AdminSettings; onSaved: (s: AdminSettings) => void }) {
  const [settings, setSettings] = useState<ServerSettings>(initial.settings);
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState(false);
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);
  useEffect(() => setSettings(initial.settings), [initial]);
  const env = new Set(initial.fromEnvironment);
  const oidc = settings.oidc;
  const setOidc = (change: Partial<typeof oidc>) => setSettings({ ...settings, oidc: { ...oidc, ...change } });
  const lockedNote = (key: string) => (env.has(key) ? ` Set by ${key}, which wins at every start.` : "");

  const save = async () => {
    setBusy(true);
    try {
      onSaved(await api.saveSettings(settings, secret ? secret : undefined));
      setSecret("");
      toast("Saved");
    } catch (e) { errorToast(e); }
    setBusy(false);
  };

  return (
    <>
      <section className="panel">
        <h2>Server</h2>
        <div className="form-stack">
          <Field label="Server name" help="Shown on the sign-in page."><input value={settings.serverName} onChange={(e) => setSettings({ ...settings, serverName: e.target.value })} /></Field>
          <Field label="Public address" help={`The address people reach this server at, e.g. https://mail.603norton.org. Needed for single sign-on behind a proxy.${lockedNote("SAME_PUBLIC_URL")}`}>
            <input value={settings.publicUrl} placeholder={window.location.origin} onChange={(e) => setSettings({ ...settings, publicUrl: e.target.value })} />
          </Field>
          <Toggle label="People can add their own mail accounts" help={`From other services (Gmail, iCloud…), besides the mailboxes you give them.${lockedNote("SAME_PERSONAL_ACCOUNTS")}`} checked={settings.personalAccounts} onChange={(personalAccounts) => setSettings({ ...settings, personalAccounts })} />
          <Toggle label="People can sign in with a password" help="Off: everyone but the break-glass admin signs in through single sign-on." checked={settings.localLogin} onChange={(localLogin) => setSettings({ ...settings, localLogin })} />
        </div>
      </section>

      <section className="panel">
        <h2>Single sign-on (Authentik)</h2>
        <p className="muted">
          In Authentik: create an OAuth2/OpenID Provider (confidential client) and an Application for it, and add this redirect URI. Who may
          use the app is set in Authentik (bindings) and, optionally, with the group below.
        </p>
        <div className="copy-row">
          <code>{initial.redirectUri}</code>
          <Button variant="ghost" onClick={() => { void navigator.clipboard?.writeText(initial.redirectUri); toast("Copied"); }}><Copy size={16} /> Copy</Button>
        </div>
        <div className="form-stack">
          <Toggle label="Turn on single sign-on" checked={oidc.enabled} onChange={(enabled) => setOidc({ enabled })} />
          <Field label="Issuer" help={`Authentik: https://auth.example.com/application/o/<application slug>/${lockedNote("SAME_OIDC_ISSUER")}`}>
            <input value={oidc.issuer} onChange={(e) => setOidc({ issuer: e.target.value })} placeholder="https://auth.example.com/application/o/superawesomemagicalemail/" />
          </Field>
          <div className="two-col">
            <Field label="Client ID" help={lockedNote("SAME_OIDC_CLIENT_ID") || undefined}><input value={oidc.clientId} onChange={(e) => setOidc({ clientId: e.target.value })} /></Field>
            <Field label="Client secret" help={initial.oidcClientSecretSet ? "Saved. Leave empty to keep it." : "Not set."}>
              <input type="password" autoComplete="new-password" value={secret} onChange={(e) => setSecret(e.target.value)} />
            </Field>
          </div>
          <div className="two-col">
            <Field label="Button label"><input value={oidc.buttonLabel} onChange={(e) => setOidc({ buttonLabel: e.target.value })} /></Field>
            <Field label="Scopes"><input value={oidc.scopes} onChange={(e) => setOidc({ scopes: e.target.value })} /></Field>
          </div>
          <div className="two-col">
            <Field label="Group allowed to sign in" help="Blank: anyone the provider lets through."><input value={oidc.userGroup} onChange={(e) => setOidc({ userGroup: e.target.value })} placeholder="mail-users" /></Field>
            <Field label="Admin group" help="Members are admins; everyone else is a user. Blank: set roles here."><input value={oidc.adminGroup} onChange={(e) => setOidc({ adminGroup: e.target.value })} placeholder="mail-admins" /></Field>
          </div>
          <div className="two-col">
            <Field label="Username claim"><input value={oidc.usernameClaim} onChange={(e) => setOidc({ usernameClaim: e.target.value })} /></Field>
            <Field label="Groups claim"><input value={oidc.groupsClaim} onChange={(e) => setOidc({ groupsClaim: e.target.value })} /></Field>
          </div>
          <Toggle label="Make an account on first sign-in" help="Off: only people you've added (above) can sign in." checked={oidc.autoCreateUsers} onChange={(autoCreateUsers) => setOidc({ autoCreateUsers })} />
          {testResult && <div className={`notice ${testResult.ok ? "notice-ok" : "notice-error"}`}>{testResult.message}</div>}
          <div className="button-row">
            <Button busy={testing} disabled={!oidc.issuer} onClick={async () => {
              setTesting(true);
              try { setTestResult(await api.testOidc(oidc)); } catch (e) { errorToast(e); }
              setTesting(false);
            }}>Check the issuer</Button>
          </div>
        </div>
      </section>

      <div className="save-bar">
        <span className="muted">Server {initial.version}</span>
        <Button variant="primary" busy={busy} onClick={() => void save()}>Save server settings</Button>
      </div>
    </>
  );
}

function ServerLog() {
  const [all, setAll] = useState(false);
  const log = useLoad(() => api.serverLog(!all), [all]);
  return (
    <div className="form-stack">
      <div className="button-row">
        <Button variant={all ? "tonal" : "primary"} onClick={() => setAll(false)}>Warnings</Button>
        <Button variant={all ? "primary" : "tonal"} onClick={() => setAll(true)}>Everything</Button>
        <Button variant="ghost" onClick={log.reload}>Refresh</Button>
      </div>
      {log.error ? <div className="notice notice-error">{log.error}</div> : (
        <pre className="log">{log.data?.length ? log.data.slice().reverse().join("\n") : log.loading ? "…" : "Nothing yet."}</pre>
      )}
    </div>
  );
}
