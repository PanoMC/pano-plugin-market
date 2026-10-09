{#if product.stock != null && product.stock > 0}
  <div class="market-stock-note small text-warning-emphasis">
    {$_('theme.store.stock-left', { values: { count: product.stock } })}
  </div>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const _ = market._;

  let { product } = $props();
</script>
