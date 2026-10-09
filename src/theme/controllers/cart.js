// `market/cart`, eager: guest cart in localStorage, server cart for signed-in users, merge on login, quotes outside checkout.
// The body is lib/cartEngine.js; this file mirrors its state (and the pending replace question) into the controller state.
import { defineController } from '@panomc/plugin-kit/controller';
import { createCartStore, depsFromHost, initialState } from '../lib/cartEngine.js';

export default defineController({
  name: 'cart',
  version: 1,
  eager: true,
  state: () => ({ ...initialState(), replaceRequest: null }),
  actions: (c) => {
    const { subscribe, replaceRequest, count, ...actions } = createCartStore(depsFromHost(c));

    subscribe((s) => c.update((p) => ({ ...p, ...s })));
    replaceRequest.subscribe((r) => c.update((p) => ({ ...p, replaceRequest: r })));

    return actions; // init, autoInit, add, update, setQuantity, remove, clear, putCart, ...
  },
  start: ({ host, actions }) => {
    // initialise with the session (first bind in the browser, login, logout); lazy outside market pages
    const off = host.onSession(() => actions.autoInit());

    // Development preview (src/mock): the cart is a store, not page data, so it reloads on this event when the fake-data mode
    // is switched. Nothing ever dispatches it in production.
    const onMock = () => {
      actions.init({ force: true }).then(() => actions.requestQuote());
    };
    if (typeof globalThis.addEventListener === 'function')
      globalThis.addEventListener('pano-market-mock-changed', onMock);

    return () => {
      off();
      if (typeof globalThis.removeEventListener === 'function')
        globalThis.removeEventListener('pano-market-mock-changed', onMock);
    };
  },
});
