import { useState } from "react";
import { useSearchParams } from "react-router-dom";
import { Monitor, Smartphone } from "lucide-react";
import { api } from "../lib/api";
import { useSession } from "../lib/session";
import { Button } from "../components/ui";

/** Where the app sends the code (its own address, registered on the phone). */
const APP_REDIRECT = "samemail://signed-in";

/**
 * The apps' sign-in (Android and Linux): the app opens this page in a browser; once the person is
 * signed in here (through Authentik, usually) and agrees, the app gets a one-time code for its own
 * sign-in, which only works with the secret the app made for it (PKCE).
 */
export function ConnectApp() {
  const [params] = useSearchParams();
  const user = useSession((s) => s.user);
  const config = useSession((s) => s.config);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const challenge = params.get("challenge") ?? "";
  const state = params.get("state") ?? "";
  const device = params.get("device") ?? "your phone";
  const valid = /^[A-Za-z0-9_-]{43}$/.test(challenge) && /^[A-Za-z0-9_-]{8,128}$/.test(state);

  const approve = async () => {
    setBusy(true);
    try {
      const { code } = await api.appCode(challenge);
      setDone(true);
      window.location.href = `${APP_REDIRECT}?code=${encodeURIComponent(code)}&state=${encodeURIComponent(state)}`;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="connect-icon">{/linux|desktop|computer/i.test(device) ? <Monitor size={36} /> : <Smartphone size={36} />}</div>
        {!valid ? (
          <>
            <h1>That link isn't right</h1>
            <p className="login-sub">Start signing in from the SuperAwesomeMagicalEmail app again.</p>
          </>
        ) : done ? (
          <>
            <h1>You're signed in</h1>
            <p className="login-sub">Head back to the app. You can close this tab.</p>
          </>
        ) : (
          <>
            <h1>Sign in to the app?</h1>
            <p className="login-sub">
              The SuperAwesomeMagicalEmail app on {device} wants to sign in to {config?.serverName ?? "this server"} as{" "}
              <strong>{user?.displayName}</strong> ({user?.username}). It will read and send your mail, from every account you have here.
            </p>
            {error && <div className="notice notice-error">{error}</div>}
            <Button variant="primary" className="wide" busy={busy} onClick={approve}>Sign in to the app</Button>
            <Button variant="ghost" className="wide" onClick={() => window.close()}>Cancel</Button>
            <p className="login-version">Only continue if you started this from the app yourself.</p>
          </>
        )}
      </div>
    </div>
  );
}
