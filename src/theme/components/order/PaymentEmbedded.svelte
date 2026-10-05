{#if status === 'ERROR'}
  <div class="alert alert-warning mb-0" role="alert">{$_('theme.order.payment-ui-error')}</div>
{:else if status === 'LOADING'}
  <div class="d-flex align-items-center gap-2 text-body-secondary" role="status">
    <span class="spinner-border spinner-border-sm" aria-hidden="true"></span>
    <span>{$_('theme.order.payment-ui-loading')}</span>
  </div>
{:else if status === 'COMPONENT' && Plugin}
  <Plugin
    {order}
    {payment}
    props={start.embedded?.props ?? {}}
    locale={$currentLanguage?.code}
    {continuePayment}
    {refresh} />
{:else if status === 'GENERIC'}
  <GenericPaymentForm {fields} {continuePayment} {waiting} {seconds} />
{:else}
  <div class="alert alert-warning mb-0" role="alert">{$_('theme.order.payment-ui-missing')}</div>
{/if}

<script>
  import { currentLanguage } from '@panomc/sdk/utils/language';
  import { onMount, untrack } from 'svelte';
  import { _ } from '../../../i18n.js';
  import {
    embeddedFallback,
    embeddedFields,
    firstComponentItem,
    isComponentId,
    loadScripts,
    resolveComponent,
    scriptsPlan,
  } from '../../lib/paymentPanel.js';
  import { view } from '../../utils/host.js';
  import GenericPaymentForm from './GenericPaymentForm.svelte';

  /**
   * In-page step of a gateway (14 §11.4 `EMBEDDED`), browser only (F12: a plugin client bundle shares the host
   * Svelte runtime; rendering another plugin's component on the server is unproven). Order: load
   * `embedded.scripts` (https only), then look up the plugin component under `embedded.component` and render it
   * with `{ order, payment, props, locale, continuePayment, refresh }`. No component registered (plugin UI missing
   * or version-skewed) falls through to the generic form when the start carries `fields`, else to the notice.
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
  let Plugin = $state.raw(null);

  /** A final status; one without a usable UI tells the panel (it then opens the method picker). */
  function setStatus(next) {
    status = next;

    if (next === 'ERROR' || next === 'MISSING') onuierror();
  }

  // the registry of the host (a missing or broken one means: no component)
  function items(id) {
    try {
      return view(id);
    } catch (e) {
      return [];
    }
  }

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

      const id = current?.embedded?.component;
      const resolved = isComponentId(id)
        ? await resolveComponent(firstComponentItem(items(id)))
        : null;

      if (!alive) return;

      if (resolved) {
        Plugin = resolved;
        status = 'COMPONENT';
      } else setStatus(embeddedFallback(current));
    })();

    return () => {
      alive = false;
    };
  });
</script>
