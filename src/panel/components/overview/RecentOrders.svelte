{#if orders.length === 0}
  <NoContent text="" />
{:else}
  <div class="table-responsive">
    <table class="table table-hover align-middle mb-0">
      <thead>
        <tr>
          <th class="text-nowrap" scope="col">{$_('pages.overview.table.order')}</th>
          <th class="text-nowrap" scope="col">{$_('pages.overview.table.player')}</th>
          <th class="text-nowrap" scope="col">{$_('pages.overview.table.products')}</th>
          <th class="text-nowrap" scope="col">{$_('pages.overview.table.total')}</th>
          <th class="text-nowrap" scope="col">{$_('pages.overview.table.payment')}</th>
          <th class="text-nowrap" scope="col">{$_('common.status')}</th>
          <th class="text-nowrap" scope="col">{$_('pages.overview.table.date')}</th>
        </tr>
      </thead>
      <tbody>
        {#each orders as order (order.id)}
          <tr
            class="row-action"
            use:rowLink={`${base}/market/orders/detail/${order.id}`}
            title={$_('pages.orders.actions.view')}>
            <td class="align-middle text-nowrap">
              <a
                class="rounded focus-ring text-decoration-none"
                href="{base}/market/orders/detail/{order.id}">
                #{order.id}
              </a>
              {#if order.testMode}
                <span class="badge text-bg-warning ms-1">{$_('common.test')}</span>
              {/if}
            </td>
            <td class="align-middle text-nowrap">
              {#if order.playerUsername}
                <PlayerCell username={order.playerUsername} />
              {:else}
                <span class="text-body-secondary">—</span>
              {/if}
            </td>
            <td class="align-middle text-nowrap"
              ><div class="text-truncate" style="max-width: 240px;" title={productsText(order)}>
                {productsText(order)}
              </div></td>
            <td class="align-middle text-nowrap">{fmt.money(order.totalPrice, order.currency)}</td>
            <td class="align-middle text-nowrap"
              ><div
                class="text-truncate"
                style="max-width: 200px;"
                title={order.paymentLabel || '—'}>
                {order.paymentLabel || '—'}
              </div></td>
            <td class="align-middle text-nowrap"
              ><StatusBadge kind="order" value={order.status} /></td>
            <td class="align-middle text-nowrap">
              <DateComponent time={order.paidAt ?? order.createdAt} />
            </td>
          </tr>
        {/each}
      </tbody>
    </table>
  </div>
{/if}

<script>
  import { NoContent, Date as DateComponent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { fmt } from '../../utils/locale.js';
  import { rowLink } from '../../utils/row-link.js';
  import PlayerCell from '../PlayerCell.svelte';
  import StatusBadge from '../StatusBadge.svelte';

  // orders: GET /orders rows (own currency per row, amounts are not converted).
  let { orders = [] } = $props();

  function productsText(order) {
    const items = order.items ?? [];
    if (items.length === 0) return '—';
    const first = items[0].variantName
      ? `${items[0].productName} (${items[0].variantName})`
      : items[0].productName;
    return items.length > 1 ? `${first} +${items.length - 1}` : first;
  }
</script>
