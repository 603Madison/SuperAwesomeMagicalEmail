import { useState } from "react";
import { Check, CircleAlert, UserPlus } from "lucide-react";
import { api } from "../lib/api";
import type { AccountInput, ConnectionCheck, Endpoint, MailAccount, Person, Provider, ProviderPreset, Security } from "../lib/types";
import { Button, Field, Modal, Toggle } from "./ui";

/** Each service's own colours, for its letter in lists. */
export const PROVIDER_STYLE: Record<Provider, string> = {
  PURELYMAIL: "linear-gradient(135deg, #5b3fd1, #0f9d77)",
  GMAIL: "linear-gradient(135deg, #ea4335, #fbbc05)",
  ICLOUD: "linear-gradient(135deg, #3b82f6, #60a5fa)",
  FASTMAIL: "linear-gradient(135deg, #0b5cad, #2a8bd8)",
  YAHOO: "linear-gradient(135deg, #5f01d1, #8b3dff)",
  OTHER: "linear-gradient(135deg, #475569, #64748b)",
};

export function ProviderBadge({ provider, label }: { provider: Provider; label: string }) {
  return <span className="source-icon" style={{ background: PROVIDER_STYLE[provider], color: "#fff" }}>{label.slice(0, 1)}</span>;
}

/** Picking a service, the first step of adding an account. */
export function ProviderPicker({ presets, onPick, onClose, title }: { presets: ProviderPreset[]; onPick: (p: ProviderPreset) => void; onClose: () => void; title: string }) {
  return (
    <Modal title={title} onClose={onClose}>
      <div className="kind-list">
        {presets.map((p) => (
          <button key={p.provider} className="kind-option" onClick={() => onPick(p)}>
            <ProviderBadge provider={p.provider} label={p.label} />
            <span>
              <span className="source-name">{p.label}</span>
              <span className="source-sub">{p.description}</span>
            </span>
          </button>
        ))}
      </div>
    </Modal>
  );
}

/**
 * Adding or changing a mail account: the address and password (servers filled in for known
 * services), checked by signing in before it's saved. For admins' mailboxes, who reads it, too,
 * including people who haven't signed in yet.
 */
export function AccountDialog({ preset, existing, admin, people, members: initialMembers, onSaved, onClose, onPeopleChanged }: {
  preset: ProviderPreset;
  existing?: MailAccount;
  /** An admin's mailbox (rather than the person's own account). */
  admin?: boolean;
  people?: Person[];
  members?: string[];
  onSaved: () => void;
  onClose: () => void;
  onPeopleChanged?: (p: Person[]) => void;
}) {
  const other = preset.provider === "OTHER";
  const [email, setEmail] = useState(existing?.email ?? "");
  const [password, setPassword] = useState("");
  const [name, setName] = useState(existing?.name ?? "");
  const [label, setLabel] = useState(existing?.label && existing.label !== existing.email ? existing.label : "");
  const [username, setUsername] = useState(existing && existing.username !== existing.email ? existing.username : "");
  const [imap, setImap] = useState<Endpoint>(existing?.imap ?? preset.imap);
  const [smtp, setSmtp] = useState<Endpoint>(existing?.smtp ?? preset.smtp);
  const [saveSent, setSaveSent] = useState(existing?.saveSent ?? preset.saveSent);
  const [members, setMembers] = useState<string[]>(initialMembers ?? []);
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(false);
  const [check, setCheck] = useState<ConnectionCheck | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [canForce, setCanForce] = useState(false);

  const input = (skipCheck = false): AccountInput => ({
    provider: preset.provider,
    email: email.trim(),
    name: name.trim(),
    label: label.trim(),
    username: username.trim(),
    password: password || null,
    imap: other ? imap : null,
    smtp: other ? smtp : null,
    saveSent,
    members: admin ? members : null,
    skipCheck,
  });

  const runCheck = async () => {
    setChecking(true);
    setError(null);
    try {
      setCheck(existing && !password
        ? await (admin ? api.checkSavedMailbox(existing.id) : api.checkSavedAccount(existing.id))
        : await (admin ? api.checkMailbox(input()) : api.checkAccount(input())));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    setChecking(false);
  };

  const save = async (skipCheck = false) => {
    setBusy(true);
    setError(null);
    try {
      if (admin) {
        if (existing) await api.updateMailbox(existing.id, input(skipCheck));
        else await api.addMailbox(input(skipCheck));
      } else if (existing) {
        await api.updateAccount(existing.id, input(skipCheck));
      } else {
        await api.addAccount(input(skipCheck));
      }
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setCanForce(true);
      setBusy(false);
    }
  };

  const title = existing ? `${existing.email}` : admin ? `Add a ${preset.label} mailbox` : `Add ${preset.label}`;
  return (
    <Modal
      title={title}
      onClose={onClose}
      wide={admin}
      footer={<>
        <Button variant="ghost" onClick={onClose}>Cancel</Button>
        {canForce && <Button onClick={() => void save(true)} busy={busy}>Save anyway</Button>}
        <Button variant="primary" busy={busy} onClick={() => void save()}>{existing ? "Save" : "Add"}</Button>
      </>}
    >
      {admin && !existing && preset.provider === "PURELYMAIL" && (
        <p className="muted">Make the mailbox at purelymail.com first (Users › Add user), then enter its address and password here and choose who reads it.</p>
      )}
      {!admin && !existing && <p className="muted">{preset.description}.</p>}
      <form className="form-stack" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <div className="two-col">
          <Field label="Email address">
            <input type="email" autoFocus={!existing} value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@example.com" autoComplete="off" required />
          </Field>
          <Field label="Password" help={existing ? "Leave empty to keep the saved one." : preset.passwordHelp || undefined}>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" required={!existing} />
          </Field>
        </div>
        <div className="two-col">
          <Field label="Name on mail you send" help="Blank: just the address.">
            <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Maddie Norton" maxLength={100} />
          </Field>
          <Field label="Shown as" help="In the list of accounts.">
            <input value={label} onChange={(e) => setLabel(e.target.value)} placeholder={email || "Work"} maxLength={80} />
          </Field>
        </div>
        {other && (
          <>
            <Field label="Username" help="Blank: the email address."><input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" /></Field>
            <ServerFields title="Incoming (IMAP)" value={imap} onChange={setImap} />
            <ServerFields title="Outgoing (SMTP)" value={smtp} onChange={setSmtp} />
          </>
        )}
        <Toggle label="Keep a copy of sent mail in Sent" help={preset.saveSent ? "This service doesn't do it by itself." : `${preset.label} does this by itself, so leave it off.`} checked={saveSent} onChange={setSaveSent} />
        {admin && people && (
          <Members people={people} value={members} onChange={setMembers} onPeopleChanged={onPeopleChanged} />
        )}
        <button type="submit" hidden />
      </form>
      {check && (
        <div className={`notice ${check.ok ? "notice-ok" : "notice-error"}`}>
          <div className="check-line">{check.imap.startsWith("Signed in") ? <Check size={16} /> : <CircleAlert size={16} />} Incoming: {check.imap}</div>
          <div className="check-line">{check.smtp.startsWith("Signed in") ? <Check size={16} /> : <CircleAlert size={16} />} Outgoing: {check.smtp}</div>
        </div>
      )}
      {error && <div className="notice notice-error" role="alert">{error}</div>}
      <div><Button busy={checking} onClick={() => void runCheck()} disabled={!email || (!existing && !password)}>Check it signs in</Button></div>
    </Modal>
  );
}

function ServerFields({ title, value, onChange }: { title: string; value: Endpoint; onChange: (e: Endpoint) => void }) {
  return (
    <fieldset className="server-fields">
      <legend>{title}</legend>
      <div className="server-grid">
        <Field label="Server"><input value={value.host} onChange={(e) => onChange({ ...value, host: e.target.value })} placeholder="mail.example.com" required /></Field>
        <Field label="Port"><input inputMode="numeric" value={value.port} onChange={(e) => onChange({ ...value, port: Number(e.target.value.replace(/\D/g, "")) || 0 })} /></Field>
        <Field label="Security">
          <select value={value.security} onChange={(e) => onChange({ ...value, security: e.target.value as Security })}>
            <option value="SSL">SSL/TLS</option>
            <option value="STARTTLS">STARTTLS</option>
            <option value="NONE">None (not encrypted)</option>
          </select>
        </Field>
      </div>
    </fieldset>
  );
}

/** Who reads an admin's mailbox: ticks beside people, and a quick way to add someone who'll sign in with Authentik. */
function Members({ people, value, onChange, onPeopleChanged }: { people: Person[]; value: string[]; onChange: (v: string[]) => void; onPeopleChanged?: (p: Person[]) => void }) {
  const [adding, setAdding] = useState(false);
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const add = async () => {
    setBusy(true);
    setError(null);
    try {
      const person = await api.addPerson({ username: username.trim(), displayName: displayName.trim(), email: email.trim(), role: "USER" });
      onPeopleChanged?.([...people, person]);
      onChange([...value, person.user.id]);
      setAdding(false);
      setUsername("");
      setDisplayName("");
      setEmail("");
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
    setBusy(false);
  };

  return (
    <fieldset className="members">
      <legend>Who reads it</legend>
      <ul className="member-list">
        {people.filter((p) => !p.user.disabled).map(({ user }) => (
          <li key={user.id}>
            <label className="member">
              <input type="checkbox" checked={value.includes(user.id)} onChange={(e) => onChange(e.target.checked ? [...value, user.id] : value.filter((v) => v !== user.id))} />
              <span className="member-text">
                <span>{user.displayName}</span>
                <span className="muted small">{user.username}{user.waitingForSignIn ? " · hasn't signed in yet" : ""}</span>
              </span>
            </label>
          </li>
        ))}
      </ul>
      {adding ? (
        <div className="add-member">
          <p className="muted small">Someone who'll sign in with Authentik: their account is linked when they first sign in with this username (or email).</p>
          <div className="server-grid">
            <Field label="Authentik username"><input autoFocus value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="off" /></Field>
            <Field label="Name"><input value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></Field>
            <Field label="Email (optional)"><input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></Field>
          </div>
          {error && <div className="notice notice-error">{error}</div>}
          <div className="button-row">
            <Button busy={busy} disabled={!username.trim()} onClick={() => void add()}>Add them</Button>
            <Button variant="ghost" onClick={() => setAdding(false)}>Cancel</Button>
          </div>
        </div>
      ) : (
        <button type="button" className="link-btn" onClick={() => setAdding(true)}><UserPlus size={16} /> Someone who hasn't signed in yet</button>
      )}
    </fieldset>
  );
}
