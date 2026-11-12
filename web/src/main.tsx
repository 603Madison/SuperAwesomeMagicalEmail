import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "@fontsource/lexend/latin-400.css";
import "@fontsource/lexend/latin-500.css";
import "@fontsource/lexend/latin-600.css";
import "@fontsource/lexend/latin-700.css";
import "@fontsource/lexend/latin-ext-400.css";
import "@fontsource/lexend/latin-ext-500.css";
import "@fontsource/lexend/latin-ext-600.css";
import "@fontsource/lexend/latin-ext-700.css";
import "./styles.css";
import "./lib/prefs";
import { App } from "./App";

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
