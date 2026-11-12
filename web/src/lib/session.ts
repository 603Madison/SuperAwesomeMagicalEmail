import { create } from "zustand";
import { api, ApiError, setSignedOutHandler } from "./api";
import type { AuthConfig, User } from "./types";

interface Session {
  config: AuthConfig | null;
  user: User | null;
  /** Still finding out whether someone is signed in. */
  loading: boolean;
}

export const useSession = create<Session>(() => ({ config: null, user: null, loading: true }));

setSignedOutHandler(() => useSession.setState({ user: null }));

export const session = {
  async start() {
    const [config, me] = await Promise.all([
      api.authConfig().catch(() => null),
      api.me().catch((e) => (e instanceof ApiError && e.status === 401 ? null : Promise.reject(e))).catch(() => null),
    ]);
    useSession.setState({ config, user: me?.user ?? null, loading: false });
  },

  signedIn(user: User) {
    useSession.setState({ user });
  },

  setUser(user: User) {
    useSession.setState({ user });
  },

  async signOut() {
    await api.logout().catch(() => undefined);
    useSession.setState({ user: null });
  },
};
