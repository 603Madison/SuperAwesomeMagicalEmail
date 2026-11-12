import type {
  AccountInput, Action, ActionResult, AdminSettings, AuthConfig, ComposeMode, ConnectionCheck, Contact, Draft, Folder,
  MailAccount, Mailbox, Message, MessagePage, NewMail, OidcSettings, Person, Providers, SendResult, ServerSettings, SessionInfo,
  Upload, User,
} from "./types";

/** An error from the server, with a message meant for people. */
export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
  }
}

/** Told when the server says the sign-in is gone, so the app can show the sign-in page. */
let onSignedOut: (() => void) | null = null;
export function setSignedOutHandler(handler: () => void) {
  onSignedOut = handler;
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  // Every change carries this header, which other sites' pages can't send.
  if (method !== "GET") headers["X-SAME"] = "1";
  const isForm = body instanceof FormData;
  if (body !== undefined && !isForm) headers["Content-Type"] = "application/json";
  let response: Response;
  try {
    response = await fetch(path, {
      method, headers, credentials: "same-origin",
      body: body === undefined ? undefined : isForm ? body : JSON.stringify(body),
    });
  } catch {
    throw new ApiError(0, "Can't reach the server");
  }
  const text = await response.text();
  const data = text ? safeJson(text) : null;
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith("/api/auth/")) onSignedOut?.();
    const message = (data && typeof data === "object" && "error" in data && typeof data.error === "string") ? data.error : `The server answered ${response.status}`;
    throw new ApiError(response.status, message);
  }
  return data as T;
}

function safeJson(text: string): unknown {
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

const get = <T>(path: string) => request<T>("GET", path);
const post = <T>(path: string, body?: unknown) => request<T>("POST", path, body ?? {});
const patch = <T>(path: string, body: unknown) => request<T>("PATCH", path, body);
const put = <T>(path: string, body: unknown) => request<T>("PUT", path, body);
const del = <T>(path: string) => request<T>("DELETE", path);
const q = encodeURIComponent;

/** Uploads a file to attach, telling [onProgress] how far it's got (0–1). */
function upload(file: File, onProgress?: (fraction: number) => void): Promise<Upload> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open("POST", "/api/uploads");
    xhr.setRequestHeader("X-SAME", "1");
    xhr.upload.onprogress = (e) => e.lengthComputable && onProgress?.(e.loaded / e.total);
    xhr.onload = () => {
      const data = safeJson(xhr.responseText) as Upload[] | { error?: string } | null;
      if (xhr.status >= 200 && xhr.status < 300 && Array.isArray(data)) resolve(data[0]);
      else reject(new ApiError(xhr.status, (data && !Array.isArray(data) && data.error) || `The server answered ${xhr.status}`));
    };
    xhr.onerror = () => reject(new ApiError(0, "Can't reach the server"));
    const form = new FormData();
    form.append("file", file, file.name);
    xhr.send(form);
  });
}

export const api = {
  authConfig: () => get<AuthConfig>("/api/auth/config"),
  login: (username: string, password: string) => post<{ user: User }>("/api/auth/login", { username, password }),
  logout: () => post<unknown>("/api/auth/logout"),
  appCode: (challenge: string) => post<{ code: string }>("/api/auth/app/code", { challenge }),

  me: () => get<{ user: User; session: SessionInfo }>("/api/me"),
  updateName: (displayName: string) => patch<User>("/api/me", { displayName }),
  changePassword: (current: string, next: string) => post<unknown>("/api/me/password", { current, new: next }),
  sessions: () => get<SessionInfo[]>("/api/me/sessions"),
  revokeSession: (id: string) => del<unknown>(`/api/me/sessions/${q(id)}`),

  providers: () => get<Providers>("/api/providers"),
  accounts: () => get<MailAccount[]>("/api/accounts"),
  addAccount: (input: AccountInput) => post<MailAccount>("/api/accounts", input),
  checkAccount: (input: AccountInput) => post<ConnectionCheck>("/api/accounts/check", input),
  checkSavedAccount: (id: string) => post<ConnectionCheck>(`/api/accounts/${q(id)}/check`),
  updateAccount: (id: string, input: AccountInput) => patch<MailAccount>(`/api/accounts/${q(id)}`, input),
  removeAccount: (id: string) => del<unknown>(`/api/accounts/${q(id)}`),
  setSignature: (id: string, signature: string) => put<MailAccount>(`/api/accounts/${q(id)}/signature`, { signature }),
  orderAccounts: (ids: string[]) => put<unknown>("/api/accounts/order", { ids }),
  folders: (accountId: string) => get<Folder[]>(`/api/accounts/${q(accountId)}/folders`),

  messages: (account: string, folder: string, cursor?: string | null, query?: string) =>
    get<MessagePage>(`/api/messages?account=${q(account)}&folder=${q(folder)}${cursor ? `&cursor=${q(cursor)}` : ""}${query ? `&q=${q(query)}` : ""}`),
  message: (id: string, images: boolean, markRead: boolean) => get<Message>(`/api/messages/${q(id)}?images=${images ? 1 : 0}&read=${markRead ? 1 : 0}`),
  act: (ids: string[], action: Action, folder?: string) => post<ActionResult>("/api/messages/actions", { ids, action, folder }),
  compose: (id: string, mode: ComposeMode) =>
    get<Draft>(`/api/messages/${q(id)}/compose?mode=${mode}&tz=${q(Intl.DateTimeFormat().resolvedOptions().timeZone || "UTC")}`),
  send: (draft: Draft) => post<SendResult>("/api/send", draft),
  saveDraft: (draft: Draft) => post<{ id: string | null }>("/api/drafts", draft),
  upload,
  removeUpload: (id: string) => del<unknown>(`/api/uploads/${q(id)}`),
  contacts: (query: string) => get<Contact[]>(`/api/contacts?q=${q(query)}`),
  newMail: (cursor?: string | null) => get<NewMail>(`/api/mail/new${cursor ? `?cursor=${q(cursor)}` : ""}`),

  people: () => get<Person[]>("/api/admin/users"),
  addPerson: (person: { username: string; displayName: string; email: string; password?: string; role: string }) => post<Person>("/api/admin/users", person),
  updatePerson: (id: string, change: { displayName?: string; role?: string; disabled?: boolean; email?: string }) => patch<User>(`/api/admin/users/${q(id)}`, change),
  deletePerson: (id: string) => del<unknown>(`/api/admin/users/${q(id)}`),
  resetPassword: (id: string, password: string) => post<unknown>(`/api/admin/users/${q(id)}/password`, { password }),
  signOutPerson: (id: string) => del<unknown>(`/api/admin/users/${q(id)}/sessions`),
  mailboxes: () => get<Mailbox[]>("/api/admin/mailboxes"),
  addMailbox: (input: AccountInput) => post<Mailbox>("/api/admin/mailboxes", input),
  checkMailbox: (input: AccountInput) => post<ConnectionCheck>("/api/admin/mailboxes/check", input),
  checkSavedMailbox: (id: string) => post<ConnectionCheck>(`/api/admin/mailboxes/${q(id)}/check`),
  updateMailbox: (id: string, input: AccountInput) => patch<Mailbox>(`/api/admin/mailboxes/${q(id)}`, input),
  setMembers: (id: string, userIds: string[]) => put<Mailbox>(`/api/admin/mailboxes/${q(id)}/members`, { userIds }),
  removeMailbox: (id: string) => del<unknown>(`/api/admin/mailboxes/${q(id)}`),
  adminSettings: () => get<AdminSettings>("/api/admin/settings"),
  saveSettings: (settings: ServerSettings, oidcClientSecret?: string) => put<AdminSettings>("/api/admin/settings", { settings, oidcClientSecret }),
  testOidc: (oidc: OidcSettings) => post<{ ok: boolean; message: string }>("/api/admin/oidc/test", oidc),
  serverLog: (warnings: boolean) => get<string[]>(`/api/admin/log${warnings ? "?warnings=1" : ""}`),
};
