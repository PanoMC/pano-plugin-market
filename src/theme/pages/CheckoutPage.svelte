{#if data.state === 'DISABLED' || pageState === 'DISABLED'}
  <StoreStateCard icon="fa-solid fa-store-slash fa-3x" text={$_('theme.store.closed')} />
{:else if data.state === 'ERROR'}
  <StoreStateCard
    icon="fa-solid fa-triangle-exclamation fa-3x"
    text={$_('theme.checkout.load-error')}
    onretry={() => location.reload()} />
{:else if !mounted || pageState === 'INIT'}
  <LoadingBlock rows={6} />
{:else if pageState === 'EMPTY'}
  <NoContent icon="fa-solid fa-cart-shopping fa-3x" text={$_('theme.cart.empty')} />
  <div class="text-center mt-3">
    <a class="btn btn-primary" href="{base}/store">{$_('theme.cart.browse')}</a>
  </div>
{:else if pageState === 'LOGIN_REQUIRED'}
  <LoginRequiredCard {loginHref} {registerHref} showReturnHint={!has('login-return-url')} />
{:else}
  <div class="row g-4" aria-busy={pageState === 'QUOTING' ? 'true' : 'false'}>
    <div class="col-lg-7">
      {#if pageState === 'BLOCKED'}
        <div class="alert alert-danger" role="alert">{$_('theme.errors.BUYER_BLOCKED')}</div>
      {/if}

      <!-- Sections 5-7 (credits, payment method, legal + place order) are added by the next slice. -->
      <fieldset class="vstack gap-3 border-0 p-0 m-0" disabled={formDisabled}>
        <legend class="visually-hidden">{$_('theme.checkout.title')}</legend>

        <BuyerSection
          user={loggedIn ? $user : null}
          guest={draft.guest}
          errors={errors.guest}
          {loginHref}
          onchange={onGuestChange}
          onblur={onGuestBlur} />

        {#if visible.gift}
          <GiftSection
            isGift={draft.isGift}
            recipient={draft.recipientUsername}
            message={draft.giftMessage}
            errors={errors.gift}
            lineNames={lineNamesWith(quote, 'GIFT_NOT_ALLOWED')}
            recipientUnknown={quoteHasCode(quote, 'RECIPIENT_UNKNOWN')}
            onchange={onGiftChange}
            onblur={onGiftBlur} />
        {/if}

        {#if visible.shipping}
          <ShippingSection
            {loggedIn}
            {addresses}
            shippingAddressId={draft.shippingAddressId}
            address={draft.shippingAddress}
            saveAddress={draft.saveAddress}
            errors={errors.shipping}
            required={typedCheck.required}
            countryCodes={shippingCountries(config)}
            {quote}
            quoting={pageState === 'QUOTING'}
            addressReady={shippingReady}
            selectedMethodId={draft.shippingMethodId}
            removeCents={settings.removeCents === true}
            onchange={onShippingChange}
            onblur={onShippingBlur} />
        {/if}

        {#if visible.billing}
          <BillingSection
            req={billingReq}
            info={draft.billingInfo}
            errors={errors.billing}
            shippingRequired={visible.shipping}
            sameAsShipping={draft.billingSameAsShipping}
            onchange={onBillingChange}
            onopen={(open) => onBillingToggle({ billingOpen: open })}
            onsame={(same) => onBillingToggle({ billingSameAsShipping: same })}
            onblur={onBillingBlur} />
        {/if}
      </fieldset>
    </div>

    <div class="col-lg-5">
      <div class="sticky-lg-top vstack gap-3" id="market-checkout-summary">
        <!-- The order summary (lines, codes, totals) is added by the next slice. -->
        {#if cartFailed}
          <ErrorAlert onretry={() => cart.retry()} />
        {/if}

        {#if runnerState.status === 'RATE_LIMITED'}
          <div class="alert alert-warning mb-0" role="alert">
            {$_('theme.errors.TOO_MANY_REQUESTS', { seconds: waitSeconds })}
          </div>
        {:else if runnerState.status === 'ERROR'}
          <ErrorAlert
            message={$_(`theme.errors.${runnerState.code === 'NETWORK' ? 'NETWORK' : 'GENERIC'}`)}
            onretry={() => runner.retry()} />
        {/if}
      </div>
    </div>
  </div>
{/if}

<script module>
  import { get } from 'svelte/store';
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import { parseTopup, resolveCheckoutLoad } from '../lib/checkoutModel.js';
  import { ensureSettings, setSettings } from '../stores/storeSettings.js';
  import { call } from '../utils/api.js';
  import { has } from '../utils/host.js';

  export async function load(event) {
    const topup = parseTopup(event.url.searchParams.get('topup'));
    const locale = get(currentLanguage)?.code;

    const [res, settings] = await Promise.all([
      call('GET', '/api/market/checkout/config', { event, query: { locale } }),
      ensureSettings(event),
    ]);

    const result = resolveCheckoutLoad({
      res,
      settings,
      topup,
      features: { meta: has('page-meta') },
    });

    if (result.data.settingsLoaded) setSettings(result.data.settings);

    return result;
  }
</script>

<script>
  import { getContext, onMount, tick, untrack } from 'svelte';
  import { base } from '@panomc/sdk/svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { _ } from '../../i18n.js';
  import ErrorAlert from '../components/common/ErrorAlert.svelte';
  import LoadingBlock from '../components/common/LoadingBlock.svelte';
  import BillingSection from '../components/checkout/BillingSection.svelte';
  import BuyerSection from '../components/checkout/BuyerSection.svelte';
  import GiftSection from '../components/checkout/GiftSection.svelte';
  import LoginRequiredCard from '../components/checkout/LoginRequiredCard.svelte';
  import ShippingSection from '../components/checkout/ShippingSection.svelte';
  import StoreStateCard from '../components/store/StoreStateCard.svelte';
  import { toWireItems } from '../lib/cartModel.js';
  import {
    applyQuoteSelections,
    billingRequirements,
    buildQuoteBody,
    canonicalBody,
    carrierExtrasFor,
    checkShippingAddress,
    derivePageState,
    effectiveBillingInfo,
    lineNamesWith,
    mergeServerCodes,
    NO_CARRIER_EXTRAS,
    nextCarrierExtras,
    persistPatch,
    quoteHasCode,
    sectionsVisible,
    shippingAddressOf,
    shippingCountries,
    validateBilling,
    validateCheckout,
    wireAddress,
  } from '../lib/checkoutModel.js';
  import { createQuoteRunner } from '../lib/quoteRunner.js';
  import { validateEmail, validateGiftRecipient, validateUsername } from '../lib/validation.js';
  import { cart } from '../stores/cart.js';
  import { checkoutDraft, ownerKeyOf } from '../stores/checkoutDraft.js';
  import { now } from '../stores/clock.js';
  import { effectiveCurrency, initCurrency, preferred } from '../stores/currency.js';
  import { bindSession, isLoggedIn, user } from '../stores/session.js';
  import { storeSettings } from '../stores/storeSettings.js';
  import { loginUrl, registerUrl } from '../utils/host.js';

  let { data } = $props();

  bindSession(getContext('session'));

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);
  const config = init.config ?? {};
  const topup = init.topup ?? null;
  const settings = init.settings ?? {};

  const RETURN_PATH = '/store/checkout';
  const returnTo = topup !== null ? `${RETURN_PATH}?topup=${topup}` : RETURN_PATH;
  const loginHref = $derived(loginUrl(returnTo));
  const registerHref = $derived(registerUrl(returnTo));

  let mounted = $state(false);
  let cartReady = $state(false);
  let quote = $state(null);
  let runnerState = $state({ status: 'IDLE', code: null, retryAfter: 0 });
  let rateUntil = $state(0);
  // imposed by the submit / error mapping of the next slice: DISABLED | BLOCKED | LOGIN_REQUIRED | EMPTY
  let forced = $state(null);
  // IDLE | SUBMITTING | LEAVING, set by the next slice
  let submit = $state('IDLE');
  let addresses = $state([]);
  let addressesLoaded = $state(false);
  let errors = $state({ guest: {}, gift: {}, shipping: {}, billing: {} });
  // extra shipping fields the carrier asked for: sticky for the draft's country + shipping method (never derived
  // from the latest quote alone, or the address would be sent and withheld in turn)
  let carrierExtrasState = $state(NO_CARRIER_EXTRAS);
  // values the quote is asked with: taken over on blur (14 §10.6)
  let committed = $state({ username: '', email: '', recipient: '', message: '' });
  let unmounted = false;
  let persisted = {};

  const draft = $derived($checkoutDraft);
  const loggedIn = $derived($isLoggedIn);
  const cartFailed = $derived($cart.mode === 'SERVER' && $cart.status === 'ERROR');
  const waitSeconds = $derived(Math.max(0, Math.ceil((rateUntil - $now) / 1000)));

  const visible = $derived(sectionsVisible({ config, quote, topup }));
  const carrierExtras = $derived(carrierExtrasFor(carrierExtrasState, draft));
  const typedCheck = $derived(checkShippingAddress(config, draft.shippingAddress, carrierExtras));
  const effectiveShipping = $derived(shippingAddressOf(draft, addresses));
  const shippingReady = $derived(
    visible.shipping && (draft.shippingAddressId !== null || typedCheck.ok),
  );
  const billingReq = $derived(
    billingRequirements({
      config,
      quote,
      info: draft.billingInfo,
      open: draft.billingOpen,
      sameAsShipping: draft.billingSameAsShipping,
      shippingAddress: effectiveShipping,
      shippingRequired: visible.shipping,
      shippingExtra: carrierExtras,
      shippingSaved: draft.shippingAddressId !== null,
    }),
  );
  const billingBody = $derived(
    visible.billing && Object.keys(validateBilling(billingReq, draft.billingInfo)).length === 0
      ? effectiveBillingInfo(billingReq, draft.billingInfo, effectiveShipping)
      : null,
  );
  const ownName = $derived(loggedIn ? ($user?.username ?? '') : committed.username);
  const guestBody = $derived(
    !loggedIn &&
      validateUsername(committed.username) === null &&
      validateEmail(committed.email) === null
      ? { username: committed.username.trim(), email: committed.email.trim() }
      : null,
  );
  const recipientBody = $derived(
    visible.gift && draft.isGift && validateGiftRecipient(committed.recipient, ownName) === null
      ? committed.recipient.trim()
      : null,
  );
  const shippingBody = $derived(
    visible.shipping && draft.shippingAddressId === null && typedCheck.ok
      ? wireAddress(draft.shippingAddress)
      : null,
  );
  const quoteBody = $derived(
    buildQuoteBody({
      topup,
      loggedIn,
      items: !loggedIn && $cart.mode === 'GUEST' ? toWireItems($cart.lines) : [],
      guest: guestBody,
      currency: effectiveCurrency($storeSettings ?? settings, null, $preferred),
      locale: $currentLanguage?.code,
      draft: { ...draft, giftMessage: committed.message },
      recipientUsername: recipientBody,
      shippingAddress: shippingBody,
      billingInfo: billingBody,
    }),
  );
  // a logged-in buyer's items are the server cart: its lines are part of what the quote depends on
  const quoteSignature = $derived(
    loggedIn
      ? `${canonicalBody(quoteBody)}|${JSON.stringify(toWireItems($cart.lines))}`
      : canonicalBody(quoteBody),
  );

  const pageState = $derived(
    derivePageState({
      cartReady: mounted && cartReady,
      count: $cart.count,
      topup,
      loggedIn,
      guestCheckout: config.guestCheckout === true,
      quote,
      quoting: runnerState.status === 'QUOTING',
      submit,
      forced: forced ?? (runnerState.status === 'DISABLED' ? 'DISABLED' : null),
    }),
  );
  const formDisabled = $derived(
    pageState === 'BLOCKED' || pageState === 'SUBMITTING' || pageState === 'LEAVING',
  );
  // a guest without the right to check out never needs a quote
  const quoting = $derived(
    mounted &&
      cartReady &&
      data.state === 'READY' &&
      submit === 'IDLE' &&
      forced === null &&
      (topup !== null || $cart.count > 0) &&
      (loggedIn || (topup === null && config.guestCheckout === true)),
  );

  const runner = createQuoteRunner({
    call,
    onState: (state) => {
      runnerState = state;
      if (state.status === 'RATE_LIMITED') rateUntil = Date.now() + state.retryAfter * 1000;
    },
    onQuote: (next) => {
      quote = next;

      const patch = applyQuoteSelections(checkoutDraft.get(), next);
      if (Object.keys(patch).length) checkoutDraft.patch(patch);

      carrierExtrasState = nextCarrierExtras(carrierExtrasState, next, checkoutDraft.get());

      cart.clampToQuote(next);
      persistToServerCart();
    },
  });

  $effect(() => {
    if (!quoting) {
      untrack(() => runner.stop());
      return;
    }

    const body = quoteBody;
    const signature = quoteSignature;

    untrack(() => runner.request(body, signature));
  });

  // saved addresses: fetched once the order needs shipping (logged-in buyers only)
  $effect(() => {
    if (!mounted || !loggedIn || !visible.shipping || untrack(() => addressesLoaded)) return;

    untrack(loadAddresses);
  });

  /** For a logged-in buyer codes, recipient and shipping selection are kept on the server cart (14 §10.6). */
  function persistToServerCart() {
    const state = get(cart);
    if (!get(isLoggedIn) || topup !== null || state.mode !== 'SERVER') return;

    const d = checkoutDraft.get();
    const gift = visible.gift && d.isGift && recipientBody !== null;
    const wanted = {
      couponCode: d.couponCode.trim() || null,
      creatorCode: d.creatorCode.trim() || null,
      recipientUsername: gift ? recipientBody : null,
      giftMessage: gift && committed.message.trim() ? committed.message.trim() : null,
      shippingAddressId: d.shippingAddressId,
      shippingAddress: shippingBody,
      shippingMethodId: d.shippingMethodId,
    };
    const patch = persistPatch({ ...(state.codes || {}), ...persisted }, wanted);

    if (Object.keys(patch).length === 0) return;

    persisted = { ...persisted, ...patch };
    cart.putCart(patch);
  }

  async function loadAddresses() {
    addressesLoaded = true;

    const res = await call('GET', '/api/market/me/addresses');
    if (unmounted || !res.ok || !Array.isArray(res.addresses)) return;

    addresses = res.addresses;

    const d = checkoutDraft.get();
    if (d.shippingAddressId !== null && !addresses.some((a) => a.id === d.shippingAddressId))
      checkoutDraft.patch({ shippingAddressId: null });
    else if (
      d.shippingAddressId === null &&
      Object.values(d.shippingAddress).every((value) => !value)
    ) {
      const preferredAddress = addresses.find((a) => a.isDefault) ?? null;
      if (preferredAddress) checkoutDraft.patch({ shippingAddressId: preferredAddress.id });
    }
  }

  // ---- errors and focus -------------------------------------------------------------------------------------

  const validationInput = () => ({
    config,
    quote,
    topup,
    draft: checkoutDraft.get(),
    user: get(user),
    saved: addresses,
    carrierExtras: carrierExtrasFor(carrierExtrasState, checkoutDraft.get()),
  });

  /** Recomputes the error of one field (on blur). */
  function refreshField(section, field) {
    const next = { ...errors[section] };
    const code = validateCheckout(validationInput()).errors[section][field];

    if (code) next[field] = code;
    else delete next[field];

    errors = { ...errors, [section]: next };
  }

  /** Re-validates the fields of a section that currently show an error (the error clears once fixed). */
  function refreshShown(section) {
    const shown = Object.keys(errors[section]);
    if (shown.length === 0) return;

    const result = validateCheckout(validationInput()).errors[section];
    const next = {};
    for (const field of shown) if (result[field]) next[field] = result[field];

    errors = { ...errors, [section]: next };
  }

  /**
   * Validates every visible section, shows the errors and moves the focus to the first invalid control.
   * The place-order button of the next slice calls this before it submits; resolves to the result.
   */
  export async function validateAndFocus() {
    const result = validateCheckout(validationInput());
    errors = result.errors;

    if (result.firstId) {
      await tick();
      document.getElementById(result.firstId)?.focus();
    }

    return result;
  }

  // ---- section handlers -----------------------------------------------------------------------------------------

  function onGuestChange(patch) {
    checkoutDraft.patch({ guest: { ...checkoutDraft.get().guest, ...patch } });
    refreshShown('guest');
  }

  function onGuestBlur(field) {
    const d = checkoutDraft.get();
    committed = { ...committed, username: d.guest.username, email: d.guest.email };
    refreshField('guest', field);
  }

  function onGiftChange(patch) {
    checkoutDraft.patch(patch);

    // switching the gift on commits an already typed recipient; switching it off drops the gift errors
    if ('isGift' in patch) {
      if (patch.isGift)
        committed = { ...committed, recipient: checkoutDraft.get().recipientUsername };
      else errors = { ...errors, gift: {} };
    }

    refreshShown('gift');
  }

  function onGiftBlur(field) {
    const d = checkoutDraft.get();

    if (field === 'recipient') committed = { ...committed, recipient: d.recipientUsername };
    if (field === 'message') committed = { ...committed, message: d.giftMessage };

    refreshField('gift', field);
  }

  function onShippingChange(patch) {
    checkoutDraft.patch(patch);

    if ('shippingAddressId' in patch && patch.shippingAddressId !== null)
      errors = { ...errors, shipping: {} };

    refreshShown('shipping');
  }

  function onShippingBlur(field) {
    refreshField('shipping', field);
  }

  function onBillingChange(patch) {
    checkoutDraft.patch({ billingInfo: { ...checkoutDraft.get().billingInfo, ...patch } });
    refreshShown('billing');
  }

  function onBillingToggle(patch) {
    checkoutDraft.patch(patch);
    errors = { ...errors, billing: {} };
  }

  function onBillingBlur(field) {
    refreshField('billing', field);
  }

  // ---- mount ----------------------------------------------------------------------------------------------------

  onMount(() => {
    initCurrency();

    const restored = checkoutDraft.restore(ownerKeyOf(get(user)));

    committed = {
      username: restored.guest.username,
      email: restored.guest.email,
      recipient: restored.recipientUsername,
      message: restored.giftMessage,
    };
    mounted = true;

    cart.init().then(() => {
      if (unmounted) return;

      // a logged-in buyer's server cart wins over the draft on the first mount (14 §10.2)
      if (get(isLoggedIn)) {
        const merged = mergeServerCodes(checkoutDraft.get(), get(cart).codes);
        checkoutDraft.set(merged);
        committed = {
          ...committed,
          recipient: merged.recipientUsername,
          message: merged.giftMessage,
        };
      }

      cartReady = true;
    });

    return () => {
      unmounted = true;
      runner.stop();
      checkoutDraft.detach();
    };
  });
</script>
