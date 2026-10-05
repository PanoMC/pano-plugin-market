{#if subscription}
  <div class="card">
    <div class="card-body vstack gap-3">
      <div>{$_('pages.order-detail.cards.subscription')}</div>
      <dl class="row mb-0">
        <dt class="col-5 text-body-secondary fw-normal">{$_('common.status')}</dt>
        <dd class="col-7"><StatusBadge kind="subscription" value={subscription.status} /></dd>
        {#if subscription.currentPeriodEnd}
          <dt class="col-5 text-body-secondary fw-normal">
            {$_('pages.order-detail.subscription.period-end')}
          </dt>
          <dd class="col-7"><DateComponent time={subscription.currentPeriodEnd} /></dd>
        {/if}
      </dl>
      <div>
        <a class="btn btn-link px-0" href="{base}/market/subscriptions/detail/{subscription.id}">
          {$_('pages.order-detail.subscription.open')}
        </a>
      </div>
    </div>
  </div>
{/if}

<script>
  import { Date as DateComponent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import StatusBadge from '../StatusBadge.svelte';

  let { subscription = null } = $props();
</script>
