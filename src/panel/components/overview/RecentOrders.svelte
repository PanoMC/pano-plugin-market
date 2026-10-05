{#if orders.length === 0}
  <NoContent icon="" />
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
          <tr>
            <td class="align-middle text-nowrap">
              <a class="text-decoration-none" href="{base}/market/orders/detail/{order.id}">
                #{order.id}
              </a>
              {#if order.testMode}
                <span class="badge text-bg-warning ms-1">{$_('common.test')}</span>
              {/if}
            </td>
            <td class="align-middle">
              {#if order.playerUsername}
                <PlayerCell username={order.playerUsername} />
              {:else}
                <span class="text-body-secondary">—</span>
              {/if}
            </td>
            <td class="align-middle">{productsText(order)}</td>
            <td class="align-middle text-nowrap">{fmt.money(order.totalPrice, order.currency)}</td>
            <td class="align-middle">{order.paymentLabel || '—'}</td>
            <td class="align-middle"><StatusBadge kind="order" value={order.status} /></td>
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
