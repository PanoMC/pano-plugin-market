{#if canCancel || canInvoice}
  <div class="vstack gap-2">
    <div class="d-flex flex-wrap gap-2">
      {#if canInvoice}
        {#if useBlob}
          <button
            type="button"
            class="btn btn-outline-secondary btn-sm"
            disabled={busy}
            onclick={downloadInvoice}>
            <i class="fa-solid fa-file-invoice me-1" aria-hidden="true"></i>{$_(
              'theme.order.invoice',
            )}
          </button>
        {:else}
          <a class="btn btn-outline-secondary btn-sm" href="{base}{invoicePath(id)}" download>
            <i class="fa-solid fa-file-invoice me-1" aria-hidden="true"></i>{$_(
              'theme.order.invoice',
            )}
          </a>
        {/if}
      {/if}

      {#if canCancel}
        <button
          type="button"
          class="btn btn-outline-danger btn-sm"
          disabled={busy}
          onclick={() => confirmCancel?.show()}>
          {$_('theme.order.cancel')}
        </button>
      {/if}
    </div>

    {#if errorKey}
      <ErrorAlert message={$_(errorKey)} />
    {/if}
  </div>
{/if}

{#if canCancel}
  <ConfirmModal
    bind:this={confirmCancel}
    id="marketCancelOrderModal"
    title={$_('theme.order.cancel-title')}
    message={$_('theme.order.cancel-message')}
    confirmLabel={$_('theme.order.cancel-confirm')}
    cancelLabel={$_('theme.order.cancel-keep')}
    onconfirm={cancelOrder} />
{/if}

<script>
  import { base } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import { _ } from '../../../i18n.js';
  import { messageKey } from '../../lib/errorMap.js';
  import { invoiceNeedsBlob, invoicePath } from '../../lib/orderState.js';
  import { tokenHeaders } from '../../stores/orderTokens.js';
  import { call } from '../../utils/api.js';
  import ConfirmModal from '../common/ConfirmModal.svelte';
  import ErrorAlert from '../common/ErrorAlert.svelte';

  /**
   * Cancel and invoice of the owner view (14 §11.4). `id` = publicId, `token` = the access token in use (or
   * null: session owner), `view` = viewState(). `onrefetch()` reloads the order (and restarts polling).
   */
  let { id, order, view, token = null, onrefetch = () => {} } = $props();

  let confirmCancel = $state();
  let busy = $state(false);
  let errorKey = $state('');

  const canCancel = $derived(!view.limited && view.panels.cancel && order.canCancel === true);
  const canInvoice = $derived(
    !view.limited && view.panels.invoice && order.invoiceAvailable === true,
  );
  const useBlob = $derived(invoiceNeedsBlob(token));

  async function cancelOrder() {
    if (busy) return;
    busy = true;
    errorKey = '';

    const res = await call('POST', `/api/market/orders/${id}/cancel`, {
      headers: tokenHeaders(id, token),
    });

    busy = false;

    if (res.ok) {
      await onrefetch();
      return;
    }

    if (res.code === 'ORDER_NOT_CANCELLABLE') {
      showToast(`plugins.pano-plugin-market.${messageKey('ORDER_NOT_CANCELLABLE')}`);
      await onrefetch();
      return;
    }

    errorKey = messageKey(res.code);
  }

  async function downloadInvoice() {
    if (busy) return;
    busy = true;
    errorKey = '';

    const res = await call('GET', invoicePath(id), {
      headers: tokenHeaders(id, token),
      blob: true,
    });

    busy = false;

    // a non-PDF answer (a JSON error envelope, a stray HTML page) is never offered as a download
    if (!res.ok || !res.blob || res.blob.type !== 'application/pdf') {
      errorKey = messageKey(res.ok ? 'GENERIC' : res.code);
      return;
    }

    const url = URL.createObjectURL(res.blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `invoice-${order.number ?? id}.pdf`;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }
</script>
