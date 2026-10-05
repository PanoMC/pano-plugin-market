import { beforeEach, describe, expect, test } from 'bun:test';
import { get, writable } from 'svelte/store';
import './sdkMocks.js';

const { bindDraftToSession, createCheckoutDraft, defaultDraft, ownerKeyOf, parseDraft, DRAFT_KEY } =
  await import('../../stores/checkoutDraft.js');
const session = await import('../../stores/session.js');

function fakeStorage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return {
    data,
    getItem: (k) => (data.has(k) ? data.get(k) : null),
    setItem: (k, v) => data.set(k, String(v)),
    removeItem: (k) => data.delete(k),
  };
}

function fakeTiming() {
  const pending = new Map();
  let next = 1;
  return {
    ms: 200,
    pending,
    set: (fn, ms) => {
      pending.set(next, { fn, ms });
      return next++;
    },
    clear: (id) => pending.delete(id),
    run: () => {
      for (const [id, { fn }] of [...pending]) {
        pending.delete(id);
        fn();
      }
    },
  };
}

describe('parseDraft', () => {
  test('garbage, non-objects and wrong JSON give the defaults', () => {
    expect(parseDraft('{not json')).toEqual(defaultDraft());
    expect(parseDraft('"text"')).toEqual(defaultDraft());
    expect(parseDraft('[1]')).toEqual(defaultDraft());
    expect(parseDraft(null)).toEqual(defaultDraft());
  });

  test('known keys survive, unknown keys and wrong types are dropped', () => {
    const draft = parseDraft(
      JSON.stringify({
        guest: { username: 'Steve', email: 5, extra: 'x' },
        isGift: 'yes',
        recipientUsername: 'Alex',
        shippingAddressId: 7,
        shippingAddress: { country: 'TR', city: 'Izmir', evil: '<script>' },
        billingInfo: { type: 'weird', taxNumber: '123' },
        useCredits: 'MAX',
        acceptLegal: true,
        legal: true,
      }),
    );

    expect(draft.guest).toEqual({ username: 'Steve', email: '' });
    expect(draft.isGift).toBe(false);
    expect(draft.recipientUsername).toBe('Alex');
    expect(draft.shippingAddressId).toBe(7);
    expect(draft.shippingAddress.country).toBe('TR');
    expect('evil' in draft.shippingAddress).toBe(false);
    expect(draft.billingInfo.type).toBe('INDIVIDUAL');
    expect(draft.billingInfo.taxNumber).toBe('123');
    expect(draft.useCredits).toBe('MAX');
    expect('acceptLegal' in draft).toBe(false);
    expect('legal' in draft).toBe(false);
  });

  test('useCredits: negative or non-number becomes null, a number is kept', () => {
    expect(parseDraft({ useCredits: -3 }).useCredits).toBeNull();
    expect(parseDraft({ useCredits: '5' }).useCredits).toBeNull();
    expect(parseDraft({ useCredits: 2.5 }).useCredits).toBe(2.5);
  });
});

describe('createCheckoutDraft', () => {
  let storage;
  let timing;
  let draft;

  beforeEach(() => {
    storage = fakeStorage();
    timing = fakeTiming();
    draft = createCheckoutDraft({ storage, timing });
  });

  test('restore reads the stored draft and starts persisting', () => {
    storage.setItem(DRAFT_KEY, JSON.stringify({ couponCode: 'SAVE10', isGift: true }));
    const restored = draft.restore();

    expect(restored.couponCode).toBe('SAVE10');
    expect(restored.isGift).toBe(true);
    expect(get(draft).couponCode).toBe('SAVE10');
  });

  test('changes are written once after the 200 ms debounce', () => {
    draft.restore();
    draft.patch({ couponCode: 'A' });
    draft.patch({ couponCode: 'AB' });

    expect(storage.data.has(DRAFT_KEY)).toBe(false);
    expect(timing.pending.size).toBe(1);
    expect([...timing.pending.values()][0].ms).toBe(200);

    timing.run();

    expect(JSON.parse(storage.getItem(DRAFT_KEY)).couponCode).toBe('AB');
  });

  test('nothing is written before restore', () => {
    draft.patch({ couponCode: 'A' });

    expect(timing.pending.size).toBe(0);
  });

  test('legal acceptance and unknown members are never stored', () => {
    draft.restore();
    draft.patch({ acceptLegal: true, legalAccepted: true });
    draft.flush();

    const stored = JSON.parse(storage.getItem(DRAFT_KEY));
    expect('acceptLegal' in stored).toBe(false);
    expect('legalAccepted' in stored).toBe(false);
    // the owner key is stored beside the draft, never part of it
    expect(Object.keys(stored).sort()).toEqual([...Object.keys(defaultDraft()), 'owner'].sort());
    expect('owner' in get(draft)).toBe(false);
  });

  test('clear empties the storage, cancels the pending write and resets the state', () => {
    draft.restore();
    draft.patch({ couponCode: 'A' });
    draft.clear();

    expect(timing.pending.size).toBe(0);
    expect(storage.data.has(DRAFT_KEY)).toBe(false);
    expect(get(draft)).toEqual(defaultDraft());
  });

  test('flush writes at once; detach stops persisting', () => {
    draft.restore();
    draft.patch({ giftMessage: 'hi' });
    draft.flush();
    expect(JSON.parse(storage.getItem(DRAFT_KEY)).giftMessage).toBe('hi');

    draft.detach();
    draft.patch({ giftMessage: 'later' });
    expect(timing.pending.size).toBe(0);
  });

  test('sessionChanged: logout and user switch clear the draft, the first bind and a login do not', () => {
    draft.restore();
    draft.patch({ couponCode: 'KEEP' });

    draft.sessionChanged(''); // first bind as a guest
    expect(get(draft).couponCode).toBe('KEEP');
    draft.sessionChanged('u:1'); // login keeps the guest draft
    expect(get(draft).couponCode).toBe('KEEP');

    draft.sessionChanged(''); // logout
    expect(get(draft).couponCode).toBe('');
    expect(storage.data.has(DRAFT_KEY)).toBe(false);

    draft.patch({ couponCode: 'ALEX' });
    draft.sessionChanged('u:1');
    draft.patch({ couponCode: 'STEVE' });
    draft.sessionChanged('u:2'); // another user
    expect(get(draft).couponCode).toBe('');
  });

  test('logout while the checkout page is not mounted still clears the stored draft', () => {
    globalThis.window = {};
    try {
      session.resetSession();
      const sessionStore = writable({ user: { id: 1, username: 'Steve' } });
      bindDraftToSession(draft);

      // the buyer typed on the checkout page, then left it (detach flushes to the storage)
      draft.restore(ownerKeyOf({ id: 1, username: 'Steve' }));
      draft.patch({ guest: { username: 'Steve', email: 's@x.io' }, couponCode: 'SAVE' });
      draft.detach();
      expect(JSON.parse(storage.getItem(DRAFT_KEY)).guest.email).toBe('s@x.io');

      // a navbar on another page binds the session and the buyer logs out there
      session.bindSession(sessionStore);
      sessionStore.set({});

      expect(storage.data.has(DRAFT_KEY)).toBe(false);
      expect(draft.restore('').guest.email).toBe('');
    } finally {
      delete globalThis.window;
      session.resetSession();
    }
  });

  test('restore under another user drops the stored draft', () => {
    draft.restore('u:1');
    draft.patch({
      couponCode: 'SAVE',
      shippingAddress: { ...defaultDraft().shippingAddress, city: 'Izmir' },
    });
    draft.flush();
    draft.detach();

    // same user: restored
    const same = createCheckoutDraft({ storage, timing });
    expect(same.restore('u:1').couponCode).toBe('SAVE');
    same.detach();

    // another user or a guest after logout: defaults, stale storage removed
    const other = createCheckoutDraft({ storage, timing });
    expect(other.restore('u:2')).toEqual(defaultDraft());
    expect(storage.data.has(DRAFT_KEY)).toBe(false);

    draft.restore('u:1');
    draft.patch({ couponCode: 'SAVE' });
    draft.flush();
    draft.detach();
    const guest = createCheckoutDraft({ storage, timing });
    expect(guest.restore('')).toEqual(defaultDraft());
  });

  test('a guest draft (empty or missing owner) is restored for any user', () => {
    storage.setItem(DRAFT_KEY, JSON.stringify({ couponCode: 'G', owner: '' }));
    expect(createCheckoutDraft({ storage, timing }).restore('u:5').couponCode).toBe('G');

    storage.setItem(DRAFT_KEY, JSON.stringify({ couponCode: 'OLD' }));
    expect(createCheckoutDraft({ storage, timing }).restore('u:5').couponCode).toBe('OLD');
  });

  test('without an owner told, a draft stored for a user is not restored', () => {
    storage.setItem(DRAFT_KEY, JSON.stringify({ couponCode: 'X', owner: 'u:1' }));

    expect(draft.restore().couponCode).toBe('');
  });

  test('a draft written after a login carries the new owner', () => {
    draft.restore('');
    draft.sessionChanged('u:3');
    draft.patch({ couponCode: 'Z' });
    draft.flush();

    expect(JSON.parse(storage.getItem(DRAFT_KEY)).owner).toBe('u:3');
  });

  test('an unavailable storage never throws', () => {
    const broken = createCheckoutDraft({
      storage: () => {
        throw new Error('blocked');
      },
      timing,
    });

    expect(() => {
      broken.restore();
      broken.patch({ couponCode: 'X' });
      broken.flush();
      broken.clear();
    }).not.toThrow();
  });
});
