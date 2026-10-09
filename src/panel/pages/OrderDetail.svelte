<MarketLayout area="orders">
  {#snippet left()}
    <div class="d-flex align-items-center flex-wrap gap-2">
      <a class="btn btn-link px-0" href="{base}/market/orders">
        <i class="fa-solid fa-arrow-left me-1" aria-hidden="true"></i>
        {$_('pages.order-detail.back')}
      </a>
      {#if order}
        <span class="fw-semibold">#{order.id}</span>
        <StatusBadge kind="order" value={order.status} />
        {#if order.testMode}
          <span class="badge text-bg-warning">{$_('common.test')}</span>
        {/if}
      {/if}
    </div>
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
        <div class="dropdown-menu dropdown-menu-end animate__animated animate__fadeIn">
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
    {#if (detail.revokePending ?? 0) > 0 || (detail.revokeFailed ?? 0) > 0}
      <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
        <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
        <div>
          <b>{$_('pages.order-detail.revoke-alert.title')}</b>
          <div>
            {$_('pages.order-detail.revoke-alert.body', {
              values: { pending: detail.revokePending ?? 0, failed: detail.revokeFailed ?? 0 },
            })}
          </div>
        </div>
      </div>
    {/if}

    <div class="row g-3">
      <div class="col-lg-8">
        <div class="vstack gap-3">
          <ItemsCard
            {detail}
            {user}
            onRerun={(item) => rerunModal?.open({ itemId: item.id })}
            onRevoke={revokeItem} />
          <TotalsCard order={detail.order} {ctx} {user} onMutate={mutate} />
          <PaymentsCard
            {detail}
            {user}
            onEvents={(payment) => paymentEvents?.open(payment)}
            onQuery={queryPayment} />
          <RefundsCard {detail} {ctx} {user} onRetry={retryRefund} onCancel={cancelRefund} />
          <DeliveriesCard
            {detail}
            {user}
            onRetry={retryDelivery}
            onCancel={cancelDelivery}
            onRerunAll={() => rerunModal?.open()}
            onRevokeAll={revokeAll} />
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
          <DisputesCard {detail} {user} onResolve={resolveDispute} />
          <MailsCard {detail} {user} onRetry={retryMail} />
          <TimelineCard {detail} />
          <PluginHook name="market:panel:order-detail:bottom" props={{ order: detail.order }} />
        </div>
      </div>

      <div class="col-lg-4">
        <div class="vstack gap-3">
          <CustomerCard order={detail.order} />
          {#if 'billingInfo' in detail.order}
            <AddressCard address={detail.order.billingInfo} billing />
          {/if}
          {#if 'shippingAddress' in detail.order}
            <AddressCard address={detail.order.shippingAddress} />
          {/if}
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

  import { base, page } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import { _, showSuccessToast } from '../../i18n';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PluginHook from '../components/PluginHook.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import AddressCard from '../components/order-detail/AddressCard.svelte';
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
  import SubscriptionCard from '../components/order-detail/SubscriptionCard.svelte';
  import TimelineCard from '../components/order-detail/TimelineCard.svelte';
  import TotalsCard from '../components/order-detail/TotalsCard.svelte';
  import { orderActions, withAvailable } from '../components/order-detail/actions.js';
  import { EXTERNAL_MODALS } from '../components/order-detail/external.js';
  import {
    AUTO_REFRESH_MAX,
    AUTO_REFRESH_MS,
    autoRefreshNeeded,
    shouldAutoRefresh,
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

  // ---- data ------------------------------------------------------------------------------------

  let refreshRun = 0;
  let autoRuns = 0;

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
    autoRuns = 0;
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
    autoRuns = 0;
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
    const timer = setInterval(async () => {
      if (autoRuns >= AUTO_REFRESH_MAX) {
        clearInterval(timer);
        return;
      }
      const due = shouldAutoRefresh({
        needed: true,
        visible: document.visibilityState === 'visible',
        modalOpen: anyModalOpen(),
        runs: autoRuns,
      });
      if (!due) return;
      autoRuns++;
      const ok = await refresh(true);
      if (!ok) clearInterval(timer); // stop on the first failed refresh
    }, AUTO_REFRESH_MS);
    return () => clearInterval(timer);
  });

  // `/market/orders/detail/<id>#deliveries` (the orders list "Re-run Delivery" link).
  // Once only: `detail` is re-assigned on every refresh and must not pull the viewport back.
  const scrollToDeliveries = createAnchorScroller('deliveries', (id) =>
    document.getElementById(id),
  );
  $effect(() => {
    scrollToDeliveries(window.location.hash, !!detail);
  });
</script>
