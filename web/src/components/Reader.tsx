import { useEffect, useRef, useState } from "react";
import {
  Archive, ArrowLeft, Download, FileText, FolderInput, ImageIcon, Mail, MailX, MoreHorizontal, Paperclip, Reply, ReplyAll, Forward, ShieldAlert,
  ShieldCheck, Star, Trash2,
} from "lucide-react";
import { api } from "../lib/api";
import { mail, useMail } from "../lib/mail";
import { usePrefs } from "../lib/prefs";
import { avatarColour, fileSize, full, fullDate, initial, parseAddress, ROLE_NAMES, shown } from "../lib/format";
import type { Address, Message } from "../lib/types";
import { Button, Empty, ErrorNotice, IconButton, Menu, Spinner, errorToast, toast } from "./ui";
import { compose } from "./Compose";

/** One message: who it's from and to, what it says, its attachments, and what can be done with it. */
export function Reader({ id, onClose, onGone }: { id: string; onClose: () => void; onGone: () => void }) {
  const markRead = usePrefs((p) => p.markReadOnOpen);
  const alwaysImages = usePrefs((p) => p.alwaysShowImages);
  const [message, setMessage] = useState<Message | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [images, setImages] = useState(alwaysImages);
  const folders = useMail((s) => (message ? s.folders[message.summary.accountId] : undefined));

  useEffect(() => {
    let cancelled = false;
    setError(null);
    api.message(id, images, markRead).then(
      (m) => {
        if (cancelled) return;
        setMessage(m);
        if (markRead) {
          mail.update(id, { unread: false });
          void mail.loadFolders(m.summary.accountId);
        }
      },
      (e) => { if (!cancelled) setError(e instanceof Error ? e.message : String(e)); },
    );
    return () => { cancelled = true; };
  }, [id, images, markRead]);

  if (error) return <div className="reader"><ReaderBar onClose={onClose} /><div className="reader-body"><ErrorNotice message={error} /></div></div>;
  if (!message || message.summary.id !== id) return <div className="reader"><ReaderBar onClose={onClose} /><div className="center-pad"><Spinner size={28} /></div></div>;

  const s = message.summary;
  const folder = folders?.find((f) => f.path === s.folder);
  const act = async (action: Parameters<typeof mail.act>[1], target?: string, leaves = true) => {
    try {
      await mail.act([s.id], action, target);
      if (leaves) onGone();
      else if (action === "FLAG" || action === "UNFLAG") setMessage({ ...message, summary: { ...s, flagged: action === "FLAG" } });
    } catch (e) { errorToast(e); }
  };
  const attachments = message.attachments.filter((a) => !a.inline);
  const moveTargets = (folders ?? []).filter((f) => f.selectable && f.path !== s.folder);

  return (
    <article className="reader" aria-label={s.subject || "Message"}>
      <ReaderBar onClose={onClose}>
        {s.draft ? (
          <Button variant="primary" onClick={() => void compose.from(s.id, "DRAFT")}>Edit draft</Button>
        ) : (
          <>
            <IconButton label="Reply (r)" onClick={() => void compose.from(s.id, "REPLY")}><Reply size={19} /></IconButton>
            <IconButton label="Reply to all (a)" onClick={() => void compose.from(s.id, "REPLY_ALL")}><ReplyAll size={19} /></IconButton>
            <IconButton label="Forward (f)" onClick={() => void compose.from(s.id, "FORWARD")}><Forward size={19} /></IconButton>
          </>
        )}
        <span className="bar-gap" />
        {folder?.role !== "ARCHIVE" && <IconButton label="Archive (e)" onClick={() => void act("ARCHIVE")}><Archive size={19} /></IconButton>}
        <IconButton label={folder?.role === "TRASH" ? "Delete for good" : "Delete (#)"} onClick={() => void act("TRASH")}><Trash2 size={19} /></IconButton>
        <IconButton label="Mark unread" onClick={() => void act("UNREAD", undefined, true)}><Mail size={19} /></IconButton>
        <IconButton label={s.flagged ? "Unstar" : "Star"} active={s.flagged} onClick={() => void act(s.flagged ? "UNFLAG" : "FLAG", undefined, false)}>
          <Star size={19} fill={s.flagged ? "currentColor" : "none"} />
        </IconButton>
        {moveTargets.length > 0 && (
          <Menu label="Move to" trigger={<FolderInput size={19} />} items={moveTargets.map((f) => ({
            label: f.role !== "OTHER" ? ROLE_NAMES[f.role] : f.name,
            onSelect: () => void act("MOVE", f.path),
          }))} />
        )}
        <Menu label="More" trigger={<MoreHorizontal size={19} />} items={[
          folder?.role === "JUNK"
            ? { label: "Not junk", icon: <ShieldCheck size={18} />, onSelect: () => void act("NOT_JUNK") }
            : { label: "Junk", icon: <ShieldAlert size={18} />, onSelect: () => void act("JUNK") },
          { label: "Download (.eml)", icon: <Download size={18} />, onSelect: () => { window.location.href = message.rawUrl; } },
          ...(message.unsubscribe ? [{ label: "Unsubscribe", icon: <MailX size={18} />, onSelect: () => unsubscribe(message.unsubscribe!) }] : []),
        ]} />
      </ReaderBar>

      <div className="reader-body">
        <h1 className="reader-subject">{s.subject || "(no subject)"}</h1>
        <div className="reader-from">
          <span className="avatar big" style={{ background: avatarColour(s.from?.email ?? "?") }}><span className="avatar-letter">{initial(s.from)}</span></span>
          <div className="reader-people">
            <div className="reader-sender">
              <strong>{shown(s.from) || "(unknown sender)"}</strong>
              {s.from?.name && <span className="muted"> &lt;{s.from.email}&gt;</span>}
            </div>
            <div className="reader-to muted small">
              <People label="To" list={s.to} />
              {message.cc.length > 0 && <People label="Cc" list={message.cc} />}
              {message.bcc.length > 0 && <People label="Bcc" list={message.bcc} />}
              {message.replyTo.length > 0 && <People label="Replies go to" list={message.replyTo} />}
            </div>
          </div>
          <time className="reader-date muted small" dateTime={new Date(s.date).toISOString()}>{fullDate(s.date)}</time>
        </div>

        {message.remoteImages && !message.imagesShown && (
          <div className="images-notice">
            <ImageIcon size={18} />
            <span>Images from the web are hidden, so the sender can't tell when you read this.</span>
            <Button variant="ghost" onClick={() => setImages(true)}>Show images</Button>
          </div>
        )}

        {message.html ? <HtmlBody html={message.html} /> : <PlainBody text={message.text} />}

        {attachments.length > 0 && (
          <section className="attachments" aria-label="Attachments">
            <h3><Paperclip size={16} /> {attachments.length === 1 ? "1 attachment" : `${attachments.length} attachments`}</h3>
            <ul>
              {attachments.map((a) => (
                <li key={a.part}>
                  <a className="attachment" href={`${a.url}?download=1`} download={a.filename}>
                    <span className="attachment-icon">{a.contentType.startsWith("image/") ? <ImageIcon size={20} /> : <FileText size={20} />}</span>
                    <span className="attachment-text">
                      <span className="attachment-name">{a.filename}</span>
                      <span className="attachment-size">{a.size ? fileSize(a.size) : a.contentType}</span>
                    </span>
                    <Download size={16} className="muted" />
                  </a>
                </li>
              ))}
            </ul>
          </section>
        )}

        {!s.draft && (
          <div className="reader-replies">
            <Button onClick={() => void compose.from(s.id, "REPLY")}><Reply size={16} /> Reply</Button>
            {message.cc.length + s.to.length > 1 && <Button onClick={() => void compose.from(s.id, "REPLY_ALL")}><ReplyAll size={16} /> Reply all</Button>}
            <Button onClick={() => void compose.from(s.id, "FORWARD")}><Forward size={16} /> Forward</Button>
          </div>
        )}
      </div>
    </article>
  );
}

function unsubscribe(link: string) {
  if (link.startsWith("mailto:")) {
    const address = parseAddress(decodeURIComponent(link.slice(7).split("?")[0]));
    if (address) compose.write([address]);
    toast("Send that message to unsubscribe");
  } else {
    window.open(link, "_blank", "noopener,noreferrer");
  }
}

function ReaderBar({ onClose, children }: { onClose: () => void; children?: React.ReactNode }) {
  return (
    <div className="reader-bar">
      <IconButton label="Back to the list" onClick={onClose}><ArrowLeft size={20} /></IconButton>
      {children}
    </div>
  );
}

function People({ label, list }: { label: string; list: Address[] }) {
  if (list.length === 0) return null;
  return (
    <div className="people-line">
      <span className="people-label">{label}</span>{" "}
      {list.map((a, i) => (
        <span key={a.email + i}>
          <button className="person" title={full(a)} onClick={() => compose.write([a])}>{shown(a)}</button>
          {i < list.length - 1 ? ", " : ""}
        </span>
      ))}
    </div>
  );
}

/**
 * HTML mail, in a frame with no scripts (the server has taken them out too) that's as tall as
 * what's in it. Links open in a new tab; mail links start a message here.
 */
function HtmlBody({ html }: { html: string }) {
  const frame = useRef<HTMLIFrameElement>(null);
  const [height, setHeight] = useState(120);

  useEffect(() => {
    const iframe = frame.current;
    if (!iframe) return;
    let observer: ResizeObserver | null = null;
    const fit = () => {
      const doc = iframe.contentDocument;
      if (!doc?.documentElement) return;
      setHeight(Math.max(60, doc.documentElement.scrollHeight));
    };
    const onLoad = () => {
      const doc = iframe.contentDocument;
      if (!doc) return;
      fit();
      observer?.disconnect();
      observer = new ResizeObserver(fit);
      observer.observe(doc.documentElement);
      doc.querySelectorAll("img").forEach((img) => img.addEventListener("load", fit));
      doc.addEventListener("click", (e) => {
        const link = (e.target as Element | null)?.closest?.("a");
        const href = link?.getAttribute("href") ?? "";
        if (href.toLowerCase().startsWith("mailto:")) {
          e.preventDefault();
          const address = parseAddress(decodeURIComponent(href.slice(7).split("?")[0]));
          if (address) compose.write([address]);
        }
      });
    };
    iframe.addEventListener("load", onLoad);
    return () => {
      iframe.removeEventListener("load", onLoad);
      observer?.disconnect();
    };
  }, [html]);

  return (
    <div className="html-paper">
      <iframe
        ref={frame}
        title="Message"
        srcDoc={html}
        sandbox="allow-same-origin allow-popups allow-popups-to-escape-sandbox"
        referrerPolicy="no-referrer"
        style={{ height }}
      />
    </div>
  );
}

const URL_PATTERN = /(https?:\/\/[^\s<>"']+[^\s<>"'.,;:!?)\]])/g;

/** Plain-text mail, in the app's own look, with its web addresses as links and quotes set apart. */
function PlainBody({ text }: { text: string }) {
  const lines = text.replace(/\r\n/g, "\n").split("\n");
  const blocks: { quoted: boolean; lines: string[] }[] = [];
  lines.forEach((line) => {
    const quoted = line.startsWith(">");
    const last = blocks[blocks.length - 1];
    if (last && last.quoted === quoted) last.lines.push(line);
    else blocks.push({ quoted, lines: [line] });
  });
  return (
    <div className="plain-text">
      {blocks.map((b, i) => (b.quoted
        ? <blockquote key={i}>{linkify(b.lines.map((l) => l.replace(/^>\s?/, "")).join("\n"))}</blockquote>
        : <div key={i}>{linkify(b.lines.join("\n"))}</div>))}
    </div>
  );
}

function linkify(text: string): React.ReactNode[] {
  return text.split(URL_PATTERN).map((part, i) => (i % 2 === 1 ? <a key={i} href={part} target="_blank" rel="noopener noreferrer">{part}</a> : part));
}

export function NoMessage() {
  const accounts = useMail((s) => s.accounts);
  if (accounts && accounts.length === 0) return null;
  return (
    <div className="reader reader-empty">
      <Empty icon={<Mail size={30} />} title="Pick a message">Choose one from the list to read it here.</Empty>
    </div>
  );
}
