<div>
  <div class="d-flex flex-wrap align-items-baseline gap-2">
    {#if product.priceFrom}
      <span class="small text-body-secondary">{$_('theme.store.price-starting-at')}</span>
    {/if}
    <span class="fw-bold"
      >{Number(product.price) === 0 ? $_('theme.store.free') : money(product.price)}</span>
    {#if strike !== null}
      <s class="text-body-secondary small">{money(strike)}</s>
    {/if}
    {#if suffix}
      <span class="small text-body-secondary">{suffix}</span>
    {/if}
    {#if settings.creditsEnabled && product.creditPrice != null}
      <span class="badge text-bg-info text-wrap text-start">
        <i class="fa-solid fa-coins me-1" aria-hidden="true"></i>{formatCredits(
          product.creditPrice,
          settings.creditName || $_('theme.store.credits'),
        )}
      </span>
    {/if}
  </div>
  {#if showVat && typeof settings.pricesIncludeVat === 'boolean'}
    <div class="small text-body-secondary">
      {$_(settings.pricesIncludeVat ? 'theme.store.vat-included' : 'theme.store.vat-excluded')}
    </div>
  {/if}
</div>

<script>
  import { _ } from '../../../i18n.js';
  import { strikePrice } from '../../lib/sale.js';
  import { now } from '../../stores/clock.js';
  import { formatCredits, formatMoney, formatPeriod, formatPrice } from '../../utils/format.js';

  /** Shared by the card, the product page and the cart. showVat: the VAT note (product page only). */
  let { product, settings = {}, showVat = false } = $props();

  const currency = $derived(product.currency || settings.displayCurrency || settings.currency);
  const strike = $derived(strikePrice(product, settings, $now));

  function money(amount) {
    return currency
      ? formatMoney(amount, currency, { removeCents: settings.removeCents })
      : formatPrice(amount, settings);
  }

  const suffix = $derived.by(() => {
    if (!product.period) return '';

    const period = formatPeriod(product.period.unit, product.period.count, $_);

    if (product.billingMode === 'SUBSCRIPTION')
      return $_('theme.store.per-period', { values: { period } });
    if (product.billingMode === 'TIMED')
      return $_('theme.store.for-period', { values: { period } });

    return '';
  });
</script>
