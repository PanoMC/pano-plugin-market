{#if status === 'ERROR'}
  <div class="market-payment-embedded market-payment-embedded__alert alert alert-warning mb-0" role="alert">
    {$_('theme.order.payment-ui-error')}
  </div>
{:else if status === 'LOADING'}
  <div
    class="market-payment-embedded d-flex align-items-center gap-2 text-body-secondary"
    role="status">
    <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
    <span>{$_('theme.order.payment-ui-loading')}</span>
  </div>
{:else if status === 'SLOT'}
  <!-- a gateway view injected into the slot market:order:payment (doc 01 section 6), id = its gateway key -->
  <PluginSlot
    id="market:order:payment"
    props={{
      order,
      payment,
      props: start.embedded?.props ?? {},
      locale: $currentLanguage?.code,
      continuePayment,
      refresh,
    }}
    filter={slotMatch} />
{:else if status === 'GENERIC'}
  <GenericPaymentForm {fields} {continuePayment} {waiting} {seconds} />
{:else}
  <div class="market-payment-embedded market-payment-embedded__alert alert alert-warning mb-0" role="alert">
    {$_('theme.order.payment-ui-missing')}
  </div>
{/if}

<script>
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import { PluginSlot } from '@panomc/sdk/components/theme';
  import { onMount, tick, untrack } from 'svelte';
  import { plugin } from '@panomc/sdk/controllers';
  import {
    COMPONENT_VIEW_PREFIX,
    embeddedFallback,
    embeddedFields,
    isComponentId,
    loadScripts,
    scriptsPlan,
  } from '../../lib/paymentPanel.js';
  import GenericPaymentForm from './GenericPaymentForm.svelte';

  const market = plugin('market');
  const { _ } = market;

  /**
   * In-page step of a gateway (14 §11.4 `EMBEDDED`), browser only (F12: a plugin client bundle shares the host
   * Svelte runtime; rendering another plugin's component on the server is unproven). Order: load
   * `embedded.scripts` (https only), then open the slot `market:order:payment` with
   * `{ order, payment, props, locale, continuePayment, refresh }`; the gateway's view is injected into it. Nothing
   * registered for this start (plugin UI missing or version-skewed) falls through to the generic form when the start
   * carries `fields`, else to the notice.
   * `onuierror()` tells the panel that this start has no usable UI (it then opens the method picker).
   */
  let {
    order,
    payment,
    start,
    continuePayment,
    refresh = () => {},
    waiting = false,
    seconds = 0,
    onuierror = () => {},
  } = $props();

  let status = $state('LOADING');

  /** A final status; one without a usable UI tells the panel (it then opens the method picker). */
  function setStatus(next) {
    status = next;

    if (next === 'ERROR' || next === 'MISSING') onuierror();
  }

  // A gateway plugin injects its view into the slot `market:order:payment` with `id` = its gateway key (or the whole
  // component id the start carries). The filter of the slot also tells whether anything matched: it runs while the slot
  // renders, so a start nobody answers can fall through to the generic form instead of an empty box.
  const componentId = untrack(() => start?.embedded?.component);
  const gatewayKey = isComponentId(componentId)
    ? componentId.slice(COMPONENT_VIEW_PREFIX.length)
    : null;
  let slotMatched = false;
  const slotMatch = (item) => {
    const matches = gatewayKey !== null && (item?.id === componentId || item?.id === gatewayKey);
    if (matches) slotMatched = true;

    return matches;
  };

  // the panel re-keys this component for every new start, so the start is read once
  const fields = untrack(() => embeddedFields(start));

  onMount(() => {
    let alive = true;

    (async () => {
      const current = untrack(() => start);
      const plan = scriptsPlan(current?.embedded?.scripts);

      if (!plan.valid) {
        if (alive) setStatus('ERROR');
        return;
      }

      try {
        if (plan.urls.length > 0) await loadScripts(plan.urls);
      } catch (e) {
        if (alive) setStatus('ERROR');
        return;
      }

      if (!alive) return;

      if (isComponentId(current?.embedded?.component)) {
        status = 'SLOT';
        await tick();

        if (alive && !slotMatched) setStatus(embeddedFallback(current));
        return;
      }

      setStatus(embeddedFallback(current));
    })();

    return () => {
      alive = false;
    };
  });
</script>
