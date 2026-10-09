// Test environment for the market controllers: a scripted ControllerHost and a registry that behaves
// like `pano.controllers` of the engine (app scope cached per name, instance scope new per call, siblings through `ctx.use`,
// eager controllers created at registration in the browser). The real registry is the SDK's; this one mirrors its contract
// without importing it, so the plugin's tests need no SDK checkout.
import { ALL, NAMESPACE, PLUGIN_ID } from './controllerSet.js';

export function fakeStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, String(v)),
    removeItem: (k) => data.delete(k),
  };
}

const identityOf = (user) => (user ? (user.id ?? user.username ?? 'user') : null);

/**
 * @param {{ browser?: boolean, user?: object|null, csrfToken?: string|null, locale?: string, features?: string[],
 *   respond?: (request: object, env: object) => any, preregister?: boolean, pano?: object }} [options]
 */
export function createEnv(options = {}) {
  const { browser = true, features = [], preregister = true } = options;

  const listeners = new Set();
  let session = { user: options.user ?? null, csrfToken: options.csrfToken ?? null };
  let bound = false;

  const env = {
    browser,
    locale: options.locale ?? 'en-US',
    requests: [],
    toasts: [],
    navigations: [],
    local: fakeStorage(),
    sessionStorage: fakeStorage(),
    nowValue: null,
    respond: options.respond ?? (() => ({ error: { code: 'NETWORK_ERROR' } })),
    events: [],
  };

  const makeHost = (event) => ({
    baseUrl: 'https://shop.test',
    browser,
    request: async (request) => {
      env.requests.push({ ...request, event });
      return env.respond(request, env);
    },
    session: () => session,
    locale: () => env.locale,
    onSession(fn) {
      if (!browser) return () => {};
      listeners.add(fn);
      return () => listeners.delete(fn);
    },
    t: (key) => key,
    toast: (key, o) => env.toasts.push(o ? [key, o] : key),
    storage: (kind) => (kind === 'session' ? env.sessionStorage : env.local),
    now: () => env.nowValue ?? Date.now(),
    navigate: (url, o) => env.navigations.push([url, o]),
    feature: (name) => features.includes(name),
    loginUrl: (returnTo) => (returnTo ? `/login?redirect=${returnTo}` : '/login'),
    registerUrl: (returnTo) => (returnTo ? `/register?redirect=${returnTo}` : '/register'),
  });

  const browserHost = makeHost(undefined);
  const defs = new Map();
  const cache = new Map();
  env.host = browserHost;

  // a sibling lives on the same host as the controller that asked for it (the engine registry does the same)
  function ctxFor(host) {
    return {
      namespace: NAMESPACE,
      use: (sibling) => {
        const full = `${NAMESPACE}/${sibling}`;
        return defs.has(full) ? acquire(full, {}, host) : null;
      },
    };
  }

  function acquire(name, opts, onHost) {
    const def = defs.get(name);
    const host = onHost ?? (browser ? browserHost : makeHost(opts?.event));
    const make = () => def.create(host, opts?.params ?? {}, opts?.initial, ctxFor(host));

    if (def.scope === 'instance' || !browser) return make();
    if (!cache.has(name)) cache.set(name, make());
    return cache.get(name);
  }

  const controllers = {
    register(pluginId, namespace, definitions) {
      for (const [key, def] of Object.entries(definitions)) defs.set(`${namespace}/${key}`, def);
      if (!browser) return;
      for (const [key, def] of Object.entries(definitions))
        if (def.eager && def.scope !== 'instance') acquire(`${namespace}/${key}`);
    },
    has: (name) => defs.has(name),
    use(name, opts) {
      return defs.has(name) ? acquire(name, opts) : null;
    },
    async load(name, opts) {
      const def = defs.get(name);
      if (!def) return null;
      const host = browser ? browserHost : makeHost(opts?.event);
      return def.load ? def.load({ host, params: opts?.params ?? {} }) : {};
    },
    reset() {
      for (const c of cache.values()) c.destroy();
      cache.clear();
      defs.clear();
    },
  };

  env.controllers = controllers;
  env.registerAll = () => controllers.register(PLUGIN_ID, NAMESPACE, ALL);
  env.use = (name) => controllers.use(`${NAMESPACE}/${name}`);

  /** Binds the session the way the engine does (first bind), or changes it; listeners run when the user changed or on the first bind. */
  env.setSession = (next = {}) => {
    const previous = session;
    session = { user: next.user ?? null, csrfToken: next.csrfToken ?? null };
    const changed = !bound || identityOf(session.user) !== identityOf(previous.user);
    bound = true;
    if (changed)
      for (const fn of [...listeners]) {
        try {
          fn(session);
        } catch (e) {
          // the engine host logs a failing listener and goes on with the others
          console.error('[test host] a session listener failed', e);
        }
      }
  };
  env.logout = () => env.setSession({});

  env.pano = {
    features: { has: (name) => features.includes(name) },
    controllers,
    ...(options.pano ?? {}),
  };

  /** Gives a fake `pano` (the object `registerTheme` gets) the registry. */
  env.attach = (pano) => {
    pano.controllers = controllers;
    env.pano = pano;
    return pano;
  };

  env.install = () => {
    if (preregister) env.registerAll();
    return env;
  };

  env.dispose = () => {
    controllers.reset();
  };

  return env.install();
}
