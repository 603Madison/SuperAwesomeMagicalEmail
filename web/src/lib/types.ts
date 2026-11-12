// The server's API models: copies of shared/src/main/kotlin/com/email/api (keep them in step).

export type Role = "ADMIN" | "USER";

export interface User {
  id: string;
  username: string;
  displayName: string;
  email?: string | null;
  role: Role;
  breakGlass: boolean;
  disabled: boolean;
  oidc: boolean;
  hasPassword: boolean;
  createdAt: number;
  lastLoginAt?: number | null;
  waitingForSignIn: boolean;
}

export interface SessionInfo {
  id: string;
  kind: "WEB" | "APP";
  name: string;
  createdAt: number;
  lastSeenAt: number;
  expiresAt: number;
  current: boolean;
}

export interface AuthConfig {
  serverName: string;
  version: string;
  localLogin: boolean;
  oidc?: { label: string } | null;
}

export type Security = "SSL" | "STARTTLS" | "NONE";
export interface Endpoint { host: string; port: number; security: Security }
export type Provider = "PURELYMAIL" | "GMAIL" | "ICLOUD" | "FASTMAIL" | "YAHOO" | "OTHER";

export interface ProviderPreset {
  provider: Provider;
  label: string;
  description: string;
  imap: Endpoint;
  smtp: Endpoint;
  saveSent: boolean;
  passwordHelp: string;
}

export interface Providers { presets: ProviderPreset[]; canAddPersonal: boolean }

export interface MailAccount {
  id: string;
  email: string;
  name: string;
  label: string;
  provider: Provider;
  username: string;
  imap: Endpoint;
  smtp: Endpoint;
  saveSent: boolean;
  managed: boolean;
  editable: boolean;
  signature: string;
  problem?: string | null;
}

export interface Member { userId: string; username: string; displayName: string; waitingForSignIn: boolean }
export interface Mailbox { account: MailAccount; members: Member[]; createdAt: number }

export interface AccountInput {
  provider: Provider;
  email: string;
  name: string;
  label: string;
  username: string;
  password?: string | null;
  imap?: Endpoint | null;
  smtp?: Endpoint | null;
  saveSent?: boolean | null;
  members?: string[] | null;
  skipCheck?: boolean;
}

export interface ConnectionCheck { ok: boolean; imap: string; smtp: string }

export type FolderRole = "INBOX" | "DRAFTS" | "SENT" | "ARCHIVE" | "JUNK" | "TRASH" | "ALL" | "FLAGGED" | "OTHER";

export interface Folder {
  path: string;
  name: string;
  role: FolderRole;
  unread: number;
  total: number;
  depth: number;
  selectable: boolean;
}

export interface Address { email: string; name?: string | null }

export interface MessageSummary {
  id: string;
  accountId: string;
  folder: string;
  from?: Address | null;
  to: Address[];
  subject: string;
  preview: string;
  date: number;
  unread: boolean;
  flagged: boolean;
  answered: boolean;
  draft: boolean;
  hasAttachments: boolean;
  size: number;
}

export interface MessagePage {
  messages: MessageSummary[];
  next?: string | null;
  total?: number | null;
  problems: Record<string, string>;
}

export interface Attachment { part: string; filename: string; contentType: string; size: number; inline: boolean; url: string }

export interface Message {
  summary: MessageSummary;
  cc: Address[];
  bcc: Address[];
  replyTo: Address[];
  html?: string | null;
  text: string;
  remoteImages: boolean;
  imagesShown: boolean;
  attachments: Attachment[];
  rawUrl: string;
  unsubscribe?: string | null;
}

export type Action = "READ" | "UNREAD" | "FLAG" | "UNFLAG" | "ARCHIVE" | "TRASH" | "DELETE" | "JUNK" | "NOT_JUNK" | "MOVE";

export interface ActionResult { done: number; failed: Record<string, string> }

export interface AttachmentRef { messageId: string; part: string; filename: string; size: number }

export interface Draft {
  accountId: string;
  to: Address[];
  cc: Address[];
  bcc: Address[];
  subject: string;
  text: string;
  html?: string | null;
  uploads: string[];
  attachments: AttachmentRef[];
  inReplyTo?: string | null;
  forwardOf?: string | null;
  draftId?: string | null;
}

export type ComposeMode = "REPLY" | "REPLY_ALL" | "FORWARD" | "DRAFT";

export interface Upload { id: string; filename: string; contentType: string; size: number }
export interface SendResult { ok: boolean; savedToSent: boolean; note?: string | null }
export interface Contact { email: string; name?: string | null }
export interface NewMail { cursor: string; messages: MessageSummary[]; unread: Record<string, number> }

// Admin

export interface OidcSettings {
  enabled: boolean;
  issuer: string;
  clientId: string;
  scopes: string;
  buttonLabel: string;
  usernameClaim: string;
  groupsClaim: string;
  userGroup: string;
  adminGroup: string;
  autoCreateUsers: boolean;
}

export interface ServerSettings {
  serverName: string;
  publicUrl: string;
  localLogin: boolean;
  personalAccounts: boolean;
  oidc: OidcSettings;
}

export interface AdminSettings {
  settings: ServerSettings;
  oidcClientSecretSet: boolean;
  redirectUri: string;
  fromEnvironment: string[];
  version: string;
}

export interface Person { user: User; mailboxes: string[] }
