<ul class="list-group">
  {#each items as item, index (item.id ?? index)}
    <li class="list-group-item">
      <div class="d-flex gap-3 align-items-start">
        <div class="flex-shrink-0 bg-body-tertiary rounded overflow-hidden">
          {#if item.imageFileName}
            <img
              src="{base}/api/market/products/image/{encodeURIComponent(
                item.imageFileName,
              )}?thumbnail=true"
              width="48"
              height="48"
              alt=""
              class="object-fit-cover d-block" />
          {:else}
            <span class="d-flex align-items-center justify-content-center ratio ratio-1x1">
              <i class="fa-solid fa-box text-body-secondary" aria-hidden="true"></i>
            </span>
          {/if}
        </div>

        <div class="flex-grow-1 text-break">
          <div class="fw-semibold">{item.name}</div>
          {#if item.variantName}
            <div class="small text-body-secondary">{item.variantName}</div>
          {/if}
          <div class="small text-body-secondary">&times; {item.quantity}</div>

          {#each fieldPairs(item.fieldValues) as pair (pair.label)}
            <div class="small text-body-secondary">{pair.label}: {pair.value}</div>
          {/each}

          {#if item.targetServerName}
            <div class="small text-body-secondary">
              <i class="fa-solid fa-server me-1" aria-hidden="true"></i>{item.targetServerName}
            </div>
          {/if}

          {#if item.expiresAt}
            <div class="small text-body-secondary">
              {$_('theme.order.valid-until', { values: { date: formatDateTime(item.expiresAt) } })}
            </div>
          {/if}

          {#if deliveryBadge(item.delivery)}
            <span class={['badge', 'mt-1', deliveryBadge(item.delivery).cls]}>
              {$_(deliveryBadge(item.delivery).labelKey)}
            </span>
          {/if}
        </div>

        {#if item.lineTotal !== undefined && item.lineTotal !== null}
          <div class="flex-shrink-0 fw-semibold">
            {formatMoney(item.lineTotal, currency, { removeCents })}
          </div>
        {/if}
      </div>
    </li>
  {/each}
</ul>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import { deliveryBadge, fieldPairs } from '../../lib/orderState.js';
  import { formatDateTime, formatMoney } from '../../utils/format.js';

  /** The items of an OrderView (14 §11.4). A limited view carries names, quantities and delivery only. */
  let { items = [], currency = '', removeCents = false } = $props();
</script>
