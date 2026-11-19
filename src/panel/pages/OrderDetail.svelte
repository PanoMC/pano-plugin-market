<MarketLayout area="orders">
  {#snippet heading()}
    {#if order}
      <span class="fw-semibold">#{order.id}</span>
      <StatusBadge kind="order" value={order.status} />
      {#if order.testMode}
        <span class="badge text-bg-warning">{$_('common.test')}</span>
      {/if}
    {/if}
  {/snippet}

  {#snippet right()}
    {#if actions.length > 0}
      <div class="dropdown d-inline-block">
        <button
          type="button"
          class="btn btn-link"
          data-bs-toggle="dropdown"
          data-bs-popper-config={POPPER_FIXED}
          aria-expanded="false"
          title={$_('common.actions')}
          aria-label={$_('common.actions')}>
          <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
        </button>
        <div class="dropdown-menu dropdown-menu-end">
          {#each actions as action (action.id)}
            <button
              type="button"
              class="dropdown-item"
              class:link-danger={action.danger}
              onclick={() => runAction(action)}>
              <i class="fa-solid {action.icon} me-2" aria-hidden="true"></i>
              {$_(`pages.order-detail.actions.${action.id}`)}
            </button>
          {/each}
        </div>
      </div>
    {/if}
  {/snippet}

  {#if data.error || !detail}
    <LoadError error={data.error ?? 'NETWORK_ERROR'} />
  {:else}
    {#if attention.length > 0}
      <div
        class="alert {attentionDanger
          ? 'alert-danger'
          : 'alert-warning'} d-flex align-items-start mb-0"
        role="alert">
        <i
          class="fa-solid {attentionDanger
            ? 'fa-circle-exclamation'
            : 'fa-triangle-exclamation'} me-3 mt-1"
          aria-hidden="true"></i>
        <div>
          <h5 class="alert-heading mb-2">{$_('pages.order-detail.attention.title')}</h5>
          <ul class="mb-0">
            {#each attention as item (item.key)}
              <li>
                {item.key === 'revoke'
                  ? $_('pages.order-detail.revoke-alert.body', { values: item.values })
                  : $_(`pages.order-detail.attention.items.${item.key}`, { values: item.values })}
                {#if item.tab !== tab}
                  <button
                    type="button"
                    class="btn btn-link alert-link p-0 align-baseline ms-2 text-capitalize"
                    onclick={() => selectTab(item.tab)}>
                    {$_('pages.order-detail.attention.view')}
                  </button>
                {/if}
              </li>
            {/each}
          </ul>
        </div>
      </div>
    {/if}

    <SummaryCard {detail} {shipping} />

    <div class="row g-3">
      <div class="col-lg-8">
        <div class="vstack gap-3">
          <ul class="nav nav-pills gap-1" role="tablist">
            {#each TABS as key (key)}
              <li class="nav-item" role="presentation">
                <button
                  type="button"
                  class="nav-link"
                  class:active={tab === key}
                  role="tab"
                  aria-selected={tab === key}
                  onclick={() => selectTab(key)}>
                  {$_(`pages.order-detail.tabs.${key}`)}
                  {#if counts[key]}
                    <span class="badge text-bg-primary ms-1">{counts[key]}</span>
                  {/if}
                  {#if needsAttention.has(key)}
                    <i
                      class="fa-solid fa-circle-exclamation text-danger ms-1"
                      title={$_('pages.order-detail.tabs.attention')}
                      aria-label={$_('pages.order-detail.tabs.attention')}></i>
                  {/if}
                </button>
              </li>
            {/each}
          </ul>

          <!-- Hidden tabs stay mounted (d-none): cards keep their state, #deliveries keeps its target. -->
          <div class="vstack gap-3" class:d-none={tab !== 'overview'} role="tabpanel">
            <ItemsCard
              {detail}
              {user}
              onRerun={(item) => rerunModal?.open({ itemId: item.id })}
              onRevoke={revokeItem} />
            <TotalsCard order={detail.order} {ctx} {user} onMutate={mutate} />
          </div>

          <div class="vstack gap-3" class:d-none={tab !== 'payments'} role="tabpanel">
            <PaymentsCard
              {detail}
              {user}
              onEvents={(payment) => paymentEvents?.open(payment)}
              onQuery={queryPayment} />
            <RefundsCard {detail} {ctx} {user} onRetry={retryRefund} onCancel={cancelRefund} />
            <DisputesCard {detail} {user} onResolve={resolveDispute} />
          </div>

          <div class="vstack gap-3" class:d-none={tab !== 'fulfillment'} role="tabpanel">
            <DeliveriesCard
              {detail}
              {user}
              onRetry={retryDelivery}
              onCancel={cancelDelivery}
              onRerunAll={() => rerunModal?.open()}
              onRevokeAll={revokeAll} />
            {#if shipping}
              <ShipmentsCard
                {detail}
                {user}
                onCreate={EXTERNAL_MODALS.createShipment
                  ? () => externalRefs.createShipment?.open()
                  : null}
                onView={(shipment) => shipmentModal?.open(shipment.id)}
                onTrack={trackShipment}
                onRetry={retryShipment}
                onCancel={cancelShipment} />
            {/if}
          </div>

          <div class="vstack gap-3" class:d-none={tab !== 'history'} role="tabpanel">
            <MailsCard {detail} {user} onRetry={retryMail} />
            <TimelineCard {detail} />
          </div>

          <PluginHook name="market:panel:order-detail:bottom" props={{ order: detail.order }} />
        </div>
      </div>

      <div class="col-lg-4">
        <div class="vstack gap-3">
          <CustomerCard order={detail.order} {shipping} />
          <SubscriptionCard subscription={detail.subscription} />
          <InvoicesCard {detail} {ctx} {user} onRegenerate={regenerateInvoice} />
          <NoteCard order={detail.order} {user} onMutate={mutate} />
        </div>
      </div>
    </div>
  {/if}
</MarketLayout>

<OrderStatusModal
  bind:this={statusModal}
  orderId={order?.id}
  onDone={done}
  onStale={() => refresh()} />
<ReviewOrderModal bind:this={reviewModal} {order} onDone={done} onStale={() => refresh()} />
<DisputeModal bind:this={disputeModal} {order} {ctx} onDone={done} onStale={() => refresh()} />
<RerunDeliveryModal
  bind:this={rerunModal}
  {detail}
  {user}
  onDone={(body) => done(rerunOutcome(body))}
  onStale={() => refresh()} />
<ResendMailModal
  bind:this={resendModal}
  {order}
  shipments={detail?.shipments ?? []}
  onDone={done}
  onStale={() => refresh()} />
<PaymentEventsModal bind:this={paymentEvents} />
<ShipmentModal
  bind:this={shipmentModal}
  {user}
  onDone={done}
  onStale={() => refresh()}
  onRelease={releaseShipment} />
<ConfirmModal bind:this={confirmModal} />

{#each Object.entries(EXTERNAL_MODALS) as [id, Component] (id)}
  {#if Component}
    <Component
      bind:this={externalRefs[id]}
      {detail}
      {ctx}
      {user}
      onDone={done}
      onStale={() => refresh()} />
  {/if}
{/each}

<script module>
  import { api } from '@panomc/sdk/plugin-api';
  import { loadOrderDetailWith } from '../components/order-detail/load-core.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadOrderDetailWith({ get: (options) => api.panel.get(options) }, event);
  }
</script>

<script>
  // The page header's right column scrolls sideways, which clips a menu hanging below it; a fixed menu is not clipped.
  const POPPER_FIXED = '{"strategy":"fixed"}';

  import { page } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import { _, showSuccessToast } from '../../i18n';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PluginHook from '../components/PluginHook.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import CustomerCard from '../components/order-detail/CustomerCard.svelte';
  import DeliveriesCard from '../components/order-detail/DeliveriesCard.svelte';
  import DisputesCard from '../components/order-detail/DisputesCard.svelte';
  import InvoicesCard from '../components/order-detail/InvoicesCard.svelte';
  import ItemsCard from '../components/order-detail/ItemsCard.svelte';
  import MailsCard from '../components/order-detail/MailsCard.svelte';
  import NoteCard from '../components/order-detail/NoteCard.svelte';
  import PaymentEventsModal from '../components/order-detail/PaymentEventsModal.svelte';
  import PaymentsCard from '../components/order-detail/PaymentsCard.svelte';
  import RefundsCard from '../components/order-detail/RefundsCard.svelte';
  import ShipmentsCard from '../components/order-detail/ShipmentsCard.svelte';
  import SummaryCard from '../components/order-detail/SummaryCard.svelte';
  import { featureOn } from '../utils/features.js';
  import SubscriptionCard from '../components/order-detail/SubscriptionCard.svelte';
  import TimelineCard from '../components/order-detail/TimelineCard.svelte';
  import TotalsCard from '../components/order-detail/TotalsCard.svelte';
  import { orderActions, withAvailable } from '../components/order-detail/actions.js';
  import { EXTERNAL_MODALS } from '../components/order-detail/external.js';
  import {
    AUTO_REFRESH_MS,
    autoRefreshNeeded,
    createAutoRefresh,
  } from '../components/order-detail/model.js';
  import { performMutation } from '../components/order-detail/mutation.js';
  import {
    anonymizeRequest,
    chargebackActionsRequest,
    deliveryCancelRequest,
    deliveryRetryRequest,
    disputeStatusRequest,
    invoiceRegenerateRequest,
    mailRetryRequest,
    orderPath,
    paymentQueryRequest,
    refundCancelRequest,
    refundRetryRequest,
    rerunOutcome,
    revokeRequest,
    shipmentCancelRequest,
    shipmentRetryRequest,
    shipmentTrackRequest,
  } from '../components/order-detail/requests.js';
  import { releaseRequest } from '../utils/shipments.js';
  import { createAnchorScroller } from '../components/order-detail/scroll.js';
  import {
    TABS,
    attentionItems,
    initialTab,
    tabCounts,
    tabHref,
    tabsNeedingAttention,
  } from '../components/order-detail/tabs.js';
  import { tick } from 'svelte';
  import { anyModalOpen, fetchPath, send } from '../components/order-detail/send.js';
  import ShipmentModal from '../components/modals/ShipmentModal.svelte';
  import DisputeModal from '../components/modals/DisputeModal.svelte';
  import OrderStatusModal from '../components/modals/OrderStatusModal.svelte';
  import RerunDeliveryModal from '../components/modals/RerunDeliveryModal.svelte';
  import ResendMailModal from '../components/modals/ResendMailModal.svelte';
  import ReviewOrderModal from '../components/modals/ReviewOrderModal.svelte';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { toastError } from '../utils/toast.js';

  let { data } = $props();

  // The panel title names the order ("Order #1007") once its id is known; load() set the bare one.
  $effect(() => {
    if (data.id === null || data.id === undefined) return;
    $page.data?.pageTitle?.set?.(
      $_('pages.order-detail.title-with-id', { values: { id: data.id } }),
    );
  });

  // 13 §1.4: a detail page never calls invalidateAll() after a mutation; it keeps the loaded object
  // and re-GETs it. The host remounts the page on every load() re-run, so the initial value is enough.
  // svelte-ignore state_referenced_locally
  let detail = $state.raw(data.detail);

  let statusModal = $state(null);
  let reviewModal = $state(null);
  let disputeModal = $state(null);
  let rerunModal = $state(null);
  let resendModal = $state(null);
  let paymentEvents = $state(null);
  let shipmentModal = $state(null);
  let confirmModal = $state(null);
  let externalRefs = $state({});

  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const order = $derived(detail?.order ?? null);
  const actions = $derived(withAvailable(orderActions(detail, user), EXTERNAL_MODALS));
  const needsPolling = $derived(autoRefreshNeeded(detail));
  const shipping = featureOn('physicalShipping');

  // ---- tabs ------------------------------------------------------------------------------------
  // Layout only: every card is still on the page (hidden tabs stay mounted), grouped by subject.

  // svelte-ignore state_referenced_locally
  let tab = $state(initialTab($page.url.search, ''));
  const counts = $derived(tabCounts(detail, { shipping }));
  const attention = $derived(attentionItems(detail, { shipping }));
  const attentionDanger = $derived(attention.some((item) => item.danger));
  const needsAttention = $derived(tabsNeedingAttention(attention));

  /** Opens a tab and keeps it in the address (?tab=) so a reload or a shared link lands on it. */
  function selectTab(key) {
    tab = key;
    // a plain history entry swap, as CreateProduct does for its hash: no load() re-run, no remount
    history.replaceState(history.state, '', tabHref(window.location.href, key));
  }

  // ---- data ------------------------------------------------------------------------------------

  let refreshRun = 0;

  /** Re-GETs the order. `silent` (auto refresh) never toasts; returns false on failure. */
  async function refresh(silent = false) {
    if (!data.id) return false;
    const mine = ++refreshRun;
    const result = await fetchPath(orderPath(data.id));
    if (mine !== refreshRun) return true; // a newer refresh answered meanwhile
    if (!result.ok || !result.body.order) {
      if (!silent) toastError($_, result.ok ? { error: 'NETWORK_ERROR', body: {} } : result);
      return false;
    }
    detail = result.body;
    return true;
  }

  const autoRefresh = createAutoRefresh(() => refresh(true));

  function toast(spec) {
    if (!spec) return;
    if (typeof spec === 'string') {
      showSuccessToast($_(spec));
    } else if (spec.variant === 'info') {
      showToast($_(spec.key, { values: spec.values }));
    } else {
      showSuccessToast($_(spec.key, { values: spec.values }));
    }
  }

  /**
   * Sends a request, refreshes the held order and toasts. `successToast` = translation key, a
   * `{ key, values, variant }` object or a function of the response body. Returns the call() result.
   */
  function mutate(request, successToast) {
    autoRefresh.reset();
    return performMutation(
      {
        send,
        refresh: () => refresh(),
        success: (body) =>
          toast(typeof successToast === 'function' ? successToast(body) : successToast),
        failure: (result) => toastError($_, result),
      },
      request,
    );
  }

  // Modals have hidden themselves already: refresh, then toast.
  async function done(spec) {
    autoRefresh.reset();
    await refresh();
    toast(spec);
  }

  /** ConfirmModal flow: a failed (non stale) request keeps the modal open for another try. */
  function ask({ icon, title, description, confirmLabel, variant = 'primary', request, success }) {
    confirmModal?.open({
      icon,
      title,
      description,
      confirmLabel,
      variant,
      onConfirm: async () => {
        const result = await mutate(request, success);
        if (!result.ok && !result.stale) return false;
      },
    });
  }

  const text = (key, values) =>
    $_(`pages.order-detail.confirm.${key}`, values ? { values } : undefined);

  // ---- page level actions ------------------------------------------------------------------------

  function runAction(action) {
    if (action.kind === 'external') {
      externalRefs[action.id]?.open();
    } else if (action.kind === 'confirm') {
      runConfirm(action.id);
    } else if (action.modal === 'status') {
      statusModal?.open(action.id);
    } else if (action.modal === 'review') {
      reviewModal?.open();
    } else if (action.modal === 'dispute') {
      disputeModal?.open();
    } else if (action.modal === 'rerun') {
      rerunModal?.open();
    } else if (action.modal === 'resend') {
      resendModal?.open();
    }
  }

  function runConfirm(id) {
    if (id === 'revoke') revokeAll();
    else if (id === 'runChargebackActions')
      ask({
        icon: 'fa-solid fa-bolt',
        title: text('chargeback.title'),
        description: text('chargeback.description', {
          player: order?.recipientUsername || order?.playerUsername || '',
        }),
        confirmLabel: text('chargeback.cta'),
        variant: 'danger',
        request: chargebackActionsRequest(order.id),
        success: (body) => ({
          key: 'pages.order-detail.toast.chargeback',
          values: { count: Number(body?.created) || 0 },
        }),
      });
    else if (id === 'anonymize')
      ask({
        icon: 'fa-solid fa-user-secret',
        title: text('anonymize.title'),
        description: text('anonymize.description'),
        confirmLabel: text('anonymize.cta'),
        variant: 'danger',
        request: anonymizeRequest(order.id),
        success: 'pages.order-detail.toast.anonymized',
      });
  }

  function revokeAll() {
    ask({
      icon: 'fa-solid fa-user-slash',
      title: text('revoke-all.title'),
      description: text('revoke-all.description'),
      confirmLabel: text('revoke-all.cta'),
      variant: 'danger',
      request: revokeRequest(order.id),
      success: (body) => ({
        key: 'pages.order-detail.toast.revoked',
        values: { count: Number(body?.created) || 0 },
      }),
    });
  }

  // ---- row level actions -------------------------------------------------------------------------

  function revokeItem(item) {
    ask({
      icon: 'fa-solid fa-user-slash',
      title: text('revoke-item.title'),
      description: text('revoke-item.description', { name: item.productName ?? '' }),
      confirmLabel: text('revoke-item.cta'),
      variant: 'danger',
      request: revokeRequest(order.id, [item.id]),
      success: (body) => ({
        key: 'pages.order-detail.toast.revoked',
        values: { count: Number(body?.created) || 0 },
      }),
    });
  }

  function queryPayment(payment) {
    return mutate(paymentQueryRequest(payment.id), 'pages.order-detail.toast.payment-queried');
  }

  function retryRefund(refund) {
    ask({
      icon: 'fa-solid fa-rotate-right',
      title: text('refund-retry.title'),
      description: text('refund-retry.description'),
      confirmLabel: text('refund-retry.cta'),
      request: refundRetryRequest(refund.id),
      success: 'pages.order-detail.toast.refund-retried',
    });
  }

  function cancelRefund(refund) {
    ask({
      icon: 'fa-solid fa-ban',
      title: text('refund-cancel.title'),
      description: text('refund-cancel.description'),
      confirmLabel: text('refund-cancel.cta'),
      variant: 'danger',
      request: refundCancelRequest(refund.id),
      success: 'pages.order-detail.toast.refund-cancelled',
    });
  }

  function retryDelivery(delivery, kind) {
    const key = kind === 'offer-again' ? 'delivery-offer-again' : 'delivery-retry';
    ask({
      icon: 'fa-solid fa-rotate-right',
      title: text(`${key}.title`),
      description: text(`${key}.description`),
      confirmLabel: text(`${key}.cta`),
      request: deliveryRetryRequest(delivery.id),
      success: 'pages.order-detail.toast.delivery-retried',
    });
  }

  function cancelDelivery(delivery) {
    ask({
      icon: 'fa-solid fa-ban',
      title: text('delivery-cancel.title'),
      description: text('delivery-cancel.description'),
      confirmLabel: text('delivery-cancel.cta'),
      variant: 'danger',
      request: deliveryCancelRequest(delivery.id),
      success: 'pages.order-detail.toast.delivery-cancelled',
    });
  }

  function resolveDispute(dispute, status) {
    ask({
      icon: 'fa-solid fa-gavel',
      title: text(`dispute-${status}.title`),
      description: text(`dispute-${status}.description`),
      confirmLabel: text(`dispute-${status}.cta`),
      variant: status === 'LOST' ? 'danger' : 'primary',
      request: disputeStatusRequest(dispute.id, status),
      success: 'pages.order-detail.toast.dispute-resolved',
    });
  }

  function retryMail(mail) {
    return mutate(mailRetryRequest(mail.id), 'pages.order-detail.toast.mail-retried');
  }

  function trackShipment(shipment) {
    return mutate(shipmentTrackRequest(shipment.id), 'pages.order-detail.toast.shipment-tracked');
  }

  function retryShipment(shipment) {
    return mutate(shipmentRetryRequest(shipment.id), 'pages.order-detail.toast.shipment-retried');
  }

  // ShipmentModal hid itself before calling: the same "Release Items" confirmation as the Shipments page.
  function releaseShipment(shipment) {
    ask({
      icon: 'fa-solid fa-box-open',
      title: $_('pages.shipments.release.title'),
      description: $_('pages.shipments.release.description'),
      confirmLabel: $_('pages.shipments.actions.release'),
      request: releaseRequest(shipment.id),
      success: 'pages.shipments.toast.released',
    });
  }

  function cancelShipment(shipment) {
    ask({
      icon: 'fa-solid fa-ban',
      title: text('shipment-cancel.title'),
      description: text('shipment-cancel.description'),
      confirmLabel: text('shipment-cancel.cta'),
      variant: 'danger',
      request: shipmentCancelRequest(shipment.id),
      success: 'pages.order-detail.toast.shipment-cancelled',
    });
  }

  function regenerateInvoice() {
    ask({
      icon: 'fa-solid fa-file-invoice',
      title: text('invoice-regenerate.title'),
      description: text('invoice-regenerate.description'),
      confirmLabel: text('invoice-regenerate.cta'),
      request: invoiceRegenerateRequest(order.id),
      success: 'pages.order-detail.toast.invoice-regenerated',
    });
  }

  // ---- auto refresh (13 §6): visible document, no open modal, every 15 s, at most 40 times ---------

  $effect(() => {
    if (!needsPolling) return;
    const timer = setInterval(
      () =>
        autoRefresh.tick({
          visible: document.visibilityState === 'visible',
          modalOpen: anyModalOpen(),
        }),
      AUTO_REFRESH_MS,
    );
    return () => clearInterval(timer);
  });

  // `/market/orders/detail/<id>#deliveries` (the orders list "Re-run Delivery" link).
  // Once only: `detail` is re-assigned on every refresh and must not pull the viewport back.
  const scrollToDeliveries = createAnchorScroller('deliveries', (id) =>
    document.getElementById(id),
  );
  let anchorHandled = false;
  $effect(() => {
    const hash = window.location.hash;
    if (anchorHandled || !detail || hash !== '#deliveries') return;
    anchorHandled = true;
    // the card sits on the Fulfillment tab: open it first, scroll once it is shown
    tab = initialTab(window.location.search, hash);
    tick().then(() => scrollToDeliveries(hash, true));
  });
</script>
