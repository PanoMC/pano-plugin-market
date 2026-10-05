<div class="card" id="market-checkout-summary-card">
  <div class="card-body vstack gap-3">
    <div class="d-flex justify-content-between align-items-baseline gap-2">
      <h2 class="h5 mb-0">{$_('theme.checkout.summary.title')}</h2>
      {#if !topup}
        <button
          type="button"
          class="btn btn-link btn-sm p-0"
          data-bs-toggle="offcanvas"
          data-bs-target="#marketCartOffcanvas"
          aria-controls="marketCartOffcanvas">
          {$_('theme.checkout.edit-cart')}
        </button>
      {/if}
    </div>

    {#if quote}
      <ul class="list-group list-group-flush">
        {#each lines as line (line.lineKey)}
          <li class="list-group-item px-0">
            <div class="d-flex gap-2 align-items-start">
              <div class="flex-shrink-0 bg-body-tertiary rounded overflow-hidden">
                {#if line.imageFileName}
                  <img
                    src="{base}/api/market/products/image/{line.imageFileName}?thumbnail=true"
                    width="40"
                    height="40"
                    alt=""
                    class="object-fit-cover d-block" />
                {:else}
                  <span class="d-flex align-items-center justify-content-center ratio ratio-1x1">
                    <i class="fa-solid fa-box text-body-secondary" aria-hidden="true"></i>
                  </span>
                {/if}
              </div>
              <div class="flex-grow-1 text-break">
                <div class="fw-semibold">{line.name}</div>
                {#if line.variantName}
                  <div class="small text-body-secondary">{line.variantName}</div>
                {/if}
                <div class="small text-body-secondary">&times; {line.quantity}</div>
                {#each lineErrors(line) as error (error.code)}
                  <div class="small text-danger" role="alert">{$_(error.messageKey)}</div>
                {/each}
              </div>
              <div class="flex-shrink-0 fw-semibold">
                {formatMoney(line.lineTotal, quote.currency, { removeCents })}
              </div>
            </div>
          </li>
        {/each}
      </ul>

      {#if !hideCodes}
        <div class="vstack gap-3">
          <CodeInput
            id="market-checkout-coupon"
            label={$_('theme.checkout.coupon')}
            applied={couponCode}
            info={couponState}
            busy={quoting}
            {disabled}
            onapply={(code) => oncode('coupon', code)}
            onremove={() => oncode('coupon', null)} />
          <CodeInput
            id="market-checkout-creator-code"
            label={$_('theme.checkout.creator-code')}
            applied={creatorCode}
            info={creatorState}
            busy={quoting}
            {disabled}
            onapply={(code) => oncode('creator', code)}
            onremove={() => oncode('creator', null)} />
        </div>
      {/if}

      {#each alerts as alert (alert.code + (alert.level ?? ''))}
        <div
          class={['alert', alert.cls, 'mb-0']}
          role={alert.level === 'error' ? 'alert' : 'status'}>
          {$_(alert.messageKey, { values: alertValues(alert) })}
        </div>
      {/each}

      <dl
        class={['row', 'mb-0', 'gy-1', quoting && 'opacity-50']}
        aria-live="polite"
        aria-busy={quoting ? 'true' : 'false'}>
        {#each rows as row (row.id)}
          <dt class={['col-7', 'fw-normal', row.strong && 'fw-bold']}>
            {$_(row.labelKey, { values: row.values ?? {} })}
            {#if row.extra}
              <span class="small text-body-secondary">
                ({formatCredits(row.extra.credits, row.extra.name)})
              </span>
            {/if}
          </dt>
          <dd class={['col-5', 'text-end', 'mb-0', row.strong && 'fw-bold']}>
            {#if row.amount === null || row.amount === undefined}
              &mdash;
            {:else}
              {row.negative ? MINUS : ''}{formatMoney(row.amount, quote.currency, { removeCents })}
            {/if}
          </dd>
        {/each}
      </dl>

      {#if chargedIn}
        <div class="form-text">
          {$_('theme.checkout.charged-in', { values: { currency: chargedIn } })}
        </div>
      {/if}
    {:else}
      <LoadingBlock rows={3} />
    {/if}
  </div>
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import {
    chargedInCurrency,
    lineErrors,
    summaryAlerts,
    summaryLines,
    summaryRows,
  } from '../../lib/summaryModel.js';
  import { formatCredits, formatMoney } from '../../utils/format.js';
  import LoadingBlock from '../common/LoadingBlock.svelte';
  import CodeInput from './CodeInput.svelte';

  /**
   * The quote at a glance: lines, the two code inputs and the totals list. Every amount is the quote's number
   * (the theme never computes a total). `couponCode` / `creatorCode` = the draft's codes, `couponState` /
   * `creatorState` = codeState() of lib/summaryModel.js; oncode(kind, code | null) applies (kind 'coupon' |
   * 'creator') or removes a code. `hideCodes` hides both inputs (top-up mode, EXTERNAL pricing).
   */
  let {
    quote = null,
    quoting = false,
    topup = false,
    removeCents = false,
    hideCodes = false,
    disabled = false,
    couponCode = '',
    creatorCode = '',
    couponState = { status: 'IDLE' },
    creatorState = { status: 'IDLE' },
    oncode = () => {},
  } = $props();

  const MINUS = '−';

  const lines = $derived(summaryLines(quote));
  const rows = $derived(summaryRows(quote));
  const alerts = $derived(summaryAlerts(quote));
  const chargedIn = $derived(chargedInCurrency(quote));

  /** Interpolation values of an alert: the formatted amount of `minimum`, plain numbers otherwise. */
  function alertValues(alert) {
    const values = { ...alert.values };

    if (typeof values.minimum === 'number')
      values.minimum = formatMoney(values.minimum, quote?.currency, { removeCents });

    return values;
  }
</script>
