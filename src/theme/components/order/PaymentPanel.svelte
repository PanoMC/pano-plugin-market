{#if model.show}
  <section class="card" aria-labelledby="market-order-payment-title">
    <div class="card-body vstack gap-3">
      <h2 class="h5 mb-0" id="market-order-payment-title">
        {$_(model.readonly ? 'theme.order.payment-details' : 'theme.order.payment-title')}
      </h2>

      {#if leaving}
        <div class="d-flex align-items-center gap-2 text-body-secondary" role="status">
          <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
          <span>{$_('theme.checkout.redirecting')}</span>
        </div>
      {:else if model.readonly}
        <PaymentInstructions
          {id}
          {order}
          {token}
          readonly
          instructions={model.start.instructions}
          {onrefetch} />
      {:else}
        {#key startSig}
          {#if model.mode === 'LINK'}
            <div>
              <a class="btn btn-primary" href={model.start.url}>
                <i class="fa-solid fa-credit-card me-1" aria-hidden="true"></i>{$_(
                  'theme.order.payment-continue',
                )}
              </a>
            </div>
          {:else if model.mode === 'IFRAME'}
            <PaymentIframe
              iframe={model.start.iframe}
              title={order.payment?.label ?? $_('theme.order.payment-title')}
              onerror={() => (failedSig = startSig)} />
          {:else if model.mode === 'COMPONENT' || model.mode === 'GENERIC' || model.mode === 'MISSING'}
            <PaymentEmbedded
              {order}
              payment={{ ...order.payment, start: model.start }}
              start={model.start}
              {continuePayment}
              refresh={onrefetch}
              waiting={continueWaiting}
              seconds={continueSeconds}
              onuierror={() => (failedSig = startSig)} />
          {:else if model.mode === 'INSTRUCTIONS'}
            <PaymentInstructions
              {id}
              {order}
              {token}
              instructions={model.start.instructions}
              {onrefetch} />
          {:else if model.mode === 'UI_ERROR'}
            <div class="alert alert-warning mb-0" role="alert">
              {$_('theme.order.payment-ui-error')}
            </div>
          {/if}
        {/key}

        {#if model.canRetry}
          <div class="vstack gap-3">
            <div>
              <button
                type="button"
                class="btn btn-link px-0"
                aria-expanded={methodsOpen ? 'true' : 'false'}
                aria-controls="market-order-payment-other"
                onclick={() => (userOpen = !methodsOpen)}>
                {$_('theme.order.payment-other')}
              </button>
            </div>

            {#if methodsOpen}
              <div class="vstack gap-3" id="market-order-payment-other">
                <PaymentMethodPicker
                  quote={pickerQuote(order)}
                  selectedId={chosen}
                  currency={order.currency}
                  {removeCents}
                  disabled={paying}
                  onselect={(methodId) => (selectedId = methodId)} />

                {#if credits}
                  <CreditsSection
                    {credits}
                    config={{ mixedCredit: true }}
                    {useCredits}
                    method={chosenMethod}
                    currency={order.currency}
                    {removeCents}
                    disabled={paying}
                    onchange={(patch) => (useCredits = patch.useCredits ?? null)} />
                {/if}

                {#if billing.open && billingFields !== null}
                  <BillingSection
                    req={billing.req}
                    info={billingInfo}
                    errors={billingErrors}
                    onchange={(patch) => (billingInfo = { ...billingInfo, ...patch })} />
                {/if}

                {#if alertKey}
                  <div class="alert alert-warning mb-0" role="alert">
                    {$_(alertKey, { values: { seconds: waitLeft } })}
                  </div>
                {/if}

                <div>
                  <button
                    type="button"
                    class="btn btn-primary"
                    disabled={!canPay({
                      order,
                      methodId: chosen,
                      busy: paying,
                      waiting: waitLeft > 0,
                    })}
                    onclick={pay}>
                    {#if paying}
                      <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
                    {/if}
                    {$_('theme.order.payment-pay')}
                  </button>
                </div>
              </div>
            {/if}
          </div>
        {/if}
      {/if}
    </div>
  </section>
{/if}

<script>
  import { base } from '@panomc/sdk/svelte';
  import { onMount, untrack } from 'svelte';
  import { _ } from '../../../i18n.js';
  import {
    billingStep,
    canPay,
    continueFailure,
    creditsForOrder,
    defaultMethodId,
    emptyBilling,
    nextStep,
    panelModel,
    payBody,
    payFailure,
    pickerQuote,
    startSignature,
  } from '../../lib/paymentPanel.js';
  import { selectedMethod } from '../../lib/paymentModel.js';
  import { now } from '../../stores/clock.js';
  import { tokenHeaders } from '../../stores/orderTokens.js';
  import { call } from '../../utils/api.js';
  import BillingSection from '../checkout/BillingSection.svelte';
  import CreditsSection from '../checkout/CreditsSection.svelte';
  import PaymentMethodPicker from '../checkout/PaymentMethodPicker.svelte';
  import PaymentEmbedded from './PaymentEmbedded.svelte';
  import PaymentIframe from './PaymentIframe.svelte';
  import PaymentInstructions from './PaymentInstructions.svelte';

  /**
   * Payment part of the order page (14 §11.4): the start UI of the newest attempt (a link, an iframe, an embedded
   * plugin component or generic form, instructions), read-only instructions while PROCESSING, and "pay another
   * way". `id` = publicId, `order` = the owner OrderView, `token` = access token in use or null, `view` =
   * viewState() of the order, `onrefetch()` reloads the order (and restarts polling).
   */
  let { id, order, view, token = null, removeCents = false, onrefetch = () => {} } = $props();

  // the browser origin, known after mount (links to the attempt page are checked against it)
  let context = $state({});

  onMount(() => {
    context = { origin: window.location.origin, base };
  });

  // a start returned by continue / pay, shown until the re-fetched order carries its own
  let override = $state.raw(null);
  // the start whose UI turned out to be unusable (script failed, component and fields missing, bad iframe)
  let failedSig = $state('');
  let userOpen = $state(null);
  let leaving = $state(false);

  let selectedId = $state(untrack(() => defaultMethodId(order)));
  let useCredits = $state(null);
  let paying = $state(false);
  let alertKey = $state('');
  let waitUntil = $state(0);
  let continueWaitUntil = $state(0);
  let billingFields = $state(null);
  let billingInfo = $state(emptyBilling());
  let billingErrors = $state({});

  const nowMs = $derived($now);
  const model = $derived(panelModel({ order, view, now: nowMs, override, context }));
  const startSig = $derived(startSignature(model.start));
  const uiFailed = $derived(failedSig !== '' && failedSig === startSig);
  const methodsOpen = $derived(userOpen ?? (model.methodsOpen || uiFailed));
  const credits = $derived(creditsForOrder(order));
  const chosen = $derived(
    (order.paymentMethods ?? []).some((m) => m?.id === selectedId && m.available !== false)
      ? selectedId
      : null,
  );
  const chosenMethod = $derived(selectedMethod(pickerQuote(order), chosen));
  const billing = $derived(billingStep(billingFields, billingInfo));
  const waitLeft = $derived(waitUntil > nowMs ? Math.ceil((waitUntil - nowMs) / 1000) : 0);
  const continueSeconds = $derived(
    continueWaitUntil > nowMs ? Math.ceil((continueWaitUntil - nowMs) / 1000) : 0,
  );
  const continueWaiting = $derived(continueSeconds > 0);

  let continuing = false;

  /** Follows the start that `continue` / `pay` answered (14 §10.8 kinds leave the page, in-page ones re-render). */
  async function follow(payment) {
    const step = nextStep(payment, order, context);

    if (step.type === 'ASSIGN') {
      leaving = true;
      window.location.assign(step.url);
      return;
    }

    if (step.type === 'SHOW') override = { base: order.payment?.start ?? null, value: step.start };

    await onrefetch();
  }

  /**
   * `continuePayment(values)` for the generic form and a plugin component: `POST …/payment/continue`. Answers
   * `{ ok: true }` or `{ ok: false, alertKey, seconds? }` (never the gateway's text).
   */
  async function continuePayment(values) {
    if (continuing || continueWaiting) return { ok: false, alertKey: 'theme.errors.GENERIC' };

    continuing = true;

    try {
      const res = await call(
        'POST',
        `/api/market/orders/${encodeURIComponent(id)}/payment/continue`,
        { body: { values }, headers: tokenHeaders(id, token) },
      );

      if (res.ok) {
        if (res.payment) await follow(res.payment);
        else await onrefetch();

        return { ok: true };
      }

      const failure = continueFailure(res);

      if (failure.seconds) continueWaitUntil = Date.now() + failure.seconds * 1000;
      if (failure.openMethods) failedSig = startSig;
      if (failure.refetch) await onrefetch();

      return { ok: false, alertKey: failure.alertKey, seconds: failure.seconds };
    } finally {
      continuing = false;
    }
  }

  /** "Pay": `POST …/pay`, then follow the new start (in-page kinds: re-fetch the order). */
  async function pay() {
    if (!canPay({ order, methodId: chosen, busy: paying, waiting: waitLeft > 0 })) return;

    alertKey = '';

    let billingInfoBody = null;

    if (billingFields !== null && billing.open) {
      billingErrors = billing.errors;

      if (!billing.body) {
        document.getElementById(billing.firstId)?.focus();
        return;
      }

      billingInfoBody = billing.body;
    }

    paying = true;

    const res = await call('POST', `/api/market/orders/${encodeURIComponent(id)}/pay`, {
      body: payBody({
        methodId: chosen,
        useCredits,
        credits: order.credits,
        billingInfo: billingInfoBody,
      }),
      headers: tokenHeaders(id, token),
    });

    paying = false;

    if (res.ok) {
      billingFields = null;
      billingErrors = {};
      userOpen = false;
      await follow(res.payment);
      return;
    }

    const failure = payFailure(res);

    alertKey = failure.alertKey;

    if (failure.seconds) waitUntil = Date.now() + failure.seconds * 1000;
    if (failure.clearMethod) selectedId = null;
    if (failure.clearCredits) useCredits = null;

    if (failure.billingFields) {
      billingFields = failure.billingFields;
      billingErrors = {};
    }

    if (failure.refetch) await onrefetch();
  }
</script>
