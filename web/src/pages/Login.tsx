import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { KeyRound, LogIn } from "lucide-react";
import { api } from "../lib/api";
import { session, useSession } from "../lib/session";
import { Button, Field } from "../components/ui";

/** Only addresses on this server. */
export function safeReturn(path: string | null): string {
  return path && path.startsWith("/") && !path.startsWith("//") && !path.startsWith("/\\") ? path : "/";
}

export function Login() {
  const config = useSession((s) => s.config);
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(params.get("error"));
  const [showPassword, setShowPassword] = useState(false);
  const returnTo = safeReturn(params.get("return"));
  const passwordForm = config?.localLogin || showPassword || !config?.oidc;

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const { user } = await api.login(username, password);
      session.signedIn(user);
      navigate(returnTo, { replace: true });
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err));
      setBusy(false);
    }
  };

  return (
    <div className="login-page">
      <div className="login-card">
        <img src="/icon.svg" alt="" className="login-logo" width={72} height={72} />
        <h1>{config?.serverName ?? "SuperAwesomeMagicalEmail"}</h1>
        <p className="login-sub">Your mail, on your server</p>
        {error && <div className="notice notice-error" role="alert">{error}</div>}
        {config?.oidc && (
          <Button variant="primary" className="wide" onClick={() => { window.location.href = `/api/auth/oidc/start?return=${encodeURIComponent(returnTo)}`; }}>
            <LogIn size={18} /> {config.oidc.label}
          </Button>
        )}
        {config?.oidc && passwordForm && <div className="divider"><span>or</span></div>}
        {passwordForm ? (
          <form onSubmit={submit} className="login-form">
            <Field label="Username">
              <input autoFocus={!config?.oidc} autoComplete="username" value={username} onChange={(e) => setUsername(e.target.value)} required />
            </Field>
            <Field label="Password">
              <input type="password" autoComplete="current-password" value={password} onChange={(e) => setPassword(e.target.value)} required />
            </Field>
            <Button variant={config?.oidc ? "tonal" : "primary"} className="wide" busy={busy} type="submit">Sign in</Button>
          </form>
        ) : (
          // The break-glass admin can always sign in with a password.
          <button className="link-btn" onClick={() => setShowPassword(true)}><KeyRound size={14} /> Sign in with a password (admins)</button>
        )}
        {config && <p className="login-version">Server {config.version}</p>}
      </div>
    </div>
  );
}
