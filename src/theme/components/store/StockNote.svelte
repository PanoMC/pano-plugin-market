{#if product.stock != null && product.stock > 0}
  <div class="small text-warning-emphasis">
    {$_('theme.store.stock-left', { values: { count: product.stock } })}
  </div>
{/if}

<script>
  import { _ } from '../../../i18n.js';

  let { product } = $props();
</script>
