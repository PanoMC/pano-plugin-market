<div class="card">
  <div class="card-body vstack gap-3">
    <div>{$_('pages.order-detail.cards.customer')}</div>
    <dl class="row mb-0">
      <dt class="col-4 text-body-secondary fw-normal">
        {$_('pages.order-detail.customer.player')}
      </dt>
      <dd class="col-8">
        {#if order?.playerUsername}
          <PlayerCell username={order.playerUsername} />
        {:else}
          <span class="text-body-secondary">—</span>
        {/if}
      </dd>

      {#if order?.isGift}
        <dt class="col-4 text-body-secondary fw-normal">
          {$_('pages.order-detail.customer.recipient')}
        </dt>
        <dd class="col-8">
          {#if order.recipientUsername}
            <PlayerCell username={order.recipientUsername} />
          {:else}
            <span class="text-body-secondary">—</span>
          {/if}
        </dd>
        {#if order.giftMessage}
          <dt class="col-4 text-body-secondary fw-normal">
            {$_('pages.order-detail.customer.gift-message')}
          </dt>
          <dd class="col-8">{order.giftMessage}</dd>
        {/if}
      {/if}

      <dt class="col-4 text-body-secondary fw-normal">{$_('pages.order-detail.customer.email')}</dt>
      <dd class="col-8 text-break">
        {#if order?.email}
          {order.email}
          <CopyButton text={order.email} />
        {:else}
          <span class="text-body-secondary">—</span>
        {/if}
      </dd>

      <dt class="col-4 text-body-secondary fw-normal">
        {$_('pages.order-detail.customer.source')}
      </dt>
      <dd class="col-8">
        {isKnownOrderSource(order?.source)
          ? $_(`enums.order-source.${order.source}`)
          : (order?.source ?? '—')}
      </dd>

      {#if order?.locale}
        <dt class="col-4 text-body-secondary fw-normal">
          {$_('pages.order-detail.customer.locale')}
        </dt>
        <dd class="col-8">{order.locale}</dd>
      {/if}

      {#if order?.clientIp}
        <dt class="col-4 text-body-secondary fw-normal">{$_('pages.order-detail.customer.ip')}</dt>
        <dd class="col-8">{order.clientIp}</dd>
      {/if}

      {#if order?.legalAcceptedAt}
        <dt class="col-4 text-body-secondary fw-normal">
          {$_('pages.order-detail.customer.legal')}
        </dt>
        <dd class="col-8">
          <DateComponent time={order.legalAcceptedAt} />
          {#if order.legalTextVersion}
            <span class="text-body-secondary">{`v${order.legalTextVersion}`}</span>
          {/if}
        </dd>
      {/if}
    </dl>
  </div>
</div>

<script>
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import CopyButton from '../CopyButton.svelte';
  import PlayerCell from '../PlayerCell.svelte';
  import { isKnownOrderSource } from './model.js';

  let { order = null } = $props();
</script>
