<script>
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _, showSuccessToast, showErrorToast } from '../../../i18n';

  let { orderId = null, currencySymbol = '', onUpdated = () => {} } = $props();

  // Currency code -> symbol. An order can carry a currency different from the
  // current sales currency (historical orders), so the total is shown in the
  // order's own symbol, falling back to the global sales symbol.
  const CURRENCY_SYMBOLS = { TRY: '₺', USD: '$', EUR: '€', GBP: '£' };

  // backend OrderStatus name -> badge display
  const ORDER_STATUS = {
    COMPLETED: { key: 'pages.orders.status-completed', cls: 'text-bg-success' },
    REFUNDED: { key: 'pages.orders.status-refunded', cls: 'text-bg-danger' },
    PENDING: { key: 'pages.orders.status-pending', cls: 'text-bg-warning' },
    FAILED: { key: 'pages.orders.status-failed', cls: 'text-bg-danger' }
  };

  let order = $state(null);
  let loading = $state(false);
  let loadError = $state(false);
  let saving = $state(false);
  let refreshing = $state(false);
  let rateInput = $state('');

  let salesSymbol = $derived(order ? CURRENCY_SYMBOLS[order.currency] || currencySymbol : currencySymbol);
  let statsSymbol = $derived(order?.statsCurrencySymbol || '');

  // The rate actually applied to this order: the frozen rate when set, otherwise
  // the effective rate derived from the converted stats value.
  let effectiveRate = $derived.by(() => {
    if (order?.exchangeRate != null) return order.exchangeRate;
    const total = Number(order?.totalPrice);
    const statsValue = Number(order?.statsValue);
    if (total > 0 && Number.isFinite(statsValue)) return statsValue / total;
    return null;
  });

  function formatMoney(value, symbol) {
    const formatted = (Number(value) || 0).toLocaleString('tr-TR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
    return symbol ? `${formatted} ${symbol}` : formatted;
  }

  function formatRate(value) {
    if (value == null) return null;
    return Number(value).toLocaleString('tr-TR', { maximumFractionDigits: 6 });
  }

  async function fetchOrder() {
    if (!orderId) return;

    loading = true;
    loadError = false;
    order = null;

    try {
      const res = await ApiUtil.get({ path: `/api/panel/market/orders/${orderId}` });

      if (!res || res.error) throw new Error(res?.error || 'NETWORK_ERROR');

      order = res.order || res;
      rateInput = order.exchangeRate != null ? String(order.exchangeRate) : '';
    } catch (e) {
      console.error('[Market] Failed to load order detail', e);
      loadError = true;
      showErrorToast($_('modals.order-detail.toast-load-error'));
    } finally {
      loading = false;
    }
  }

  function closeModal() {
    const el = document.getElementById('orderDetailModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).hide();
    }
  }

  // Fetch on every open: the modal is mounted once and reused for each row.
  $effect(() => {
    const el = document.getElementById('orderDetailModal');
    if (!el) return;
    const handler = () => fetchOrder();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  async function saveRate() {
    const val = parseFloat(String(rateInput).replace(',', '.'));
    if (!Number.isFinite(val) || val <= 0) {
      showErrorToast($_('modals.order-detail.toast-invalid-rate'));
      return;
    }

    saving = true;
    try {
      const res = await ApiUtil.put({
        path: `/api/panel/market/orders/${orderId}/exchange-rate`,
        body: { exchangeRate: val }
      });

      if (res && res.error) throw new Error(res.error);

      showSuccessToast($_('modals.order-detail.toast-rate-updated'));
      // Close the modal first: onUpdated() navigates with invalidateAll, which
      // remounts the whole plugin page (and would destroy this modal, leaving an
      // orphan backdrop). Closing here avoids that and drops the now-pointless
      // in-place refetch.
      closeModal();
      onUpdated();
    } catch (e) {
      console.error('[Market] Failed to update order exchange rate', e);
      showErrorToast($_('modals.order-detail.toast-rate-error'));
    } finally {
      saving = false;
    }
  }

  async function refreshRate() {
    refreshing = true;
    try {
      const res = await ApiUtil.post({
        path: `/api/panel/market/orders/${orderId}/exchange-rate/refresh`
      });

      if (res && res.error) {
        if (res.error === 'EXCHANGE_RATE_FETCH_FAILED') {
          showErrorToast($_('modals.order-detail.toast-fetch-failed'));
          return;
        }
        throw new Error(res.error);
      }

      showSuccessToast($_('modals.order-detail.toast-rate-refreshed'));
      // Close before navigating (onUpdated -> invalidateAll remount) so the modal
      // isn't torn out mid-render and no orphan backdrop is left behind.
      closeModal();
      onUpdated();
    } catch (e) {
      console.error('[Market] Failed to refresh order exchange rate', e);
      showErrorToast($_('modals.order-detail.toast-rate-error'));
    } finally {
      refreshing = false;
    }
  }
</script>

<div class="modal fade" id="orderDetailModal" tabindex="-1" aria-labelledby="orderDetailModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="orderDetailModalLabel">
          {$_('modals.order-detail.title')}{#if order}<span class="font-monospace text-body-secondary ms-2">#{order.id}</span>{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body pb-3">
        {#if loading}
          <div class="text-center text-body-secondary py-5">
            <span class="spinner-border" role="status" aria-hidden="true"></span>
          </div>
        {:else if loadError || !order}
          <div class="text-center text-body-secondary py-5">
            <i class="fas fa-triangle-exclamation mb-2 fs-3"></i>
            <div>{$_('modals.order-detail.load-error')}</div>
          </div>
        {:else}
          <!-- Order summary -->
          <dl class="row mb-0">
            <dt class="col-4 text-body-secondary fw-normal">{$_('pages.orders.table.player')}</dt>
            <dd class="col-8">
              {#if order.playerUsername}
                <div class="d-flex align-items-center">
                  <img src="https://minotar.net/avatar/{order.playerUsername}/24" class="rounded-circle me-2" style="width: 24px; height: 24px;" alt={order.playerUsername} />
                  <span>{order.playerUsername}</span>
                </div>
              {:else}
                <span class="text-body-secondary">-</span>
              {/if}
            </dd>

            <dt class="col-4 text-body-secondary fw-normal">{$_('modals.order-detail.items')}</dt>
            <dd class="col-8">
              <div class="d-flex flex-wrap gap-1">
                {#each order.items || [] as item, i (item.id ?? i)}
                  <span class="badge text-bg-primary">
                    {item.productName}{#if item.quantity && item.quantity > 1}<span class="ms-1 opacity-75">×{item.quantity}</span>{/if}
                  </span>
                {:else}
                  <span class="text-body-secondary">-</span>
                {/each}
              </div>
            </dd>

            <dt class="col-4 text-body-secondary fw-normal">{$_('modals.order-detail.total')}</dt>
            <dd class="col-8 fw-semibold">{formatMoney(order.totalPrice, salesSymbol)}</dd>

            <dt class="col-4 text-body-secondary fw-normal">{$_('common.status')}</dt>
            <dd class="col-8">
              {#if ORDER_STATUS[order.status]}
                <span class="badge {ORDER_STATUS[order.status].cls}">{$_(ORDER_STATUS[order.status].key)}</span>
              {:else}
                <span class="badge text-bg-secondary">{order.status}</span>
              {/if}
            </dd>

            <dt class="col-4 text-body-secondary fw-normal">{$_('pages.orders.table.payment-method')}</dt>
            <dd class="col-8">{order.paymentLabel || '-'}</dd>

            <dt class="col-4 text-body-secondary fw-normal">{$_('pages.orders.table.date')}</dt>
            <dd class="col-8"><DateComponent time={order.createdAt} relativeFormat={true} /></dd>
          </dl>

          <hr />

          <!-- Stats-currency equivalent -->
          <h6 class="mb-3">{$_('modals.order-detail.stats-section')}</h6>

          <dl class="row mb-3">
            <dt class="col-4 text-body-secondary fw-normal">{$_('modals.order-detail.exchange-rate')}</dt>
            <dd class="col-8">
              {#if order.exchangeRate != null}
                {formatRate(order.exchangeRate)}
              {:else}
                <span class="badge text-bg-secondary">{$_('modals.order-detail.exchange-rate-auto')}</span>
              {/if}
            </dd>

            <dt class="col-4 text-body-secondary fw-normal">{$_('modals.order-detail.stats-value')}</dt>
            <dd class="col-8 fw-semibold">{formatMoney(order.statsValue, statsSymbol)}</dd>
          </dl>

          {#if effectiveRate != null}
            <div class="alert alert-secondary py-2 px-3 mb-3 small">
              {$_('modals.order-detail.rate-line', { values: { salesSymbol, rate: formatRate(effectiveRate), statsSymbol } })}
            </div>
          {/if}

          <!-- Manual correction -->
          <label for="orderExchangeRateInput" class="form-label small text-body-secondary mb-1">{$_('modals.order-detail.manual-rate-label')}</label>
          <div class="input-group mb-2">
            <input
              id="orderExchangeRateInput"
              type="number"
              step="any"
              min="0"
              class="form-control"
              placeholder={$_('modals.order-detail.rate-input-placeholder')}
              bind:value={rateInput} />
            <button
              type="button"
              class="btn btn-primary d-flex align-items-center gap-2"
              disabled={saving || refreshing}
              onclick={saveRate}>
              {#if saving}
                <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
              {/if}
              {$_('common.save')}
            </button>
          </div>

          <button
            type="button"
            class="btn btn-outline-secondary btn-sm d-flex align-items-center gap-2"
            disabled={saving || refreshing}
            onclick={refreshRate}>
            {#if refreshing}
              <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
            {:else}
              <i class="fas fa-arrows-rotate"></i>
            {/if}
            {$_('modals.order-detail.fetch-rate')}
          </button>
        {/if}
      </div>
    </div>
  </div>
</div>
