// `market/session`, eager: who is signed in, fed by `host.onSession` (first bind, login, logout; a no-op on the server).
// Pages and slot components no longer bind the session themselves (rule V5).
import { defineController } from '@panomc/plugin-kit/controller';

function fromSession(session) {
  const user = session?.user || null;

  return { user, isLoggedIn: user !== null, csrfToken: session?.csrfToken || null };
}

export default defineController({
  name: 'session',
  version: 1,
  eager: true,
  state: ({ host }) => fromSession(host.session()),
  actions: (c) => ({
    /** Reads the host session again. */
    sync: () => c.set(fromSession(c.host.session())),
    /**
     * Runs `fn` on the first session bind and whenever the user logs in or out (browser only). Returns the unsubscribe.
     * The old `onSessionInit`.
     */
    onChange: (fn) => c.host.onSession(fn),
  }),
  start: ({ host, set }) => host.onSession(() => set(fromSession(host.session()))),
});
