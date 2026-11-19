{#if order}
  <div class="card">
    <div class="card-body">
      <div class="row row-cols-2 row-cols-md-3 row-cols-xl-auto g-3 justify-content-xl-between">
        <div class="col">
          <div class="text-body-secondary">{$_('pages.order-detail.summary.total')}</div>
          <div class="fs-5 fw-semibold">{fmt.money(order.totalPrice, order.currency)}</div>
        </div>
        <div class="col">
          <div class="text-body-secondary">{$_('pages.order-detail.summary.payment')}</div>
          <div class="hstack gap-2 flex-wrap">
            {#if payment}
              <StatusBadge kind="payment" value={payment.status} />
            {/if}
            {#if order.paymentLabel}
              <span>{order.paymentLabel}</span>
            {:else if !payment}
              <span class="text-body-secondary">—</span>
            {/if}
          </div>
        </div>
        <div class="col">
          <div class="text-body-secondary">{$_('pages.order-detail.summary.fulfillment')}</div>
          {#if order.fulfillmentStatus}
            <StatusBadge kind="fulfillment" value={order.fulfillmentStatus} />
          {:else}
            <span class="text-body-secondary">—</span>
          {/if}
        </div>
        {#if shipping && order.requiresShipping === true}
          <div class="col">
            <div class="text-body-secondary">{$_('pages.order-detail.summary.shipping')}</div>
            <StatusBadge kind="shipping" value={order.shippingStatus} />
          </div>
        {/if}
        <div class="col">
          <div class="text-body-secondary">{$_('pages.order-detail.summary.date')}</div>
          <DateComponent time={order.createdAt} />
        </div>
        <div class="col">
          <div class="text-body-secondary">{$_('pages.order-detail.summary.player')}</div>
          {#if order.playerUsername}
            <PlayerCell username={order.playerUsername} />
          {:else}
            <span class="text-body-secondary">—</span>
          {/if}
        </div>
      </div>
    </div>
  </div>
{/if}

<script>
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import { fmt } from '../../utils/locale.js';
  import PlayerCell from '../PlayerCell.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { latestPayment } from './tabs.js';

  // The answers looked for first (paid? delivered?), above the tabs. Read only: every action stays
  // on its card. shipping = the physical shipping feature is on.
  let { detail = null, shipping = false } = $props();

  const order = $derived(detail?.order ?? null);
  const payment = $derived(latestPayment(detail?.payments));
</script>
