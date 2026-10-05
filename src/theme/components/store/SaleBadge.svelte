{#if badge}
  <span class="badge text-bg-danger">
    <span class="visually-hidden">{$_('theme.store.on-sale')}</span>
    {#if badge.kind === 'percent'}-{badge.percent}%{:else}-{formatMoney(badge.amount, currency, {
        removeCents: settings.removeCents,
      })}{/if}
  </span>
{/if}

<script>
  import { _ } from '../../../i18n.js';
  import { saleBadge } from '../../lib/sale.js';
  import { now } from '../../stores/clock.js';
  import { formatMoney } from '../../utils/format.js';

  let { product, settings = {} } = $props();

  const badge = $derived(saleBadge(product, settings, $now));
  const currency = $derived(product.currency || settings.displayCurrency || settings.currency);
</script>
