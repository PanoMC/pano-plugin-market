<div class="card">
  <div class="card-body vstack gap-3">
    <div class="d-flex align-items-center gap-2">
      <span>{$_('pages.order-detail.cards.totals')}</span>
      {#if external}
        <span
          class="badge text-bg-info"
          use:tooltip={[$_('pages.order-detail.external-pricing-tip')]}>
          {$_('pages.order-detail.external-pricing')}
        </span>
      {/if}
    </div>

    <dl class="row mb-0">
      {#each rows as row (row.key)}
        <dt class="col-7 text-body-secondary fw-normal" class:text-body={row.strong}>
          {$_(`pages.order-detail.totals.${row.key}`)}
          {#if row.key === 'vat' && row.included}
            <span class="text-body-secondary"
              >({$_('pages.order-detail.totals.vat-included')})</span>
          {/if}
          {#if row.note}
            <span class="text-body-secondary">({row.note})</span>
          {/if}
        </dt>
        <dd class="col-5 text-end mb-1" class:fw-semibold={row.strong}>
          {#if row.kind === 'credits'}
            {fmt.credits(row.amount, ctx?.creditName)}
            <div class="text-body-secondary">{fmt.money(row.value, currency)}</div>
          {:else if row.kind === 'text'}
            {row.currency}
            {#if row.rate !== null}
              <div class="text-body-secondary">{row.rate}</div>
            {/if}
          {:else}
            {row.negative ? '−' : ''}{fmt.money(row.amount, currency)}
            {#if row.key === 'refunded'}
              {#if row.gateway > 0}
                <div class="text-body-secondary">
                  {$_('pages.order-detail.totals.refunded-gateway')}
                  {fmt.money(row.gateway, currency)}
                </div>
              {/if}
              {#if row.credits > 0}
                <div class="text-body-secondary">
                  {$_('pages.order-detail.totals.refunded-credits')}
                  {fmt.credits(row.credits, ctx?.creditName)}
                </div>
              {/if}
            {/if}
          {/if}
        </dd>
      {/each}
    </dl>

    {#if canEdit}
      <form class="vstack gap-2" onsubmit={save}>
        <div class="text-body-secondary">{$_('pages.order-detail.exchange-rate.title')}</div>
        <div class="input-group">
          <input
            class="form-control"
            class:is-invalid={rateInvalid}
            type="text"
            inputmode="decimal"
            autocomplete="off"
            placeholder={$_('pages.order-detail.exchange-rate.placeholder')}
            oninput={() => (rateInvalid = false)}
            bind:value={rateText} />
          <button class="btn btn-primary" type="submit" disabled={busy}>
            {$_('common.save')}
          </button>
          <button
            class="btn btn-outline-secondary"
            type="button"
            disabled={busy}
            aria-label={$_('pages.order-detail.exchange-rate.refresh')}
            use:tooltip={[$_('pages.order-detail.exchange-rate.refresh')]}
            onclick={refreshRate}>
            <i class="fa-solid fa-arrows-rotate" aria-hidden="true"></i>
          </button>
        </div>
        {#if order?.statsValue !== undefined && order?.statsValue !== null}
          <div class="small text-body-secondary">
            {$_('pages.order-detail.exchange-rate.stats-value')}
            {fmt.money(order.statsValue, ctx?.statsCurrency)}
          </div>
        {/if}
      </form>
    {/if}
  </div>
</div>

<script>
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ } from '../../../i18n';
  import { fmt } from '../../utils/locale.js';
  import { canEditExchangeRate } from './actions.js';
  import { isExternalPricing, parseExchangeRate, totalsRows } from './model.js';
  import { exchangeRateRefreshRequest, exchangeRateRequest } from './requests.js';

  // order: detail.order; ctx: GET /context (may be null); onMutate(request, toastKey) => call() result.
  let { order, ctx = null, user = null, onMutate = async () => ({ ok: false }) } = $props();

  let rateInvalid = $state(false);
  let busy = $state(false);

  const rows = $derived(totalsRows(order));
  const currency = $derived(order?.currency);
  const external = $derived(isExternalPricing(order));
  const canEdit = $derived(canEditExchangeRate(user) && order?.id !== undefined);

  const loadedRate = $derived(
    order?.exchangeRate === null || order?.exchangeRate === undefined
      ? ''
      : String(order.exchangeRate),
  );
  // The input follows the loaded rate (a derived value that typing overrides).
  let rateText = $derived(loadedRate);

  async function save(event) {
    event.preventDefault();
    if (busy) return;
    const rate = parseExchangeRate(rateText);
    rateInvalid = rate === null;
    if (rate === null) return;
    busy = true;
    try {
      await onMutate(exchangeRateRequest(order.id, rate), 'pages.order-detail.toast.rate-saved');
    } finally {
      busy = false;
    }
  }

  async function refreshRate() {
    if (busy) return;
    busy = true;
    try {
      await onMutate(
        exchangeRateRefreshRequest(order.id),
        'pages.order-detail.toast.rate-refreshed',
      );
    } finally {
      busy = false;
    }
  }
</script>
