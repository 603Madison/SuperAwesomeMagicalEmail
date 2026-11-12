import { useEffect, useRef, useState } from "react";
import {
  Archive, Check, FolderInput, Mail, MailOpen, Menu as MenuIcon, Paperclip, RefreshCw, Reply, Search, Star, Trash2, X,
} from "lucide-react";
import { ALL, mail, useMail } from "../lib/mail";
import { avatarColour, initial, ROLE_NAMES, shortDate, shown } from "../lib/format";
import type { Action, MessageSummary } from "../lib/types";
import { Empty, ErrorNotice, IconButton, Menu, Spinner, errorToast } from "./ui";

/** The messages in the folder being looked at, newest first, with search and actions. */
export function MessageList({ openId, onOpen, onMenu }: { openId: string | null; onOpen: (m: MessageSummary) => void; onMenu: () => void }) {
  const list = useMail((s) => s.list);
  const view = useMail((s) => s.view);
  const query = useMail((s) => s.query);
  const selected = useMail((s) => s.selected);
  const accounts = useMail((s) => s.accounts) ?? [];
  const folders = useMail((s) => s.folders);
  const [search, setSearch] = useState(query);
  const sentinel = useRef<HTMLDivElement>(null);

  useEffect(() => setSearch(query), [query]);

  // More as the end of the list comes into view.
  useEffect(() => {
    const el = sentinel.current;
    if (!el) return;
    const observer = new IntersectionObserver((entries) => { if (entries.some((e) => e.isIntersecting)) void mail.loadMore(); }, { rootMargin: "400px" });
    observer.observe(el);
    return () => observer.disconnect();
  }, [list.key, list.next]);

  const account = accounts.find((a) => a.id === view.account);
  const folder = folders[view.account]?.find((f) => f.path === view.folder);
  const title = view.account === ALL ? "All inboxes" : folder ? (folder.role !== "OTHER" ? ROLE_NAMES[folder.role] : folder.name) : view.folder === "INBOX" ? "Inbox" : view.folder;
  const outgoing = folder?.role === "SENT" || folder?.role === "DRAFTS";
  const many = accounts.length > 1;
  const act = (ids: string[], action: Action, target?: string) => mail.act(ids, action, target).catch(errorToast);
  const chosen = list.messages.filter((m) => selected.includes(m.id));
  const moveTargets = view.account === ALL ? [] : (folders[view.account] ?? []).filter((f) => f.selectable && f.path !== view.folder);

  return (
    <section className="list-pane" aria-label={title}>
      <header className="list-head">
        <div className="list-title">
          <IconButton label="Mailboxes" className="phone-only" onClick={onMenu}><MenuIcon size={20} /></IconButton>
          <div className="list-title-text">
            <h1>{title}</h1>
            <span className="muted small">{account ? account.label || account.email : view.account === ALL ? `${accounts.length} accounts` : ""}</span>
          </div>
          <IconButton label="Check for new mail" onClick={() => void mail.refresh()}><RefreshCw size={18} className={list.loading ? "spin" : ""} /></IconButton>
        </div>
        <form className="search-box" role="search" onSubmit={(e) => { e.preventDefault(); void mail.open(view, search.trim()); }}>
          <Search size={18} />
          <input value={search} onChange={(e) => setSearch(e.target.value)} placeholder={`Search ${title.toLowerCase()}`} aria-label="Search" id="mail-search" />
          {query && <IconButton label="Clear search" type="button" onClick={() => { setSearch(""); void mail.open(view, ""); }}><X size={16} /></IconButton>}
        </form>
        {selected.length > 0 && (
          <div className="bulk-bar">
            <IconButton label="Clear selection" onClick={() => mail.select([])}><X size={18} /></IconButton>
            <span className="bulk-count">{selected.length} selected</span>
            <div className="spacer" />
            <IconButton label="Archive" onClick={() => act(selected, "ARCHIVE")}><Archive size={18} /></IconButton>
            <IconButton label="Delete" onClick={() => act(selected, "TRASH")}><Trash2 size={18} /></IconButton>
            {chosen.some((m) => m.unread)
              ? <IconButton label="Mark read" onClick={() => act(selected, "READ")}><MailOpen size={18} /></IconButton>
              : <IconButton label="Mark unread" onClick={() => act(selected, "UNREAD")}><Mail size={18} /></IconButton>}
            {moveTargets.length > 0 && (
              <Menu label="Move to" trigger={<FolderInput size={18} />} items={moveTargets.map((f) => ({
                label: f.role !== "OTHER" ? ROLE_NAMES[f.role] : f.name,
                onSelect: () => void act(selected, "MOVE", f.path),
              }))} />
            )}
          </div>
        )}
      </header>

      {Object.entries(list.problems).map(([id, problem]) => (
        <div key={id} className="notice notice-error list-notice">{accounts.find((a) => a.id === id)?.label ?? "An account"}: {problem}</div>
      ))}
      {list.error && <div className="list-notice"><ErrorNotice message={list.error} onRetry={() => void mail.refresh()} /></div>}

      {list.messages.length === 0 && !list.loading && !list.error ? (
        accounts.length === 0 ? null : (
          <Empty icon={<Check size={30} />} title={query ? "Nothing found" : "All caught up"}>
            {query ? `No messages match "${query}".` : "No messages here."}
          </Empty>
        )
      ) : (
        <ul className="messages" role="list">
          {list.messages.map((m) => (
            <Row
              key={m.id}
              message={m}
              open={m.id === openId}
              selected={selected.includes(m.id)}
              anySelected={selected.length > 0}
              outgoing={outgoing}
              accountLabel={view.account === ALL && many ? accounts.find((a) => a.id === m.accountId)?.label : undefined}
              onOpen={() => onOpen(m)}
              onSelect={(on) => mail.select(on ? [...selected, m.id] : selected.filter((s) => s !== m.id))}
              onAct={(action) => act([m.id], action)}
            />
          ))}
        </ul>
      )}
      {list.loading && <div className="center-pad"><Spinner /></div>}
      <div ref={sentinel} className="sentinel" />
    </section>
  );
}

function Row({ message: m, open, selected, anySelected, outgoing, accountLabel, onOpen, onSelect, onAct }: {
  message: MessageSummary;
  open: boolean;
  selected: boolean;
  anySelected: boolean;
  outgoing: boolean;
  accountLabel?: string;
  onOpen: () => void;
  onSelect: (on: boolean) => void;
  onAct: (action: Action) => void;
}) {
  const who = outgoing ? (m.to.length ? `To: ${m.to.map(shown).join(", ")}` : "(no recipients)") : shown(m.from) || "(unknown sender)";
  const person = outgoing ? m.to[0] : m.from;
  return (
    <li className={`msg ${m.unread ? "is-unread" : ""} ${open ? "is-open" : ""} ${selected ? "is-selected" : ""}`}>
      <button
        className={`avatar ${anySelected || selected ? "is-selecting" : ""}`}
        style={{ background: avatarColour(person?.email ?? "?") }}
        aria-label={selected ? "Unselect" : "Select"}
        aria-pressed={selected}
        onClick={() => onSelect(!selected)}
      >
        <span className="avatar-letter">{initial(person)}</span>
        <span className="avatar-check"><Check size={18} /></span>
      </button>
      <button className="msg-main" onClick={onOpen}>
        <span className="msg-top">
          <span className="msg-from">{m.draft && <span className="draft-tag">Draft</span>}{who}</span>
          <span className="msg-date">{shortDate(m.date)}</span>
        </span>
        <span className="msg-subject">
          {m.answered && <Reply size={13} className="answered" aria-label="Answered" />}
          {m.subject || "(no subject)"}
        </span>
        <span className="msg-preview">
          {accountLabel && <span className="account-tag">{accountLabel}</span>}
          {m.preview}
        </span>
      </button>
      <div className="msg-side">
        {m.hasAttachments && <Paperclip size={15} className="clip" aria-label="Has attachments" />}
        <button className={`star ${m.flagged ? "is-on" : ""}`} aria-label={m.flagged ? "Unstar" : "Star"} aria-pressed={m.flagged} onClick={() => onAct(m.flagged ? "UNFLAG" : "FLAG")}>
          <Star size={16} />
        </button>
      </div>
      <div className="msg-actions">
        <IconButton label="Archive" onClick={() => onAct("ARCHIVE")}><Archive size={17} /></IconButton>
        <IconButton label="Delete" onClick={() => onAct("TRASH")}><Trash2 size={17} /></IconButton>
        {m.unread
          ? <IconButton label="Mark read" onClick={() => onAct("READ")}><MailOpen size={17} /></IconButton>
          : <IconButton label="Mark unread" onClick={() => onAct("UNREAD")}><Mail size={17} /></IconButton>}
      </div>
    </li>
  );
}
