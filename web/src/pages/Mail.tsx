import { useEffect } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { Inbox, Settings as SettingsIcon } from "lucide-react";
import { ALL, mail, useMail } from "../lib/mail";
import { useSession } from "../lib/session";
import type { MessageSummary } from "../lib/types";
import { MessageList } from "../components/MessageList";
import { NoMessage, Reader } from "../components/Reader";
import { compose } from "../components/Compose";
import { Empty, Spinner, errorToast } from "../components/ui";

/** Reading mail: the list, and the message picked from it. Where you are is in the address, so links and Back work. */
export function Mail({ onMenu }: { onMenu: () => void }) {
  const [params, setParams] = useSearchParams();
  const accounts = useMail((s) => s.accounts);
  const accountsError = useMail((s) => s.accountsError);
  const list = useMail((s) => s.list.messages);
  const user = useSession((s) => s.user)!;
  const account = params.get("a") ?? (accounts && accounts.length === 1 ? accounts[0].id : ALL);
  const folder = params.get("f") ?? "INBOX";
  const query = params.get("q") ?? "";
  const openId = params.get("m");

  useEffect(() => {
    if (accounts && accounts.length > 0) void mail.open({ account, folder }, query);
  }, [account, folder, query, accounts]);

  const open = (m: MessageSummary | null, replace = false) => {
    const next = new URLSearchParams(params);
    if (m) next.set("m", m.id);
    else next.delete("m");
    setParams(next, { replace });
    if (m?.draft) void compose.from(m.id, "DRAFT");
  };

  /** After the open message leaves the list (archived, deleted): the next one, or none. */
  const afterGone = () => {
    const index = list.findIndex((m) => m.id === openId);
    const next = list[index + 1] ?? list[index - 1] ?? null;
    open(next && next.id !== openId && window.innerWidth > 1000 ? next : null, true);
  };

  useKeys(openId, list, open, afterGone);

  if (!accounts) return <div className="mail-loading"><Spinner size={28} /></div>;
  if (accounts.length === 0) {
    return (
      <div className="mail-empty">
        <Empty icon={<Inbox size={32} />} title={`Welcome, ${user.displayName}`}>
          {accountsError ?? (
            <>
              <p>There's no mailbox here for you yet. An admin can set one up for you, or you can add your own account.</p>
              <p><Link to="/settings" className="btn btn-primary"><SettingsIcon size={16} /> Add an account</Link></p>
            </>
          )}
        </Empty>
      </div>
    );
  }

  return (
    <div className={`mail ${openId ? "has-open" : ""}`}>
      <MessageList openId={openId} onOpen={(m) => open(m)} onMenu={onMenu} />
      <section className="reader-pane" aria-label="Message">
        {openId ? <Reader key={openId} id={openId} onClose={() => open(null)} onGone={afterGone} /> : <NoMessage />}
      </section>
    </div>
  );
}

/** j/k next and previous, r/a/f reply and forward, e archive, # delete, c write, / search, Esc back. */
function useKeys(openId: string | null, list: MessageSummary[], open: (m: MessageSummary | null, replace?: boolean) => void, afterGone: () => void) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      if (e.ctrlKey || e.metaKey || e.altKey) return;
      if (target && (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName))) return;
      if (document.querySelector(".modal-backdrop, .compose:not(.compose-min)")) return;
      const index = list.findIndex((m) => m.id === openId);
      const current = index >= 0 ? list[index] : null;
      switch (e.key) {
        case "j": if (list[index + 1]) open(list[index + 1], true); break;
        case "k": if (index > 0) open(list[index - 1], true); break;
        case "c": compose.write(); break;
        case "/": document.getElementById("mail-search")?.focus(); break;
        case "Escape": if (current) open(null); break;
        case "r": if (current) void compose.from(current.id, "REPLY"); break;
        case "a": if (current) void compose.from(current.id, "REPLY_ALL"); break;
        case "f": if (current) void compose.from(current.id, "FORWARD"); break;
        case "e": if (current) mail.act([current.id], "ARCHIVE").then(afterGone, errorToast); break;
        case "#": if (current) mail.act([current.id], "TRASH").then(afterGone, errorToast); break;
        default: return;
      }
      e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [openId, list, open, afterGone]);
}
