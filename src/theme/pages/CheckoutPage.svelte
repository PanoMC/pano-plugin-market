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
    <div class="col-lg-7 vstack gap-3">
      {#if pageState === 'BLOCKED'}
        <div class="alert alert-danger mb-0" role="alert">{$_('theme.errors.BUYER_BLOCKED')}</div>
      {/if}

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

        {#if creditsVisible}
          <CreditsSection
            credits={quote.credits}
            {config}
            payWithCredits={draft.payWithCredits}
            useCredits={draft.useCredits}
            method={chosenMethod}
            currency={quote.currency}
            removeCents={settings.removeCents === true}
            alertKey={notices.credits?.messageKey ?? ''}
            alertMax={notices.credits?.max ?? null}
            onchange={onCreditsChange} />
        {/if}

        {#if quote}
          <PaymentMethodPicker
            {quote}
            selectedId={draft.paymentMethodId}
            payWithCredits={draft.payWithCredits}
            currency={quote.currency}
            removeCents={settings.removeCents === true}
            alertKey={notices.payment?.messageKey ?? ''}
            onselect={onSelectMethod} />
        {/if}
      </fieldset>

      {#if notices.shipping}
        {@render noticeAlert(notices.shipping)}
      {/if}

      {#if config.legal?.required}
        <LegalCheckbox
          legal={config.legal}
          checked={legalAccepted}
          invalid={legalInvalid}
          updated={legalUpdated}
          disabled={formDisabled}
          onchange={onLegalChange} />
      {/if}

      {#if topup === null}
        <div class="form-check">
          <input
            id="market-checkout-hide"
            class="form-check-input"
            type="checkbox"
            checked={hideFromBroadcast}
            disabled={formDisabled}
            onchange={(event) => (hideFromBroadcast = event.currentTarget.checked)} />
          <label class="form-check-label" for="market-checkout-hide">
            {$_('theme.checkout.hide-from-broadcast')}
          </label>
        </div>
      {/if}

      <button
        type="button"
        class="btn btn-primary btn-lg w-100"
        disabled={place.disabled || rateLocked}
        onclick={placeOrder}>
        {#if pageState === 'SUBMITTING' || pageState === 'LEAVING'}
          <span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span>
        {/if}
        {#if pageState === 'LEAVING'}
          {$_('theme.checkout.redirecting')}
        {:else if place.mode === 'COMPLETE'}
          {$_('theme.checkout.complete')}
        {:else}
          {$_('theme.checkout.pay', {
            values: {
              amount: formatMoney(place.amount, quote?.currency, {
                removeCents: settings.removeCents === true,
              }),
            },
          })}
        {/if}
      </button>
    </div>

    <div class="col-lg-5">
      <div class="sticky-lg-top vstack gap-3" id="market-checkout-summary">
        {#if notices.summary}
          {@render noticeAlert(notices.summary)}
        {/if}

        <OrderSummary
          {quote}
          quoting={pageState === 'QUOTING'}
          topup={topup !== null}
          removeCents={settings.removeCents === true}
          hideCodes={codesAreHidden}
          disabled={formDisabled}
          couponCode={draft.couponCode}
          creatorCode={draft.creatorCode}
          couponState={codeUi.coupon}
          creatorState={codeUi.creator}
          oncode={onCode} />

        {#if cartFailed}
          <ErrorAlert onretry={() => cart.retry()} />
        {/if}

        {#if runnerState.status === 'RATE_LIMITED'}
          <div class="alert alert-warning mb-0" role="alert">
            {$_('theme.errors.TOO_MANY_REQUESTS', { values: { seconds: waitSeconds } })}
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

{#snippet noticeAlert(notice)}
  <div
    class={['alert', notice.cls ?? 'alert-danger', 'mb-0']}
    id="market-checkout-notice-{notice.where}"
    role="alert">
    {$_(notice.messageKey, { values: noticeValues(notice) })}
    {#if notice.button === 'EDIT_CART'}
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary d-block mt-2"
        data-bs-toggle="offcanvas"
        data-bs-target="#marketCartOffcanvas"
        aria-controls="marketCartOffcanvas">
        {$_('theme.checkout.edit-cart')}
      </button>
    {:else if notice.button === 'RELOAD'}
      <button
        type="button"
        class="btn btn-sm btn-outline-secondary d-block mt-2"
        onclick={() => location.reload()}>
        {$_('theme.checkout.reload')}
      </button>
    {/if}
  </div>
{/snippet}

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
  import { base, goto } from '@panomc/sdk/svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { showToast } from '@panomc/sdk/toasts';
  import { _ } from '../../i18n.js';
  import ErrorAlert from '../components/common/ErrorAlert.svelte';
  import LoadingBlock from '../components/common/LoadingBlock.svelte';
  import BillingSection from '../components/checkout/BillingSection.svelte';
  import BuyerSection from '../components/checkout/BuyerSection.svelte';
  import CreditsSection from '../components/checkout/CreditsSection.svelte';
  import GiftSection from '../components/checkout/GiftSection.svelte';
  import LegalCheckbox from '../components/checkout/LegalCheckbox.svelte';
  import LoginRequiredCard from '../components/checkout/LoginRequiredCard.svelte';
  import OrderSummary from '../components/checkout/OrderSummary.svelte';
  import PaymentMethodPicker from '../components/checkout/PaymentMethodPicker.svelte';
  import ShippingSection from '../components/checkout/ShippingSection.svelte';
  import StoreStateCard from '../components/store/StoreStateCard.svelte';
  import { toWireItems } from '../lib/cartModel.js';
  import {
    ADDRESS_FIELDS,
    applyQuoteSelections,
    BILLING_EXTRAS,
    billingRequirements,
    buildQuoteBody,
    canonicalBody,
    carrierExtrasFor,
    checkShippingAddress,
    configOf,
    derivePageState,
    effectiveBillingInfo,
    fieldId,
    firstInvalidId,
    lineNamesWith,
    mapBuyerFields,
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
  import {
    saveOrderToken,
    submitCheckout,
    successPlan,
    failurePlan,
  } from '../lib/checkoutSubmit.js';
  import {
    creditsPatchAfterQuote,
    LEGAL_CHECK_ID,
    legalChanged,
    placeOrderState,
    restoredCreditsPatch,
    selectedMethod,
    selectMethodPatch,
  } from '../lib/paymentModel.js';
  import { createQuoteRunner } from '../lib/quoteRunner.js';
  import { codesHidden, nextCodeState } from '../lib/summaryModel.js';
  import { validateEmail, validateGiftRecipient, validateUsername } from '../lib/validation.js';
  import { cart } from '../stores/cart.js';
  import { checkoutDraft, ownerKeyOf } from '../stores/checkoutDraft.js';
  import { now } from '../stores/clock.js';
  import { effectiveCurrency, initCurrency, preferred } from '../stores/currency.js';
  import { bindSession, isLoggedIn, user } from '../stores/session.js';
  import { storeSettings } from '../stores/storeSettings.js';
  import { formatCredits, formatMoney } from '../utils/format.js';
  import { loginUrl, registerUrl } from '../utils/host.js';

  let { data } = $props();

  bindSession(getContext('session'));

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);
  // the checkout config; replaced when the legal text changed while the buyer was on the page (14 §10.5)
  let config = $state(init.config ?? {});
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
  // imposed by the error mapping of the submit: DISABLED | BLOCKED | LOGIN_REQUIRED | EMPTY
  let forced = $state(null);
  // IDLE | SUBMITTING | LEAVING
  let submit = $state('IDLE');
  // alerts by place (summary | shipping | credits | payment), set by the error mapping, cleared on any edit
  let notices = $state({ summary: null, shipping: null, credits: null, payment: null });
  let codeUi = $state({ coupon: { status: 'IDLE' }, creator: { status: 'IDLE' } });
  // legal acceptance is page state only: never stored (14 §10.2)
  let legalAccepted = $state(false);
  let legalInvalid = $state(false);
  let legalUpdated = $state(false);
  let hideFromBroadcast = $state(false);
  let submitLockUntil = $state(0);
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
  let retryTimer = null;
  let legalRefetching = false;

  const draft = $derived($checkoutDraft);
  const loggedIn = $derived($isLoggedIn);
  const cartFailed = $derived($cart.mode === 'SERVER' && $cart.status === 'ERROR');
  const waitSeconds = $derived(Math.max(0, Math.ceil((rateUntil - $now) / 1000)));
  const submitWait = $derived(Math.max(0, Math.ceil((submitLockUntil - $now) / 1000)));
  const rateLocked = $derived(submitLockUntil > 0 && submitWait > 0);

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
  const creditsVisible = $derived(loggedIn && topup === null && quote?.credits?.enabled === true);
  const chosenMethod = $derived(
    draft.payWithCredits ? null : selectedMethod(quote, draft.paymentMethodId),
  );
  const codesAreHidden = $derived(codesHidden({ topup, method: chosenMethod }));
  const place = $derived(placeOrderState({ pageState, quote, draft }));
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
    onQuote: (next) => takeQuote(next),
  });

  /** A quote (from the runner, or the fresh one of PRICE_CHANGED) becomes the shown one: the rules of 14 §10.5. */
  function takeQuote(next) {
    quote = next;

    const before = checkoutDraft.get();
    // the credit choice stays valid (it is never switched on here), then the payment / shipping selection
    const credits = creditsPatchAfterQuote({ draft: before, quote: next, config });
    const patch = { ...credits, ...applyQuoteSelections({ ...before, ...credits }, next) };

    // an invalid or locked code leaves the draft (the input keeps showing why)
    for (const [kind, member, result, fallback] of [
      ['coupon', 'couponCode', next.coupon, 'INVALID_COUPON'],
      ['creator', 'creatorCode', next.creatorCode, 'INVALID_CREATOR_CODE'],
    ]) {
      const state = nextCodeState({
        current: codeUi[kind],
        result,
        applied: before[member],
        fallbackCode: fallback,
        nowMs: Date.now(),
      });

      codeUi = { ...codeUi, [kind]: state.ui };
      if (state.drop) patch[member] = '';
    }

    if (Object.keys(patch).length) checkoutDraft.patch(patch);

    carrierExtrasState = nextCarrierExtras(carrierExtrasState, next, checkoutDraft.get());

    cart.clampToQuote(next);
    persistToServerCart();

    if (legalChanged(config, next)) refetchConfig();
  }

  /** The legal text changed mid-session: fetch the config again, untick the box and say so (14 §10.5). */
  async function refetchConfig() {
    if (legalRefetching) return;
    legalRefetching = true;

    const res = await call('GET', '/api/market/checkout/config', {
      query: { locale: get(currentLanguage)?.code },
    });
    legalRefetching = false;

    const fresh = configOf(res);
    if (unmounted || !fresh) return;

    config = fresh;
    legalAccepted = false;
    legalUpdated = true;
  }

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
   * The place-order button calls this before it submits; resolves to the result.
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
    clearNotices();
    checkoutDraft.patch({ guest: { ...checkoutDraft.get().guest, ...patch } });
    refreshShown('guest');
  }

  function onGuestBlur(field) {
    const d = checkoutDraft.get();
    committed = { ...committed, username: d.guest.username, email: d.guest.email };
    refreshField('guest', field);
  }

  function onGiftChange(patch) {
    clearNotices();
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
    clearNotices();
    checkoutDraft.patch(patch);

    if ('shippingAddressId' in patch && patch.shippingAddressId !== null)
      errors = { ...errors, shipping: {} };

    refreshShown('shipping');
  }

  function onShippingBlur(field) {
    refreshField('shipping', field);
  }

  function onBillingChange(patch) {
    clearNotices();
    checkoutDraft.patch({ billingInfo: { ...checkoutDraft.get().billingInfo, ...patch } });
    refreshShown('billing');
  }

  function onBillingToggle(patch) {
    clearNotices();
    checkoutDraft.patch(patch);
    errors = { ...errors, billing: {} };
  }

  function onBillingBlur(field) {
    refreshField('billing', field);
  }

  // ---- credits, payment method, codes, legal ----------------------------------------------------------------

  const NO_NOTICES = { summary: null, shipping: null, credits: null, payment: null };

  function clearNotices() {
    if (Object.values(notices).some(Boolean)) notices = { ...NO_NOTICES };
  }

  function onCreditsChange(patch) {
    clearNotices();
    checkoutDraft.patch(patch);
  }

  function onSelectMethod(id) {
    clearNotices();

    const d = checkoutDraft.get();
    checkoutDraft.patch(selectMethodPatch(d, id, selectedMethod(quote, id)));
  }

  /** Apply (`code`) or remove (null) the coupon / creator code: the new body asks for a fresh quote. */
  function onCode(kind, code) {
    clearNotices();
    codeUi = { ...codeUi, [kind]: { status: 'IDLE' } };
    checkoutDraft.patch({ [kind === 'coupon' ? 'couponCode' : 'creatorCode']: code ?? '' });
  }

  function onLegalChange(checked) {
    legalAccepted = checked;
    if (checked) {
      legalInvalid = false;
      legalUpdated = false;
    }
  }

  // ---- submit (14 §10.7) and its errors (14 §10.9) --------------------------------------------------------------

  /** Interpolation values of a notice: formatted amounts and plain numbers, never user text (14 §16). */
  function noticeValues(notice) {
    const v = notice.values ?? {};
    const out = {};

    if (typeof v.minimum === 'number')
      out.minimum = formatMoney(v.minimum, quote?.currency, {
        removeCents: settings.removeCents === true,
      });
    if (typeof v.min === 'number') out.min = formatCredits(v.min);
    if (typeof v.max === 'number') out.max = formatCredits(v.max);
    if (typeof v.retryAfter === 'number') out.retryAfter = v.retryAfter;
    if (notice.countdown) out.seconds = submitWait;

    return out;
  }

  function setNotice(where, notice) {
    notices = { ...NO_NOTICES, [where]: { where, ...notice } };

    tick().then(() =>
      document.getElementById(`market-checkout-notice-${where}`)?.scrollIntoView({
        block: 'nearest',
      }),
    );
  }

  function focusId(id) {
    tick().then(() => document.getElementById(id)?.focus());
  }

  function navigate(navigation) {
    if (navigation.type === 'ASSIGN') {
      window.location.assign(navigation.url);
      return;
    }

    Promise.resolve(goto(navigation.path)).catch(() => {
      window.location.assign(`${base}${navigation.path}`);
    });
  }

  function sessionStore() {
    try {
      return window.sessionStorage;
    } catch (e) {
      return null;
    }
  }

  /** The order exists: token, draft, cart and the pending address save, then the navigation of 14 §10.8. */
  function finishSuccess(plan) {
    if (plan.token) saveOrderToken(sessionStore(), plan.token.publicId, plan.token.value);

    if (plan.saveAddress)
      call('POST', '/api/market/me/addresses', { body: plan.saveAddress }).catch(() => {});

    checkoutDraft.clear();
    if (plan.clearCart) cart.afterCheckout();

    submit = 'LEAVING';
    navigate(plan.navigation);
  }

  const submitContext = () => ({ origin: window.location.origin, base });

  async function placeOrder() {
    if (pageState !== 'READY' || place.disabled || rateLocked) return;

    clearNotices();

    const checked = await validateAndFocus();
    if (!checked.valid) return;

    if (config.legal?.required === true && !legalAccepted) {
      legalInvalid = true;
      focusId(LEGAL_CHECK_ID);
      return;
    }

    if (!quote || quote.canCheckout !== true) return;

    submit = 'SUBMITTING';

    const out = await submitCheckout({
      call,
      draftStore: checkoutDraft,
      quoteBody,
      quote,
      config,
      accepted: legalAccepted,
      hide: hideFromBroadcast,
    });

    if (unmounted) return;

    if (out.ok === true) {
      finishSuccess(
        successPlan({
          res: out,
          topup,
          loggedIn,
          draft: checkoutDraft.get(),
          context: submitContext(),
        }),
      );
      return;
    }

    applyFailure(out);
  }

  /** Executes the action of errorMap.checkoutAction for a failed answer (14 §10.9). */
  function applyFailure(res) {
    const plan = failurePlan({
      res,
      topup,
      loggedIn,
      draft: checkoutDraft.get(),
      context: submitContext(),
    });
    const { action } = plan;
    const details = action.details;

    if (Object.keys(plan.keyPatch).length) checkoutDraft.patch(plan.keyPatch);

    if (plan.success) {
      finishSuccess(plan.success);
      return;
    }

    // back to editing: the quote effect asks for a fresh quote as soon as the page is idle again
    submit = 'IDLE';

    const alert = (where, extra = {}) =>
      setNotice(where, {
        messageKey: action.messageKey,
        cls: 'alert-danger',
        values: details,
        ...extra,
      });

    switch (action.kind) {
      case 'STATE':
        if (action.state === 'EMPTY') cart.retry();
        else forced = action.state;
        break;

      case 'ALERT':
        if (action.clearMethod) checkoutDraft.patch({ paymentMethodId: null });
        if (action.clearCredits) checkoutDraft.patch({ payWithCredits: false, useCredits: null });

        alert(action.where, {
          button: action.editCart ? 'EDIT_CART' : undefined,
          max: details.maxApplicable ?? null,
        });
        break;

      case 'FIELD':
        applyFieldFailure(action);
        break;

      case 'CODE': {
        const member = action.which === 'coupon' ? 'couponCode' : 'creatorCode';
        const locked = details.reason === 'CODE_ATTEMPTS_LOCKED';

        codeUi = {
          ...codeUi,
          [action.which]: locked
            ? {
                status: 'LOCKED',
                until:
                  Date.now() + (Number(details.retryAfter) > 0 ? details.retryAfter : 60) * 1000,
              }
            : { status: 'INVALID', messageKey: action.messageKey },
        };
        checkoutDraft.patch({ [member]: '' });
        break;
      }

      case 'PRICE_CHANGED':
        if (details.quote && typeof details.quote === 'object') takeQuote(details.quote);
        alert('summary', { cls: 'alert-warning' });
        break;

      case 'LEGAL':
        legalAccepted = false;
        legalInvalid = true;
        refetchConfig();
        focusId(LEGAL_CHECK_ID);
        break;

      case 'BILLING':
        applyBillingFailure(details);
        break;

      case 'LEAVE':
        showToast(`plugins.pano-plugin-market.${action.messageKey}`);
        navigate({ type: 'GOTO', path: action.to });
        break;

      case 'RETRY':
        alert('summary', { cls: 'alert-warning' });
        retryTimer = setTimeout(() => {
          retryTimer = null;
          if (!unmounted) placeOrder();
        }, action.retryAfterMs);
        break;

      case 'RATE_LIMIT': {
        const wait = Math.min(Number(details.retryAfter) > 0 ? Number(details.retryAfter) : 1, 30);

        submitLockUntil = Date.now() + wait * 1000;
        alert('summary', { cls: 'alert-warning', countdown: true });
        break;
      }

      case 'RELOAD':
        alert('summary', { cls: 'alert-warning', button: 'RELOAD' });
        break;

      default:
        // NETWORK keeps the key (press the button again, nobody is charged twice); GENERIC dropped it
        alert('summary');
    }
  }

  function applyFieldFailure(action) {
    const details = action.details;

    if (action.where === 'gift') {
      errors = { ...errors, gift: { ...errors.gift, recipient: 'FIELD_INVALID' } };
      focusId(fieldId('gift', 'recipient'));
      return;
    }

    if (action.where === 'shipping') {
      const shipping = { ...errors.shipping };
      const fields = Array.isArray(details.fields) ? details.fields : [];

      for (const field of fields)
        if (ADDRESS_FIELDS.includes(field)) shipping[field] = 'FIELD_REQUIRED';

      errors = { ...errors, shipping };

      // the client validation may know more than the server named: it marks what is missing too
      validateAndFocus().then((result) => {
        if (result.valid) focusId(firstInvalidId(errors) ?? fieldId('shipping', 'firstName'));
      });
      return;
    }

    // top-up amount: it comes from the address bar, so the reason is shown above the button
    setNotice('summary', {
      messageKey: action.messageKey,
      cls: 'alert-danger',
      values: details,
    });
  }

  function applyBillingFailure(details) {
    const mapped = mapBuyerFields(details.fields, config.addressFields, billingReq.country);
    const marked = {};

    for (const field of [...ADDRESS_FIELDS, ...BILLING_EXTRAS])
      if (mapped.has(field)) marked[field] = 'FIELD_REQUIRED';

    checkoutDraft.patch({ billingOpen: true });
    errors = { ...errors, billing: marked };
    focusId(firstInvalidId({ billing: marked }) ?? fieldId('billing', 'firstName'));
  }

  // ---- mount ----------------------------------------------------------------------------------------------------

  onMount(() => {
    initCurrency();

    const restored = checkoutDraft.restore(ownerKeyOf(get(user)));

    // credits are never pre-applied: a choice made before a reload or an earlier visit is not restored, the buyer
    // confirms it again (owner decision, 14 §10.5)
    const dropCredits = restoredCreditsPatch(restored);
    if (Object.keys(dropCredits).length > 0) checkoutDraft.patch(dropCredits);

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
      if (retryTimer !== null) clearTimeout(retryTimer);
      runner.stop();
      checkoutDraft.detach();
    };
  });
</script>
