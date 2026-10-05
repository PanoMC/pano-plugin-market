{#if visible}
  <div class="small text-danger-emphasis">
    <i class="fa-regular fa-clock me-1" aria-hidden="true"></i>
    <span class="visually-hidden">{$_('theme.store.sale-ends-in')}</span>
    <span>{format(remaining(product.sale.endsAt, $now))}</span>
  </div>
{/if}

<script>
  import { _ } from '../../../i18n.js';
  import { format, remaining } from '../../lib/countdown.js';
  import { countdownVisible } from '../../lib/sale.js';
  import { now } from '../../stores/clock.js';

  let { product, settings = {} } = $props();

  // time-dependent text only once the clock runs, so the server render and the first client render match
  const visible = $derived(countdownVisible(product, settings, $now));
</script>
