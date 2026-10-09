// `market/checkoutDraft`, eager: the checkout form's draft in sessionStorage, restored on mount, written on every change.
// Eager so that logging out on ANY page clears the stored draft (14 §10.2). The body is ./_checkoutDraftEngine.js.
import { defineController } from '@panomc/plugin-kit/controller';
import { defaultDraft, ownerKeyOf } from '../lib/checkoutDraftModel.js';
import { createCheckoutDraft } from './_checkoutDraftEngine.js';

export default defineController({
  name: 'checkoutDraft',
  version: 1,
  eager: true,
  state: () => defaultDraft(),
  actions: (c) => {
    const draft = createCheckoutDraft({ storage: () => c.host.storage('session') });

    draft.subscribe((s) => c.set(s));

    // subscribe / get belong to the controller
    const { subscribe, ...actions } = draft;

    return actions; // get, restore, patch, set, flush, clear, detach, sessionChanged
  },
  // the session's user changed (first bind, login, logout): logging out or switching users clears the draft
  start: ({ host, actions }) =>
    host.onSession(() => actions.sessionChanged(ownerKeyOf(host.session().user))),
});
