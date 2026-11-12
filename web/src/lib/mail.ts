import { create } from "zustand";
import { api } from "./api";
import { usePrefs } from "./prefs";
import { shown } from "./format";
import type { Action, Folder, MailAccount, MessageSummary } from "./types";

/** What's being looked at: an account's folder, or every inbox ("all", "INBOX"). */
export interface View {
  account: string;
  folder: string;
}

export const ALL = "all";

interface List {
  /** The view and search this list is for. */
  key: string;
  messages: MessageSummary[];
  next: string | null;
  total: number | null;
  loading: boolean;
  error: string | null;
  problems: Record<string, string>;
}

interface Mail {
  accounts: MailAccount[] | null;
  accountsError: string | null;
  folders: Record<string, Folder[]>;
  folderErrors: Record<string, string>;
  /** Each account's unread messages in its inbox. */
  unread: Record<string, number>;
  view: View;
  query: string;
  list: List;
  /** Messages ticked in the list, for doing something to all of them. */
  selected: string[];
  /** Where the last new-mail check got to. */
  cursor: string | null;
}

const emptyList: List = { key: "", messages: [], next: null, total: null, loading: false, error: null, problems: {} };

export const useMail = create<Mail>(() => ({
  accounts: null,
  accountsError: null,
  folders: {},
  folderErrors: {},
  unread: {},
  view: { account: ALL, folder: "INBOX" },
  query: "",
  list: emptyList,
  selected: [],
  cursor: null,
}));

const keyOf = (view: View, query: string) => `${view.account}\u0000${view.folder}\u0000${query}`;

/** Folders that messages move out of when they're archived, binned or marked as junk. */
const LEAVES: Action[] = ["ARCHIVE", "TRASH", "DELETE", "JUNK", "NOT_JUNK", "MOVE"];

export const mail = {
  async loadAccounts() {
    try {
      const accounts = await api.accounts();
      useMail.setState({ accounts, accountsError: null });
      accounts.forEach((a) => void mail.loadFolders(a.id));
    } catch (e) {
      useMail.setState({ accountsError: e instanceof Error ? e.message : String(e), accounts: useMail.getState().accounts ?? [] });
    }
  },

  async loadFolders(accountId: string) {
    try {
      const folders = await api.folders(accountId);
      useMail.setState((s) => {
        const { [accountId]: _, ...errors } = s.folderErrors;
        const inbox = folders.find((f) => f.role === "INBOX");
        return { folders: { ...s.folders, [accountId]: folders }, folderErrors: errors, unread: inbox ? { ...s.unread, [accountId]: inbox.unread } : s.unread };
      });
    } catch (e) {
      useMail.setState((s) => ({ folderErrors: { ...s.folderErrors, [accountId]: e instanceof Error ? e.message : String(e) } }));
    }
  },

  /** Shows [view] (and searches it for [query]), loading its first page. */
  async open(view: View, query = "") {
    const key = keyOf(view, query);
    const current = useMail.getState();
    if (current.list.key === key && !current.list.error) {
      useMail.setState({ view, query });
      return;
    }
    useMail.setState({ view, query, selected: [], list: { ...emptyList, key, loading: true } });
    await mail.load(key, view, query, null);
  },

  async loadMore() {
    const { list, view, query } = useMail.getState();
    if (!list.next || list.loading) return;
    useMail.setState({ list: { ...list, loading: true } });
    await mail.load(list.key, view, query, list.next);
  },

  /** Loads the current view again from the top (new mail, or after a change). */
  async refresh() {
    const { view, query } = useMail.getState();
    const key = keyOf(view, query);
    useMail.setState((s) => ({ list: { ...s.list, key, loading: true } }));
    await mail.load(key, view, query, null);
    if (view.account !== ALL) void mail.loadFolders(view.account);
    else useMail.getState().accounts?.forEach((a) => void mail.loadFolders(a.id));
  },

  async load(key: string, view: View, query: string, cursor: string | null) {
    try {
      const page = await api.messages(view.account, view.folder, cursor, query || undefined);
      if (useMail.getState().list.key !== key) return; // something else was opened meanwhile
      useMail.setState((s) => ({
        list: {
          key,
          messages: cursor ? [...s.list.messages, ...page.messages.filter((m) => !s.list.messages.some((o) => o.id === m.id))] : page.messages,
          next: page.next ?? null,
          total: page.total ?? null,
          loading: false,
          error: null,
          problems: page.problems ?? {},
        },
      }));
    } catch (e) {
      if (useMail.getState().list.key !== key) return;
      useMail.setState((s) => ({ list: { ...s.list, loading: false, error: e instanceof Error ? e.message : String(e) } }));
    }
  },

  /** Changes a message in the list (it's been read, flagged…). */
  update(id: string, change: Partial<MessageSummary>) {
    useMail.setState((s) => ({ list: { ...s.list, messages: s.list.messages.map((m) => (m.id === id ? { ...m, ...change } : m)) } }));
  },

  select(ids: string[]) {
    useMail.setState({ selected: ids });
  },

  /** Does [action] to [ids]: shown at once, then put right if the server says otherwise. */
  async act(ids: string[], action: Action, folder?: string): Promise<boolean> {
    if (ids.length === 0) return true;
    const before = useMail.getState().list;
    const change: Partial<MessageSummary> | null =
      action === "READ" ? { unread: false } : action === "UNREAD" ? { unread: true } : action === "FLAG" ? { flagged: true } : action === "UNFLAG" ? { flagged: false } : null;
    useMail.setState((s) => ({
      selected: s.selected.filter((id) => !ids.includes(id)),
      list: {
        ...s.list,
        messages: LEAVES.includes(action) ? s.list.messages.filter((m) => !ids.includes(m.id)) : s.list.messages.map((m) => (ids.includes(m.id) && change ? { ...m, ...change } : m)),
      },
    }));
    try {
      const result = await api.act(ids, action, folder);
      const failed = Object.values(result.failed);
      if (failed.length) throw new Error(failed[0]);
      const accounts = new Set(before.messages.filter((m) => ids.includes(m.id)).map((m) => m.accountId));
      accounts.forEach((a) => void mail.loadFolders(a));
      return true;
    } catch (e) {
      useMail.setState((s) => ({ list: s.list.key === before.key ? before : s.list }));
      throw e;
    }
  },

  /** Asks the server what's new; refreshes the list when the inbox being shown got mail. */
  async poll() {
    const { cursor } = useMail.getState();
    try {
      const news = await api.newMail(cursor);
      useMail.setState((s) => ({ cursor: news.cursor, unread: { ...s.unread, ...news.unread } }));
      if (!cursor || news.messages.length === 0) return;
      const { view, query } = useMail.getState();
      const showsInbox = view.folder === "INBOX" && !query && (view.account === ALL || news.messages.some((m) => m.accountId === view.account));
      if (showsInbox) void mail.refresh();
      else news.messages.forEach((m) => void mail.loadFolders(m.accountId));
      if (usePrefs.getState().notify && typeof Notification !== "undefined" && Notification.permission === "granted" && document.hidden) {
        const first = news.messages[0];
        const n = new Notification(news.messages.length === 1 ? shown(first.from) || "New mail" : `${news.messages.length} new messages`, {
          body: news.messages.length === 1 ? first.subject || first.preview : news.messages.slice(0, 3).map((m) => `${shown(m.from)}: ${m.subject}`).join("\n"),
          icon: "/icon.svg",
          tag: "same-new-mail",
        });
        n.onclick = () => window.focus();
      }
    } catch {
      // Tried again at the next tick.
    }
  },

  accountOf(id: string): MailAccount | undefined {
    return useMail.getState().accounts?.find((a) => a.id === id);
  },

  /** The account's folder for [role], if it has one. */
  folderFor(accountId: string, role: Folder["role"]): Folder | undefined {
    return useMail.getState().folders[accountId]?.find((f) => f.role === role);
  },
};
