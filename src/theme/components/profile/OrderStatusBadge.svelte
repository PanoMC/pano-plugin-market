<span
  class={[
    'market-order-status-badge',
    'market-order-status-badge__badge',
    'badge',
    badgeClass(badge.className),
  ]}>{badge.key ? $_(badge.key) : badge.raw}</span>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { orderBadge } from '../../lib/profileModel.js';
  import { badgeClass } from '../../lib/classes.js';

  const { _ } = plugin('market');

  /** status: order status; an unknown value is shown raw on a secondary badge. */
  let { status } = $props();

  const badge = $derived(orderBadge(status));
</script>
