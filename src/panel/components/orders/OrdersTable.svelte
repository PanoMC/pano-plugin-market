<div class="table-responsive">
  <table class="table table-hover">
    <thead>
      <tr>
        <th class="align-middle text-nowrap" scope="col"></th>
        <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.order')}</th>
        <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.player')}</th>
        <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.products')}</th>
        <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.total')}</th>
        <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.payment')}</th>
        <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
        <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.delivery')}</th>
        {#if shippingEnabled}
          <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.shipping')}</th>
        {/if}
        <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.date')}</th>
      </tr>
    </thead>
    <tbody>
      {#each orders as order (order.id)}
        {@const actions = rowActions(order, user)}
        {@const products = productsCell(order)}
        {@const refunded = refundedLine(order)}
        <tr>
          <th scope="row" class="align-middle">
            <div class="dropdown position-static">
              <button
                type="button"
                class="btn btn-link"
                data-bs-toggle="dropdown"
                title={$_('common.actions')}
                aria-label={$_('common.actions')}>
                <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
              </button>
              <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                {#if actions.includes('view')}
                  <a class="dropdown-item" href="{base}/market/orders/detail/{order.id}">
                    <i class="fa-solid fa-eye me-2" aria-hidden="true"></i>
                    {$_('pages.orders.actions.view')}
                  </a>
                {/if}
                {#if actions.includes('copy')}
                  <button type="button" class="dropdown-item" onclick={() => copyId(order)}>
                    <i class="fa-solid fa-copy me-2" aria-hidden="true"></i>
                    {$_('pages.orders.actions.copy-id')}
                  </button>
                {/if}
                {#if actions.includes('rerun')}
                  <a class="dropdown-item" href="{base}/market/orders/detail/{order.id}#deliveries">
                    <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>
                    {$_('pages.orders.actions.rerun')}
                  </a>
                {/if}
              </div>
            </div>
          </th>
          <td class="align-middle text-nowrap">
            <a class="text-decoration-none" href="{base}/market/orders/detail/{order.id}">
              #{order.id}
            </a>
            {#if order.testMode}
              <span class="badge text-bg-warning ms-1">{$_('common.test')}</span>
            {/if}
            {#if order.isGift}
              <i
                class="fa-solid fa-gift ms-1 text-body-secondary"
                role="img"
                aria-label={$_('pages.orders.gift')}
                use:tooltip={[$_('pages.orders.gift')]}></i>
            {/if}
          </td>
          <td class="align-middle">
            {#if order.playerUsername}
              <PlayerCell username={order.playerUsername} />
            {:else}
              <span class="text-body-secondary">—</span>
            {/if}
            {#if order.isGift && order.recipientUsername}
              <div class="text-body-secondary">→ {order.recipientUsername}</div>
            {/if}
          </td>
          <td class="align-middle">
            {#if products.first}
              {#if products.more > 0}
                <span use:tooltip={[products.tooltip]}>
                  {products.first}
                  <span class="text-body-secondary">+{products.more}</span>
                </span>
              {:else}
                <span>{products.first}</span>
              {/if}
            {:else}
              <span class="text-body-secondary">—</span>
            {/if}
          </td>
          <td class="align-middle text-nowrap">
            {fmt.money(order.totalPrice, order.currency)}
            {#if refunded !== null}
              <div class="text-body-secondary">−{fmt.money(refunded, order.currency)}</div>
            {/if}
          </td>
          <td class="align-middle">{order.paymentLabel || '—'}</td>
          <td class="align-middle">
            {#if order.status === 'REVIEW' && order.reviewReason}
              <span use:tooltip={[$_(`enums.review-reason.${order.reviewReason}`)]}>
                <StatusBadge kind="order" value={order.status} />
              </span>
            {:else}
              <StatusBadge kind="order" value={order.status} />
            {/if}
          </td>
          <td class="align-middle">
            {#if hiddenStatus('fulfillment', order.fulfillmentStatus)}
              <span class="text-body-secondary">—</span>
            {:else}
              <StatusBadge kind="fulfillment" value={order.fulfillmentStatus} />
            {/if}
          </td>
          {#if shippingEnabled}
            <td class="align-middle">
              {#if hiddenStatus('shipping', order.shippingStatus)}
                <span class="text-body-secondary">—</span>
              {:else}
                <StatusBadge kind="shipping" value={order.shippingStatus} />
              {/if}
            </td>
          {/if}
          <td class="align-middle text-nowrap">
            <DateComponent time={order.paidAt ?? order.createdAt} />
          </td>
        </tr>
      {/each}
    </tbody>
  </table>
</div>

<script>
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { copy } from '@panomc/sdk/utils/text';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _, showErrorToast, showSuccessToast } from '../../../i18n';
  import { fmt } from '../../utils/locale.js';
  import PlayerCell from '../PlayerCell.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { hiddenStatus, productsCell, refundedLine, rowActions } from './filters.js';

  // orders: GET /orders rows; user: the layout user; shippingEnabled: ctx.shippingEnabled.
  let { orders = [], user = null, shippingEnabled = false } = $props();

  async function copyId(order) {
    try {
      const ok = await copy(String(order.publicId ?? order.id));
      if (ok === false) throw new Error('copy failed');
    } catch {
      showErrorToast($_('common.copy-failed'));
      return;
    }
    showSuccessToast($_('common.copied'));
  }
</script>
