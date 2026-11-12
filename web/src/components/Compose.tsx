import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from "react";
import { create } from "zustand";
import {
  Bold, ChevronDown, Italic, Link as LinkIcon, List, ListOrdered, Maximize2, Minimize2, Paperclip, Quote, RemoveFormatting, Send, Trash2, Underline, X,
} from "lucide-react";
import { api } from "../lib/api";
import { mail, useMail } from "../lib/mail";
import { fileSize, full, parseAddress, shown, textToHtml } from "../lib/format";
import type { Address, AttachmentRef, ComposeMode, Contact, Draft } from "../lib/types";
import { Button, IconButton, Spinner, errorToast, toast } from "./ui";

/** A file being attached: uploading (with how far it's got), or uploaded. */
interface Attaching {
  key: string;
  name: string;
  size: number;
  progress: number;
  uploadId?: string;
  error?: string;
}

interface Composing {
  /** What the window starts with; null while a reply is being prepared. */
  seed: Draft | null;
  /** Changes each time a new message is started, so the window starts afresh. */
  key: number;
  loading: boolean;
}

export const useCompose = create<Composing | null>(() => null);

let nextKey = 1;

const blank = (accountId: string): Draft => ({ accountId, to: [], cc: [], bcc: [], subject: "", text: "", html: null, uploads: [], attachments: [] });

/** The account to write from: the one being looked at, else the first. */
function defaultAccount(): string | null {
  const { view, accounts } = useMail.getState();
  return accounts?.find((a) => a.id === view.account)?.id ?? accounts?.[0]?.id ?? null;
}

function signatureHtml(accountId: string): string {
  const signature = mail.accountOf(accountId)?.signature?.trim();
  return signature ? `<p>-- <br>${textToHtml(signature)}</p>` : "";
}

export const compose = {
  /** A new message, to [to] if given. */
  write(to: Address[] = []) {
    const accountId = defaultAccount();
    if (!accountId) {
      toast("Add a mail account first (Settings)", "error");
      return;
    }
    useCompose.setState({ seed: { ...blank(accountId), to, html: `<p><br></p>${signatureHtml(accountId)}` }, key: nextKey++, loading: false }, true);
  },

  /** A reply, reply to all or forward of [messageId], or a draft opened again. */
  async from(messageId: string, mode: ComposeMode) {
    const key = nextKey++;
    useCompose.setState({ seed: null, key, loading: true }, true);
    try {
      const seed = await api.compose(messageId, mode);
      if (useCompose.getState()?.key !== key) return;
      const html = seed.html ?? `<p>${textToHtml(seed.text)}</p>`;
      const withSignature = mode === "DRAFT" ? html : html.replace(/^<p><br><\/p>/, `<p><br></p>${signatureHtml(seed.accountId)}`);
      useCompose.setState({ seed: { ...seed, html: withSignature }, key, loading: false }, true);
    } catch (e) {
      useCompose.setState(null, true);
      errorToast(e);
    }
  },

  close() {
    useCompose.setState(null, true);
  },
};

/** The window for writing mail: docked at the bottom right, or the whole screen on phones (and when made big). */
export function Compose() {
  const state = useCompose();
  if (!state) return null;
  if (state.loading || !state.seed) {
    return (
      <div className="compose compose-loading" role="dialog" aria-label="New message">
        <Spinner size={28} />
      </div>
    );
  }
  return <ComposeWindow key={state.key} seed={state.seed} />;
}

function ComposeWindow({ seed }: { seed: Draft }) {
  const accounts = useMail((s) => s.accounts) ?? [];
  const [accountId, setAccountId] = useState(seed.accountId);
  const [to, setTo] = useState<Address[]>(seed.to);
  const [cc, setCc] = useState<Address[]>(seed.cc);
  const [bcc, setBcc] = useState<Address[]>(seed.bcc);
  const [showCopies, setShowCopies] = useState(seed.cc.length > 0 || seed.bcc.length > 0);
  const [subject, setSubject] = useState(seed.subject);
  const [kept, setKept] = useState<AttachmentRef[]>(seed.attachments);
  const [files, setFiles] = useState<Attaching[]>([]);
  const [draftId, setDraftId] = useState<string | null>(seed.draftId ?? null);
  const [sending, setSending] = useState(false);
  const [saving, setSaving] = useState(false);
  const [big, setBig] = useState(false);
  const [minimized, setMinimized] = useState(false);
  const [dragging, setDragging] = useState(false);
  const editor = useRef<HTMLDivElement>(null);
  const fileInput = useRef<HTMLInputElement>(null);
  const dirty = useRef(false);
  const touch = () => { dirty.current = true; };

  useEffect(() => {
    if (editor.current) editor.current.innerHTML = seed.html ?? "";
    // Replies start typing above the quote; new messages in the body unless there's nobody to send to yet.
    if (seed.to.length > 0 || seed.inReplyTo) placeCaretAtStart(editor.current);
  }, [seed]);

  const draft = (): Draft => ({
    accountId,
    to, cc, bcc,
    subject,
    text: "",
    html: editor.current?.innerHTML ?? "",
    uploads: files.filter((f) => f.uploadId).map((f) => f.uploadId!),
    attachments: kept,
    inReplyTo: seed.inReplyTo ?? null,
    forwardOf: seed.forwardOf ?? null,
    draftId,
  });

  const hasContent = () => to.length + cc.length + bcc.length > 0 || subject.trim() !== "" || (editor.current?.innerText.trim() ?? "") !== "" || files.length > 0;

  const saveDraft = async (quiet: boolean) => {
    if (!hasContent()) return;
    setSaving(true);
    try {
      const saved = await api.saveDraft(draft());
      setDraftId(saved.id);
      dirty.current = false;
      if (!quiet) toast("Saved to Drafts");
      const drafts = mail.folderFor(accountId, "DRAFTS");
      const { view } = useMail.getState();
      if (drafts && view.account === accountId && view.folder === drafts.path) void mail.refresh();
    } catch (e) {
      if (!quiet) errorToast(e);
    }
    setSaving(false);
  };

  // Kept as a draft every half minute while it's being written.
  useEffect(() => {
    const timer = setInterval(() => { if (dirty.current && !sending) void saveDraft(true); }, 30_000);
    return () => clearInterval(timer);
  });

  const close = async () => {
    if (dirty.current && hasContent()) await saveDraft(false);
    compose.close();
  };

  const discard = async () => {
    if (draftId) await api.act([draftId], "DELETE").catch(() => undefined);
    files.filter((f) => f.uploadId).forEach((f) => void api.removeUpload(f.uploadId!).catch(() => undefined));
    compose.close();
    if (draftId) void mail.refresh();
  };

  const send = async () => {
    if (to.length + cc.length + bcc.length === 0) {
      toast("Who's it to?", "error");
      return;
    }
    if (files.some((f) => !f.uploadId && !f.error)) {
      toast("Wait for the attachments to finish uploading", "error");
      return;
    }
    if (!subject.trim() && !window.confirm("Send it without a subject?")) return;
    setSending(true);
    try {
      const result = await api.send(draft());
      compose.close();
      toast(result.note ?? "Sent");
      void mail.loadFolders(accountId);
      const { view } = useMail.getState();
      if (view.account === accountId && view.folder !== "INBOX") void mail.refresh();
    } catch (e) {
      errorToast(e);
      setSending(false);
    }
  };

  const attach = (list: FileList | File[]) => {
    Array.from(list).forEach((file) => {
      const key = `${Date.now()}-${Math.random()}`;
      setFiles((f) => [...f, { key, name: file.name, size: file.size, progress: 0 }]);
      touch();
      api.upload(file, (progress) => setFiles((f) => f.map((x) => (x.key === key ? { ...x, progress } : x))))
        .then((u) => setFiles((f) => f.map((x) => (x.key === key ? { ...x, progress: 1, uploadId: u.id } : x))))
        .catch((e) => setFiles((f) => f.map((x) => (x.key === key ? { ...x, error: e instanceof Error ? e.message : String(e) } : x))));
    });
  };

  const onKey = (e: ReactKeyboardEvent) => {
    if ((e.ctrlKey || e.metaKey) && e.key === "Enter") {
      e.preventDefault();
      void send();
    } else if (e.key === "Escape") {
      e.stopPropagation();
      void close();
    }
  };

  const title = subject.trim() || (seed.inReplyTo ? "Reply" : seed.forwardOf ? "Forward" : "New message");

  if (minimized) {
    return (
      <div className="compose compose-min" role="dialog" aria-label={title}>
        <button className="compose-bar" onClick={() => setMinimized(false)}>
          <span>{title}</span>
          <Maximize2 size={16} />
        </button>
      </div>
    );
  }

  return (
    <div
      className={`compose ${big ? "is-big" : ""} ${dragging ? "is-dragging" : ""}`}
      role="dialog"
      aria-label={title}
      onKeyDown={onKey}
      onDragOver={(e) => { if (e.dataTransfer.types.includes("Files")) { e.preventDefault(); setDragging(true); } }}
      onDragLeave={(e) => { if (e.currentTarget === e.target) setDragging(false); }}
      onDrop={(e) => { if (e.dataTransfer.files.length) { e.preventDefault(); setDragging(false); attach(e.dataTransfer.files); } }}
    >
      <div className="compose-head">
        <h2>{title}</h2>
        <div className="compose-head-actions">
          <IconButton label="Make small" className="desktop-only" onClick={() => setMinimized(true)}><ChevronDown size={18} /></IconButton>
          <IconButton label={big ? "Make it smaller" : "Make it bigger"} className="desktop-only" onClick={() => setBig(!big)}>
            {big ? <Minimize2 size={16} /> : <Maximize2 size={16} />}
          </IconButton>
          <IconButton label="Close (keeps a draft)" onClick={() => void close()}><X size={20} /></IconButton>
        </div>
      </div>

      <div className="compose-fields">
        {accounts.length > 1 && (
          <label className="compose-row">
            <span className="compose-label">From</span>
            <select value={accountId} onChange={(e) => { setAccountId(e.target.value); touch(); }} className="compose-from">
              {accounts.map((a) => <option key={a.id} value={a.id}>{a.name ? `${a.name} <${a.email}>` : a.email}</option>)}
            </select>
          </label>
        )}
        <div className="compose-row">
          <span className="compose-label">To</span>
          <Recipients value={to} onChange={(v) => { setTo(v); touch(); }} autoFocus={to.length === 0} label="To" />
          {!showCopies && <button className="link-btn" onClick={() => setShowCopies(true)}>Cc Bcc</button>}
        </div>
        {showCopies && (
          <>
            <div className="compose-row">
              <span className="compose-label">Cc</span>
              <Recipients value={cc} onChange={(v) => { setCc(v); touch(); }} label="Cc" />
            </div>
            <div className="compose-row">
              <span className="compose-label">Bcc</span>
              <Recipients value={bcc} onChange={(v) => { setBcc(v); touch(); }} label="Bcc" />
            </div>
          </>
        )}
        <label className="compose-row">
          <span className="compose-label">Subject</span>
          <input className="compose-subject" value={subject} onChange={(e) => { setSubject(e.target.value); touch(); }} aria-label="Subject" />
        </label>
      </div>

      <Toolbar editor={editor} />
      <div
        ref={editor}
        className="compose-editor"
        contentEditable
        role="textbox"
        aria-multiline
        aria-label="Message"
        onInput={touch}
        onPaste={() => touch()}
        suppressContentEditableWarning
      />

      {(kept.length > 0 || files.length > 0) && (
        <ul className="compose-files">
          {kept.map((a) => (
            <li key={`${a.messageId}/${a.part}`} className="file-chip">
              <Paperclip size={14} />
              <span className="file-name">{a.filename}</span>
              {a.size > 0 && <span className="file-size">{fileSize(a.size)}</span>}
              <IconButton label={`Remove ${a.filename}`} onClick={() => { setKept(kept.filter((k) => k !== a)); touch(); }}><X size={14} /></IconButton>
            </li>
          ))}
          {files.map((f) => (
            <li key={f.key} className={`file-chip ${f.error ? "is-error" : ""}`} title={f.error}>
              <Paperclip size={14} />
              <span className="file-name">{f.name}</span>
              <span className="file-size">{f.error ? "Didn't upload" : f.uploadId ? fileSize(f.size) : `${Math.round(f.progress * 100)}%`}</span>
              {!f.uploadId && !f.error && <span className="file-progress" style={{ width: `${Math.round(f.progress * 100)}%` }} />}
              <IconButton label={`Remove ${f.name}`} onClick={() => {
                setFiles(files.filter((x) => x.key !== f.key));
                if (f.uploadId) void api.removeUpload(f.uploadId).catch(() => undefined);
                touch();
              }}><X size={14} /></IconButton>
            </li>
          ))}
        </ul>
      )}

      <div className="compose-foot">
        <Button variant="primary" busy={sending} onClick={() => void send()} title="Send (Ctrl+Enter)"><Send size={16} /> Send</Button>
        <IconButton label="Attach files" onClick={() => fileInput.current?.click()}><Paperclip size={20} /></IconButton>
        <input ref={fileInput} type="file" multiple hidden onChange={(e) => { if (e.target.files) attach(e.target.files); e.target.value = ""; }} />
        <span className="compose-status">{saving ? "Saving…" : draftId ? "Draft saved" : ""}</span>
        <div className="spacer" />
        <Button variant="ghost" onClick={() => void saveDraft(false)} disabled={saving}>Save draft</Button>
        <IconButton label="Discard" onClick={() => void discard()}><Trash2 size={18} /></IconButton>
      </div>
      {dragging && <div className="drop-hint">Drop files to attach them</div>}
    </div>
  );
}

function placeCaretAtStart(el: HTMLElement | null) {
  if (!el) return;
  el.focus();
  const range = document.createRange();
  range.setStart(el, 0);
  range.collapse(true);
  const selection = window.getSelection();
  selection?.removeAllRanges();
  selection?.addRange(range);
}

/** Bold, italic, lists, links and quotes: the formatting mail needs. */
function Toolbar({ editor }: { editor: React.RefObject<HTMLDivElement | null> }) {
  const run = (command: string, value?: string) => {
    editor.current?.focus();
    document.execCommand(command, false, value);
  };
  const tools: { label: string; icon: React.ReactNode; run: () => void }[] = [
    { label: "Bold (Ctrl+B)", icon: <Bold size={16} />, run: () => run("bold") },
    { label: "Italic (Ctrl+I)", icon: <Italic size={16} />, run: () => run("italic") },
    { label: "Underline (Ctrl+U)", icon: <Underline size={16} />, run: () => run("underline") },
    { label: "Bulleted list", icon: <List size={16} />, run: () => run("insertUnorderedList") },
    { label: "Numbered list", icon: <ListOrdered size={16} />, run: () => run("insertOrderedList") },
    { label: "Quote", icon: <Quote size={16} />, run: () => run("formatBlock", "blockquote") },
    {
      label: "Link",
      icon: <LinkIcon size={16} />,
      run: () => {
        const url = window.prompt("Link to (https://…)");
        if (url && /^(https?:|mailto:)/i.test(url.trim())) run("createLink", url.trim());
      },
    },
    { label: "Clear formatting", icon: <RemoveFormatting size={16} />, run: () => run("removeFormat") },
  ];
  return (
    <div className="compose-tools" role="toolbar" aria-label="Formatting">
      {tools.map((t) => (
        <button key={t.label} type="button" className="tool" title={t.label} aria-label={t.label} onMouseDown={(e) => e.preventDefault()} onClick={t.run}>
          {t.icon}
        </button>
      ))}
    </div>
  );
}

/** People to send to, as chips, with suggestions from addresses written to before. */
export function Recipients({ value, onChange, autoFocus, label }: { value: Address[]; onChange: (v: Address[]) => void; autoFocus?: boolean; label: string }) {
  const [text, setText] = useState("");
  const [suggestions, setSuggestions] = useState<Contact[]>([]);
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);

  useEffect(() => {
    const query = text.trim();
    if (query.length < 2) {
      setSuggestions([]);
      return;
    }
    const timer = setTimeout(() => {
      api.contacts(query).then((found) => {
        setSuggestions(found.filter((c) => !value.some((v) => v.email.toLowerCase() === c.email.toLowerCase())));
        setActive(0);
      }, () => setSuggestions([]));
    }, 150);
    return () => clearTimeout(timer);
  }, [text, value]);

  const add = (address: Address) => {
    if (!value.some((v) => v.email.toLowerCase() === address.email.toLowerCase())) onChange([...value, address]);
    setText("");
    setSuggestions([]);
  };

  /** Turns what's typed into chips; anything that isn't an address stays to be fixed. */
  const commit = (): boolean => {
    const parts = text.split(/[,;\n]+/).map((p) => p.trim()).filter(Boolean);
    if (parts.length === 0) return false;
    const parsed = parts.map(parseAddress);
    const good = parsed.filter((a): a is Address => a !== null);
    const bad = parts.filter((_, i) => parsed[i] === null);
    if (good.length) onChange([...value, ...good.filter((g) => !value.some((v) => v.email.toLowerCase() === g.email.toLowerCase()))]);
    setText(bad.join(", "));
    return good.length > 0;
  };

  const onKeyDown = (e: ReactKeyboardEvent<HTMLInputElement>) => {
    if (suggestions.length && (e.key === "ArrowDown" || e.key === "ArrowUp")) {
      e.preventDefault();
      setActive((a) => (a + (e.key === "ArrowDown" ? 1 : suggestions.length - 1)) % suggestions.length);
    } else if (e.key === "Enter" || e.key === "Tab" || e.key === "," || e.key === ";") {
      if (suggestions.length && (e.key === "Enter" || e.key === "Tab")) {
        e.preventDefault();
        add(suggestions[active]);
      } else if (text.trim()) {
        if (commit() || e.key !== "Tab") e.preventDefault();
      }
    } else if (e.key === "Backspace" && text === "" && value.length) {
      onChange(value.slice(0, -1));
    }
  };

  return (
    <div className="recipients" onClick={() => input.current?.focus()}>
      {value.map((a) => (
        <span key={a.email} className="chip-address" title={full(a)}>
          {shown(a)}
          <button type="button" aria-label={`Remove ${shown(a)}`} onClick={(e) => { e.stopPropagation(); onChange(value.filter((v) => v !== a)); }}><X size={12} /></button>
        </span>
      ))}
      <input
        ref={input}
        value={text}
        autoFocus={autoFocus}
        aria-label={label}
        onChange={(e) => setText(e.target.value)}
        onKeyDown={onKeyDown}
        onBlur={() => setTimeout(() => { commit(); setSuggestions([]); }, 150)}
        onPaste={(e) => {
          const pasted = e.clipboardData.getData("text");
          if (/[,;\n]/.test(pasted)) {
            e.preventDefault();
            setText(text + pasted);
            setTimeout(commit, 0);
          }
        }}
        autoComplete="off"
      />
      {suggestions.length > 0 && (
        <ul className="suggestions" role="listbox">
          {suggestions.map((c, i) => (
            <li key={c.email} role="option" aria-selected={i === active} className={i === active ? "is-active" : ""} onMouseDown={(e) => { e.preventDefault(); add(c); }}>
              <span className="suggestion-name">{c.name || c.email}</span>
              {c.name && <span className="suggestion-email">{c.email}</span>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
