<span class={['badge', badge.className]}>{badge.key ? $_(badge.key) : badge.raw}</span>

<script>
  import { _ } from '../../../i18n.js';
  import { orderBadge } from '../../lib/profileModel.js';

  /** status: order status; an unknown value is shown raw on a secondary badge. */
  let { status } = $props();

  const badge = $derived(orderBadge(status));
</script>
