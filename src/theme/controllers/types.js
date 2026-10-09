// Typedefs of the public controllers of pano-plugin-market (doc 02 section 2: `types.js` is the typedef module). No code.
// State shapes list EVERY public key: a controller's initial state holds all of them (doc 02 section 1).

/** @typedef {import('@panomc/plugin-kit/controller').ControllerHost} ControllerHost */

/** @typedef {{ ok: true, [key: string]: any } | { ok: false, code: string, [key: string]: any }} ApiResult */

/**
 * `market/session`, eager: the signed-in user, fed by `host.onSession`.
 * @typedef {{ user: object | null, isLoggedIn: boolean, csrfToken: string | null }} SessionState
 */

/** `market/settings`: the `settings` object of GET /store (null until a page or `ensure` stored it). @typedef {{ settings: object | null }} SettingsState */

/** `market/clock`: epoch milliseconds, 0 on the server, ticking every second while subscribed. @typedef {{ now: number }} ClockState */

/** `market/currency`: the preferred display currency (ISO code or null). @typedef {{ preferred: string | null }} CurrencyState */

/**
 * `market/cart`, eager: today's cart store state plus the pending replace question.
 * @typedef {{ mode: 'NONE'|'GUEST'|'SERVER', status: 'IDLE'|'LOADING'|'SYNCING'|'ERROR', lines: object[], quote: object | null,
 *   quoteStale: boolean, count: number, error: string | null, codes: object | null, reduced: string[],
 *   replaceRequest: { line: object, product: object } | null }} CartState
 */

/** `market/checkoutDraft`, eager: the draft of the checkout form (see `defaultDraft()` in lib/checkoutDraftModel.js). @typedef {ReturnType<typeof import('../lib/checkoutDraftModel.js').defaultDraft>} CheckoutDraftState */

/** `market/profile`, eager: the `me/summary` of the signed-in user (null: unknown, signed out or failed). @typedef {{ summary: object | null }} ProfileState */

/**
 * Params of the four page loaders (`market/store`, `product`, `order`, `checkout`): `event.params` of the route, plus
 * `url` (the request URL as a string or URL; the controller reads the query and the origin from it). Without `url` the
 * query is empty and the origin is `host.baseUrl`.
 * @typedef {{ url?: string | URL, slug?: string, id?: string }} PageLoadParams
 */

/**
 * What a page loader resolves: the view's `load` result of today (`{ data, ... }`), plus `redirect` ({ status, location })
 * where the old load threw a redirect, and `notFound: true` where it threw a 404. The view turns those into its throw.
 * @typedef {{ data?: object, redirect?: { status: number, location: string }, notFound?: boolean, [key: string]: any }} PageLoadResult
 */

export {};
