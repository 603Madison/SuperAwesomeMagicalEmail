import type { Address, FolderRole } from "./types";

const sameDay = (a: Date, b: Date) => a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();

/** A message's date in a list: the time today, the weekday this week, the day this year, else the date. */
export function shortDate(ms: number): string {
  if (!ms) return "";
  const date = new Date(ms);
  const now = new Date();
  if (sameDay(date, now)) return date.toLocaleTimeString(undefined, { hour: "numeric", minute: "2-digit" });
  const days = (now.getTime() - date.getTime()) / 86_400_000;
  if (days < 6 && days > 0) return date.toLocaleDateString(undefined, { weekday: "short" });
  if (date.getFullYear() === now.getFullYear()) return date.toLocaleDateString(undefined, { day: "numeric", month: "short" });
  return date.toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

/** A message's date in full, for reading it. */
export function fullDate(ms: number): string {
  if (!ms) return "";
  return new Date(ms).toLocaleString(undefined, { weekday: "short", day: "numeric", month: "short", year: "numeric", hour: "numeric", minute: "2-digit" });
}

/** "3 minutes ago", for sign-ins. */
export function when(ms: number): string {
  const seconds = Math.round((Date.now() - ms) / 1000);
  if (seconds < 60) return "just now";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} minute${minutes === 1 ? "" : "s"} ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} hour${hours === 1 ? "" : "s"} ago`;
  const days = Math.round(hours / 24);
  if (days < 30) return `${days} day${days === 1 ? "" : "s"} ago`;
  return new Date(ms).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

export function fileSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${Math.round(bytes / 1024)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(bytes < 10 * 1024 * 1024 ? 1 : 0)} MB`;
}

export const shown = (a?: Address | null) => (a ? (a.name?.trim() || a.email) : "");

export const full = (a: Address) => (a.name?.trim() ? `${a.name} <${a.email}>` : a.email);

/** The first letter of a name, for its circle. */
export const initial = (a?: Address | null) => (shown(a).replace(/[^\p{L}\p{N}]/gu, "").charAt(0) || "?").toUpperCase();

/** A steady colour for a person's circle, from a set that reads well in light and dark. */
const AVATAR_COLOURS = ["#6a3fd6", "#0f9d77", "#1d5fc9", "#b4471a", "#8e3f5c", "#0e8fa3", "#7c5b12", "#4f6b1f", "#a3416a", "#3d57b3"];
export function avatarColour(seed: string): string {
  let hash = 0;
  for (let i = 0; i < seed.length; i++) hash = (hash * 31 + seed.charCodeAt(i)) | 0;
  return AVATAR_COLOURS[Math.abs(hash) % AVATAR_COLOURS.length];
}

export const ROLE_NAMES: Record<FolderRole, string> = {
  INBOX: "Inbox", DRAFTS: "Drafts", SENT: "Sent", ARCHIVE: "Archive", JUNK: "Junk", TRASH: "Trash", ALL: "All mail", FLAGGED: "Starred", OTHER: "",
};

/** Parses "Name <a@b>", or just "a@b", typed into a recipient field. */
export function parseAddress(text: string): Address | null {
  const t = text.trim().replace(/[,;]+$/, "");
  const angle = t.match(/^(.*)<\s*([^<>\s]+@[^<>\s]+)\s*>$/);
  if (angle) return { email: angle[2], name: angle[1].trim().replace(/^"|"$/g, "") || null };
  return /^[^\s@<>]+@[^\s@<>]+\.[^\s@<>]+$/.test(t) ? { email: t } : null;
}

/** Text escaped for HTML, with its lines and its links kept. */
export function textToHtml(text: string): string {
  const escaped = text.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");
  return escaped.replace(/\n/g, "<br>");
}
