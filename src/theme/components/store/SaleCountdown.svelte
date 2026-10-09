{#if visible}
  <div class="market-sale-countdown small text-danger-emphasis">
    <i class="fa-regular fa-clock me-1" aria-hidden="true"></i>
    <span class="visually-hidden">{$_('theme.store.sale-ends-in')}</span>
    <span>{format(remaining(product.sale.endsAt, clock.state.now))}</span>
  </div>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { format, remaining } from '../../lib/countdown.js';
  import { countdownVisible } from '../../lib/sale.js';

  const market = plugin('market');
  const _ = market._;
  const clock = market.require('clock');

  let { product, settings = {} } = $props();

  // time-dependent text only once the clock runs, so the server render and the first client render match
  const visible = $derived(countdownVisible(product, settings, clock.state.now));
</script>
