import { useState } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import {
  AlertTriangle, Archive, ChevronDown, ChevronRight, File, Folder as FolderIcon, Inbox, Layers, LogOut, PenSquare, Send, Settings as SettingsIcon, Shield,
  ShieldAlert, Star, Trash2,
} from "lucide-react";
import { ALL, useMail } from "../lib/mail";
import { session, useSession } from "../lib/session";
import { ROLE_NAMES } from "../lib/format";
import type { Folder, FolderRole } from "../lib/types";
import { IconButton } from "./ui";
import { compose } from "./Compose";

const ROLE_ICONS: Record<FolderRole, React.ReactNode> = {
  INBOX: <Inbox size={18} />,
  DRAFTS: <File size={18} />,
  SENT: <Send size={18} />,
  ARCHIVE: <Archive size={18} />,
  JUNK: <ShieldAlert size={18} />,
  TRASH: <Trash2 size={18} />,
  ALL: <Layers size={18} />,
  FLAGGED: <Star size={18} />,
  OTHER: <FolderIcon size={18} />,
};

export const mailLink = (account: string, folder: string) => `/?a=${encodeURIComponent(account)}&f=${encodeURIComponent(folder)}`;

/** The brand, writing, every inbox together, then each account's folders; settings at the bottom. */
export function Sidebar({ onNavigate }: { onNavigate: () => void }) {
  const user = useSession((s) => s.user)!;
  const accounts = useMail((s) => s.accounts);
  const folders = useMail((s) => s.folders);
  const folderErrors = useMail((s) => s.folderErrors);
  const unread = useMail((s) => s.unread);
  const view = useMail((s) => s.view);
  const navigate = useNavigate();
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({});
  const totalUnread = Object.values(unread).reduce((a, b) => a + b, 0);
  const go = (account: string, folder: string) => {
    navigate(mailLink(account, folder));
    onNavigate();
  };

  return (
    <nav className="sidebar" aria-label="Mailboxes">
      <div className="brand">
        <img src="/icon.svg" alt="" width={32} height={32} />
        <span>SuperAwesome<wbr />MagicalEmail</span>
      </div>
      <button className="btn btn-primary compose-button" onClick={() => { compose.write(); onNavigate(); }}>
        <PenSquare size={18} /> Write
      </button>

      {accounts && accounts.length > 1 && (
        <button className={`nav-link ${view.account === ALL ? "active" : ""}`} onClick={() => go(ALL, "INBOX")}>
          <Inbox size={18} /> <span className="nav-text">All inboxes</span>
          {totalUnread > 0 && <span className="count">{totalUnread}</span>}
        </button>
      )}

      {accounts?.map((account) => {
        const list = folders[account.id];
        const open = !collapsed[account.id];
        return (
          <div key={account.id} className="account-group">
            <button className="account-head" onClick={() => setCollapsed({ ...collapsed, [account.id]: open })} aria-expanded={open}>
              {open ? <ChevronDown size={14} /> : <ChevronRight size={14} />}
              <span className="account-label" title={account.email}>{account.label || account.email}</span>
              {(account.problem || folderErrors[account.id]) && <span title={folderErrors[account.id] ?? account.problem ?? ""}><AlertTriangle size={14} className="warn" /></span>}
            </button>
            {open && (
              list ? (
                list.filter((f) => f.selectable || list.some((c) => c.path !== f.path && c.depth > f.depth)).map((f) => (
                  <FolderLink
                    key={f.path}
                    folder={f}
                    active={view.account === account.id && view.folder === f.path}
                    onClick={() => f.selectable && go(account.id, f.path)}
                  />
                ))
              ) : (
                <FolderLink folder={{ path: "INBOX", name: "Inbox", role: "INBOX", unread: unread[account.id] ?? 0, total: 0, depth: 0, selectable: true }}
                  active={view.account === account.id && view.folder === "INBOX"} onClick={() => go(account.id, "INBOX")} />
              )
            )}
          </div>
        );
      })}

      <div className="nav-spacer" />
      <NavLink to="/settings" className="nav-link" onClick={onNavigate}><SettingsIcon size={18} /> <span className="nav-text">Settings</span></NavLink>
      {user.role === "ADMIN" && <NavLink to="/admin" className="nav-link" onClick={onNavigate}><Shield size={18} /> <span className="nav-text">Admin</span></NavLink>}
      <div className="sidebar-user">
        <span title={user.username}>{user.displayName}</span>
        <IconButton label="Sign out" onClick={() => void session.signOut()}><LogOut size={18} /></IconButton>
      </div>
    </nav>
  );
}

function FolderLink({ folder, active, onClick }: { folder: Folder; active: boolean; onClick: () => void }) {
  const name = folder.role !== "OTHER" ? ROLE_NAMES[folder.role] : folder.name;
  const showCount = folder.unread > 0 && folder.role !== "SENT" && folder.role !== "TRASH" && folder.role !== "JUNK";
  return (
    <button
      className={`nav-link folder-link ${active ? "active" : ""} ${folder.selectable ? "" : "is-parent"}`}
      style={{ paddingLeft: 14 + Math.min(folder.depth, 4) * 14 }}
      onClick={onClick}
      title={folder.path}
    >
      {ROLE_ICONS[folder.role]} <span className="nav-text">{name}</span>
      {folder.role === "DRAFTS" && folder.total > 0 ? <span className="count quiet">{folder.total}</span> : showCount && <span className="count">{folder.unread}</span>}
    </button>
  );
}
