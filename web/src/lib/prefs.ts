import { create } from "zustand";

/** Choices kept in this browser: how things look, and how mail is read. */
interface Prefs {
  /** All text in Lexend Bold, like a phone's Bold text setting. */
  boldText: boolean;
  /** The colours: Aurora (purple and green), Violet, Ocean or Ember. */
  palette: Palette;
  /** Light, dark, or as the system is. */
  scheme: "system" | "light" | "dark";
  /** Show images from the web in messages without asking. */
  alwaysShowImages: boolean;
  /** Opening a message marks it read. */
  markReadOnOpen: boolean;
  /** Show the browser's notifications for new mail (while a tab is open). */
  notify: boolean;
}

export type Palette = "aurora" | "violet" | "ocean" | "ember";

/** The themes, with the two colours each shows in Settings: the same as the family's other apps. */
export const PALETTES: { id: Palette; name: string; swatch: [string, string] }[] = [
  { id: "aurora", name: "Aurora", swatch: ["#6a3fd6", "#12a37c"] },
  { id: "violet", name: "Violet", swatch: ["#5b3fd1", "#8e3f5c"] },
  { id: "ocean", name: "Ocean", swatch: ["#1d5fc9", "#0e8fa3"] },
  { id: "ember", name: "Ember", swatch: ["#b4471a", "#9c3f60"] },
];

const KEY = "same.prefs";

function load(): Prefs {
  const defaults: Prefs = { boldText: false, palette: "aurora", scheme: "system", alwaysShowImages: false, markReadOnOpen: true, notify: false };
  try {
    return { ...defaults, ...JSON.parse(localStorage.getItem(KEY) ?? "{}") };
  } catch {
    return defaults;
  }
}

export const usePrefs = create<Prefs>(load);

/** The theme, scheme and bold text, on the page (the stylesheet picks its colours by them). */
function show(prefs: Prefs) {
  const root = document.documentElement;
  root.toggleAttribute("data-bold", prefs.boldText);
  root.dataset.palette = prefs.palette;
  if (prefs.scheme === "system") delete root.dataset.scheme;
  else root.dataset.scheme = prefs.scheme;
}
show(usePrefs.getState());

usePrefs.subscribe((prefs) => {
  show(prefs);
  try {
    localStorage.setItem(KEY, JSON.stringify(prefs));
  } catch {
    // Not saved; the choice lasts until the page is closed.
  }
});
