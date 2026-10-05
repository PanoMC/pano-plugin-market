// Cart store (14 §6.3-6.5): guest cart in localStorage (v2), server cart for logged-in users, merge on login,
// quotes outside checkout. `createCartStore(deps)` builds an instance with injectable dependencies (tests);
// `cart` is the instance of the running theme. Nothing here reads storage at module top level.
import { derived, get, writable } from 'svelte/store';
import { showToast } from '@panomc/sdk/toasts';
import { currentLanguage } from '@panomc/sdk/utils/language';
import { pluginId } from '../../i18n.js';
import { call } from '../utils/api.js';
import {
  COUNT_KEY,
  MAX_QUANTITY,
  STORAGE_KEY,
  addLine,
  applyQuote,
  buildMeta,
  countOf,
  hasMessageCodes,
  linesFromServer,
  parseStored,
  removeLine,
  replaceDecision,
  serialize,
  toWireItems,
  updateLine,
} from '../lib/cartModel.js';
import { lineKey } from '../lib/lineKey.js';
import { effectiveCurrency } from './currency.js';
import { onSessionInit, user } from './session.js';
import { storeSettings } from './storeSettings.js';

const SERVER_CART = '/api/market/me/cart';
const toastKey = (key) => `plugins.${pluginId}.theme.${key}`;

export const timing = {
  debounce: 300,
  sleep: (ms) => new Promise((resolve) => setTimeout(resolve, ms)),
};

function defaultStorage(kind) {
  try {
    return kind === 'session' ? globalThis.sessionStorage : globalThis.localStorage;
  } catch (e) {
    return null;
  }
}

const defaultDeps = () => ({
  call,
  getUser: () => get(user),
  getCurrency: () => effectiveCurrency(get(storeSettings), null),
  getLocale: () => get(currentLanguage)?.code,
  local: () => defaultStorage('local'),
  session: () => defaultStorage('session'),
  toast: (key) => showToast(toastKey(key)),
  now: () => Date.now(),
  onMarketPath: () =>
    typeof globalThis.location !== 'undefined' &&
    /^\/(store|profile)(\/|$)/.test(globalThis.location.pathname),
  timing,
});

const initialState = () => ({
  mode: 'NONE',
  status: 'IDLE',
  lines: [],
  quote: null,
  quoteStale: false,
  count: 0,
  error: null,
  codes: null,
});

const userKeyOf = (u) => (u ? `u:${u.id ?? u.username ?? ''}` : '');

export function createCartStore(overrides = {}) {
  const d = { ...defaultDeps(), ...overrides };
  const state = writable(initialState());
  const replaceRequest = writable(null);

  let userKey = null;
  let initPromise = null;
  let initToken = 0;
  let queue = Promise.resolve();
  let quoteSeq = 0;
  let quoteTimer = null;
  let quoteWaiters = [];
  let watchers = 0;
  let pendingReplace = null;
  let serverExtras = {};
  // user key whose server cart is in `lines` (a complete PUT may only be built from a loaded server cart)
  let loadedKey = null;

  const serverLoaded = () => loadedKey !== null && loadedKey === userKey;

  function commit(patch) {
    state.update((s) => {
      const next = { ...s, ...patch };
      if (patch.lines) next.count = countOf(patch.lines);
      return next;
    });
  }

  // ---- guest storage -------------------------------------------------------------------------------------

  function readGuest() {
    let raw = null;
    let storage = null;

    try {
      storage = d.local();
      raw = storage ? storage.getItem(STORAGE_KEY) : null;
    } catch (e) {
      raw = null;
    }

    const items = parseStored(raw).items;

    // the normalised form is written back on first read (rewrites a legacy value once, 00 §10)
    if (typeof raw === 'string') {
      try {
        const normalised = serialize(items);
        if (normalised !== raw) storage.setItem(STORAGE_KEY, normalised);
      } catch (e) {
        // storage unavailable: the cart lives for this page only
      }
    }

    return items;
  }

  function writeGuest(lines) {
    try {
      d.local()?.setItem(STORAGE_KEY, serialize(lines));
    } catch (e) {
      // storage unavailable (private mode / quota): the cart lives for this page only
    }
  }

  function writeCountCache(count) {
    try {
      const u = d.getUser();
      if (u)
        d.session()?.setItem(
          COUNT_KEY,
          JSON.stringify({ userId: u.id ?? null, count, at: d.now() }),
        );
    } catch (e) {
      // not critical
    }
  }

  // ---- server cart ---------------------------------------------------------------------------------------

  function applyServerResponse(res) {
    const cartBody = res.cart || {};
    const quote = res.quote || null;
    const lines = linesFromServer(cartBody.items, quote);

    loadedKey = userKey;

    commit({
      mode: 'SERVER',
      status: 'IDLE',
      error: null,
      lines,
      quote,
      quoteStale: false,
      codes: {
        couponCode: cartBody.couponCode ?? null,
        creatorCode: cartBody.creatorCode ?? null,
        recipientUsername: cartBody.recipientUsername ?? null,
        giftMessage: cartBody.giftMessage ?? null,
      },
    });
    writeCountCache(countOf(lines));
  }

  function failToast(code) {
    d.toast(code === 'NETWORK' ? 'errors.NETWORK' : 'errors.GENERIC');
  }

  /** Server mutations run one after another; `request` reads the state when its turn comes. */
  function enqueue(request, onOk) {
    const run = async () => {
      const keyAtStart = userKey;
      commit({ status: 'SYNCING', error: null });

      let res;
      try {
        res = await request();
      } catch (e) {
        res = { ok: false, code: 'NETWORK' };
      }

      // the user changed (login / logout) while the request was in flight: its answer is not ours any more
      if (userKey !== keyAtStart || get(state).mode !== 'SERVER') return false;

      if (!res.ok) {
        commit({ status: 'ERROR', error: res.code });
        failToast(res.code);
        return false;
      }

      applyServerResponse(res);
      if (onOk) onOk(res);
      return true;
    };

    const result = queue.then(run);
    queue = result.catch(() => {});
    return result;
  }

  /**
   * The complete PUT body (06 §2.2: `items` present = replace all items, every other member present = update it,
   * `null` clears). Explicit nulls are kept; only `undefined` and an unknown currency are left out. Only call it
   * for a loaded server cart (`putRequest` guards that).
   */
  function currentCartBody(patch = {}) {
    const s = get(state);
    const body = { ...serverExtras, ...(s.codes || {}), ...patch };
    body.items = toWireItems(patch.items || s.lines);

    const currency = patch.currency || d.getCurrency();
    if (currency) body.currency = currency;
    else delete body.currency;

    for (const key of Object.keys(body)) if (body[key] === undefined) delete body[key];

    return body;
  }

  /** A complete PUT request; it never runs on lines that are not the server cart (that would wipe it). */
  const putRequest = (patch) => () =>
    serverLoaded()
      ? d.call('PUT', SERVER_CART, { body: currentCartBody(patch) })
      : Promise.resolve({ ok: false, code: 'CART_NOT_LOADED' });

  /** Server mode: makes sure the server cart is in `lines`, retrying the load once; false when it is not. */
  async function ensureServerLoaded() {
    if (serverLoaded()) return true;

    await init({ force: true });

    if (serverLoaded()) return true;

    // still unloaded: the buyer is told (unless they logged out meanwhile) and nothing is sent
    if (get(state).mode === 'SERVER') failToast(get(state).error);
    return false;
  }

  // ---- init ----------------------------------------------------------------------------------------------

  async function initGuest(key) {
    userKey = key;
    loadedKey = null;
    quoteSeq++;
    const lines = readGuest();

    commit({
      mode: 'GUEST',
      status: 'IDLE',
      lines,
      quote: null,
      quoteStale: lines.length > 0,
      error: null,
      codes: null,
    });
    serverExtras = {};
  }

  async function initServer(key, token) {
    userKey = key;
    if (loadedKey !== key) loadedKey = null;

    const local = readGuest();

    if (loadedKey === key) {
      // a reload of a cart we already hold: keep its lines (14 §6.3 "lines unchanged" when the reload fails)
      commit({ mode: 'SERVER', status: 'LOADING', error: null });
    } else {
      commit({
        mode: 'SERVER',
        status: 'LOADING',
        lines: local,
        quote: null,
        quoteStale: local.length > 0,
        error: null,
      });
    }

    const currency = d.getCurrency();
    const res = local.length
      ? await d.call('POST', `${SERVER_CART}/merge`, { body: { items: toWireItems(local) } })
      : await d.call('GET', SERVER_CART, { query: { currency } });

    if (res.ok && local.length) writeGuest([]);
    if (token !== initToken) return;

    if (!res.ok) {
      // local items stay (and are shown, never PUT); retried on the next init or on "Retry"
      commit({ status: 'ERROR', error: res.code });
      return;
    }

    applyServerResponse(res);
    if (local.length && hasMessageCodes(res.quote)) d.toast('cart.merge-dropped');
  }

  /** Idempotent per user; concurrent calls share one promise. `force` retries a failed server load. */
  function init({ force = false } = {}) {
    const key = userKeyOf(d.getUser());
    const s = get(state);

    if (initPromise && initPromise.key === key) return initPromise;
    if (!force && s.mode !== 'NONE' && key === userKey && s.status !== 'ERROR')
      return Promise.resolve();

    const token = ++initToken;
    const run = key ? initServer(key, token) : initGuest(key);
    const promise = Promise.resolve(run).then(() => undefined);
    promise.key = key;
    initPromise = promise;
    promise.finally(() => {
      if (initPromise === promise) initPromise = null;
    });

    return promise;
  }

  /** Session hook: init at once on market pages or once the cart is live; elsewhere stay lazy (14 §7.3). */
  function autoInit() {
    if (get(state).mode !== 'NONE' || d.onMarketPath()) return init();
    return Promise.resolve();
  }

  async function ready() {
    if (get(state).mode === 'NONE' || initPromise) await init();
  }

  // ---- mutations -----------------------------------------------------------------------------------------

  function mutateGuest(lines) {
    quoteSeq++; // an answer to the previous lines is stale now
    writeGuest(lines);
    commit({ lines, quoteStale: true, status: 'IDLE', error: null });
    if (watchers > 0) requestQuote();
  }

  function normalizeNewLine(line, product) {
    const productId = Number(line?.productId ?? product?.id);
    const quantity = Math.min(Math.floor(Number(line?.quantity ?? 1)), MAX_QUANTITY);

    if (!Number.isInteger(productId) || productId <= 0 || !(quantity >= 1)) return null;

    return {
      productId,
      variantId: Number(line?.variantId) > 0 ? Number(line.variantId) : 0,
      quantity,
      fieldValues:
        line?.fieldValues && typeof line.fieldValues === 'object' ? { ...line.fieldValues } : {},
      targetServerId: line?.targetServerId ? Number(line.targetServerId) : null,
      meta: buildMeta(product, line?.variantName),
    };
  }

  async function doAdd(line) {
    const s = get(state);

    if (s.mode === 'SERVER') {
      return enqueue(
        () => d.call('POST', `${SERVER_CART}/items`, { body: toWireItems([line])[0] }),
        () => d.toast('store.added-to-cart'),
      );
    }

    const result = addLine(s.lines, line);

    if (result.full) {
      d.toast('cart.too-many-lines');
      return false;
    }

    mutateGuest(result.lines);
    d.toast('store.added-to-cart');
    return true;
  }

  async function doReplace(line) {
    const s = get(state);

    if (s.mode === 'SERVER') {
      if (!(await ensureServerLoaded())) return false;

      return enqueue(putRequest({ items: [line] }), () => d.toast('store.added-to-cart'));
    }

    mutateGuest([line]);
    d.toast('store.added-to-cart');
    return true;
  }

  /**
   * Adds `line` ({ productId, variantId?, quantity?, fieldValues?, targetServerId? }); `product` supplies the
   * display meta and `billingMode`. Resolves true when the line is in the cart afterwards. A subscription
   * meeting other content opens the replace request (`replaceRequest`) and resolves once the buyer decides.
   */
  async function add(line, product = {}) {
    await ready();

    const newLine = normalizeNewLine(line, product);
    if (!newLine) return false;

    // the replace question and the POST need the real server cart, not the browser lines shown while it is unloaded
    if (get(state).mode === 'SERVER' && !serverLoaded() && !(await ensureServerLoaded()))
      return false;

    const s = get(state);

    if (s.mode === 'SERVER' && s.status === 'LOADING') return false;

    const decision = replaceDecision(s, newLine);

    if (decision === 'ALREADY_IN_CART') return true;
    if (decision === 'ADD') return doAdd(newLine);

    // one pending question at a time: a newer one cancels the older
    pendingReplace?.resolve(false);

    return new Promise((resolve) => {
      pendingReplace = { line: newLine, resolve };
      replaceRequest.set({ line: newLine, product });
    });
  }

  async function confirmReplace() {
    const pending = pendingReplace;
    if (!pending) return false;

    pendingReplace = null;
    replaceRequest.set(null);
    pending.resolve(await doReplace(pending.line));

    return true;
  }

  function cancelReplace() {
    const pending = pendingReplace;
    pendingReplace = null;
    replaceRequest.set(null);
    pending?.resolve(false);
  }

  function findLine(key) {
    return get(state).lines.find((l) => lineKey(l) === key) || null;
  }

  /** Quantity / fieldValues / targetServerId of the line `key`; a quantity <= 0 removes it. */
  async function update(key, patch) {
    await ready();

    const s = get(state);
    const target = findLine(key);
    if (!target) return false;

    if ('quantity' in patch && !(Math.floor(Number(patch.quantity)) >= 1)) return remove(key);

    if (s.mode === 'SERVER') {
      if (!target.itemId) return false;

      const body = {};
      if ('quantity' in patch)
        body.quantity = Math.min(Math.floor(Number(patch.quantity)), MAX_QUANTITY);
      if ('fieldValues' in patch) body.fieldValues = patch.fieldValues || {};
      if ('targetServerId' in patch) body.targetServerId = patch.targetServerId ?? null;

      return enqueue(() => d.call('PUT', `${SERVER_CART}/items/${target.itemId}`, { body }));
    }

    mutateGuest(updateLine(s.lines, key, patch));
    return true;
  }

  const setQuantity = (key, quantity) => update(key, { quantity });

  async function remove(key) {
    await ready();

    const s = get(state);
    const target = findLine(key);
    if (!target) return false;

    if (s.mode === 'SERVER') {
      if (!target.itemId) return false;
      return enqueue(() => d.call('DELETE', `${SERVER_CART}/items/${target.itemId}`));
    }

    mutateGuest(removeLine(s.lines, key));
    return true;
  }

  async function clear() {
    await ready();

    if (get(state).mode === 'SERVER')
      return enqueue(
        () => d.call('DELETE', SERVER_CART),
        () => {
          serverExtras = {}; // the server cleared its shipping selection too
        },
      );

    mutateGuest([]);
    return true;
  }

  /**
   * Server mode: PUT the complete cart with `patch` applied (codes, recipient, currency, shipping selection, items).
   * Guest mode: nothing to persist outside the checkout draft.
   */
  async function putCart(patch = {}) {
    await ready();

    if (get(state).mode !== 'SERVER') return false;
    if (!(await ensureServerLoaded())) return false;

    for (const key of ['shippingAddressId', 'shippingAddress', 'shippingMethodId'])
      if (key in patch) serverExtras = { ...serverExtras, [key]: patch[key] };

    return enqueue(putRequest(patch));
  }

  /** The display currency changed: the server cart is re-priced by a complete PUT, a guest quote is re-asked. */
  async function setCurrency(code) {
    await ready();

    if (get(state).mode === 'SERVER') return putCart({ currency: code });

    commit({ quoteStale: true });
    if (watchers > 0) requestQuote();

    return true;
  }

  /** After a successful checkout: guest lines are cleared locally; the server emptied its own cart (no request). */
  function afterCheckout() {
    if (get(state).mode === 'SERVER') {
      serverExtras = {};
      commit({
        lines: [],
        quote: null,
        quoteStale: false,
        codes: null,
        status: 'IDLE',
        error: null,
      });
      writeCountCache(0);
      return;
    }

    quoteSeq++;
    writeGuest([]);
    commit({ lines: [], quote: null, quoteStale: false, status: 'IDLE', error: null });
  }

  // ---- quotes --------------------------------------------------------------------------------------------

  function applyGuestQuote(quote) {
    const s = get(state);
    const result = applyQuote(s.lines, quote);

    if (result.changed) writeGuest(result.lines);

    commit({
      lines: result.changed ? result.lines : s.lines,
      quote,
      quoteStale: result.stale,
      status: 'IDLE',
      error: null,
    });

    if (result.stale && watchers > 0) requestQuote();
  }

  async function runQuote(seq) {
    const body = () => ({
      items: toWireItems(get(state).lines),
      currency: d.getCurrency() || undefined,
      locale: d.getLocale(),
    });
    const ask = () => d.call('POST', '/api/market/checkout/quote', { body: body() });

    let res = await ask();

    if (!res.ok && res.code === 'TOO_MANY_REQUESTS') {
      const wait = Math.min(Number(res.retryAfter) > 0 ? Number(res.retryAfter) : 1, 30);
      await d.timing.sleep(wait * 1000);
      if (seq !== quoteSeq) return null;
      res = await ask();
    }

    if (seq !== quoteSeq) return null; // a newer request or a line change made this answer stale

    if (!res.ok) {
      commit({ status: 'ERROR', error: res.code, quoteStale: true });
      return null;
    }

    applyGuestQuote(res.quote);
    return res.quote;
  }

  /**
   * Quote of the guest cart (debounced 300 ms, sequence-numbered). A server cart's quote arrives with every
   * cart response, so there it resolves to the current quote without a request.
   */
  function requestQuote() {
    const s = get(state);

    if (s.mode !== 'GUEST') return Promise.resolve(s.quote);

    if (!s.lines.length) {
      commit({ quote: null, quoteStale: false });
      return Promise.resolve(null);
    }

    clearTimeout(quoteTimer);
    const seq = ++quoteSeq;

    return new Promise((resolve) => {
      quoteWaiters.push(resolve);
      quoteTimer = setTimeout(async () => {
        let result = null;

        try {
          result = await runQuote(seq);
        } finally {
          const waiters = quoteWaiters;
          quoteWaiters = [];
          waiters.forEach((w) => w(result));
        }
      }, d.timing.debounce);
    });
  }

  /** While watched (offcanvas open / checkout mounted) every guest line change asks for a fresh quote. */
  function watchQuotes() {
    watchers++;
    if (get(state).mode === 'GUEST' && get(state).quoteStale) requestQuote();

    let done = false;

    return () => {
      if (done) return;
      done = true;
      watchers = Math.max(0, watchers - 1);
    };
  }

  /** "Retry" button: reload the server cart, or ask the guest quote again. */
  function retry() {
    if (get(state).mode === 'SERVER') return init({ force: true });

    return requestQuote();
  }

  /** Clamps guest quantities against a quote the checkout page received (14 §6.4, 10.6). */
  function clampToQuote(quote) {
    if (get(state).mode !== 'GUEST') return;

    const s = get(state);
    const result = applyQuote(s.lines, quote);

    if (result.changed) {
      writeGuest(result.lines);
      commit({ lines: result.lines });
    }
  }

  /** Test helper: forget all state. */
  function reset() {
    clearTimeout(quoteTimer);
    quoteTimer = null;
    quoteWaiters.forEach((w) => w(null));
    quoteWaiters = [];
    pendingReplace?.resolve(false);
    pendingReplace = null;
    replaceRequest.set(null);
    userKey = null;
    loadedKey = null;
    initPromise = null;
    initToken++;
    quoteSeq++;
    watchers = 0;
    serverExtras = {};
    queue = Promise.resolve();
    state.set(initialState());
  }

  return {
    subscribe: state.subscribe,
    replaceRequest: { subscribe: replaceRequest.subscribe },
    count: derived(state, ($s) => $s.count),
    init,
    autoInit,
    add,
    update,
    setQuantity,
    remove,
    clear,
    putCart,
    setCurrency,
    afterCheckout,
    requestQuote,
    watchQuotes,
    retry,
    clampToQuote,
    confirmReplace,
    cancelReplace,
    reset,
  };
}

export const cart = createCartStore();

// Initialise with the session (first bind in the browser, login, logout); lazy outside market pages.
onSessionInit(() => cart.autoInit());
