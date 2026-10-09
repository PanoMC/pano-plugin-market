{#if badge}
  <span class="market-sale-badge market-sale-badge__badge badge text-bg-danger">
    <span class="visually-hidden">{$_('theme.store.on-sale')}</span>
    {#if badge.kind === 'percent'}-{badge.percent}%{:else}-{formatMoney(badge.amount, currency, {
        removeCents: settings.removeCents,
      })}{/if}
  </span>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { saleBadge } from '../../lib/sale.js';

  const market = plugin('market');
  const _ = market._;
  const clock = market.require('clock');
  const { formatMoney } = market.require('format').actions;

  let { product, settings = {} } = $props();

  const badge = $derived(saleBadge(product, settings, clock.state.now));
  const currency = $derived(product.currency || settings.displayCurrency || settings.currency);
</script>
