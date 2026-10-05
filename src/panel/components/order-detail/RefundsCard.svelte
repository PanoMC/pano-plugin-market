{#if refunds.length > 0}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.order-detail.cards.refunds', { values: { count: refunds.length } })}
      </div>
    </CardHeader>
    <div class="table-responsive">
      <table class="table table-hover">
        <thead>
          <tr>
            <th class="align-middle text-nowrap" scope="col"></th>
            <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.amount')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.reason')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.origin')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.buyer-link')}
            </th>
            <th class="align-middle text-nowrap" scope="col"
              >{$_('pages.order-detail.table.date')}</th>
          </tr>
        </thead>
        <tbody>
          {#each refunds as refund (refund.id)}
            <tr>
              <th scope="row" class="align-middle">
                <ControlDropdown items={menu(refund)} />
              </th>
              <td class="align-middle">
                <StatusBadge kind="refund" value={refund.status} />
                {#if refund.failureMessage}
                  <div class="text-danger">{refund.failureMessage}</div>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {fmt.money(refund.amount, refund.currency ?? currency)}
                {#if split(refund)}
                  <div class="text-body-secondary">
                    {fmt.money(refund.gatewayAmount, refund.currency ?? currency)}
                    ·
                    {fmt.credits(refund.creditAmount, ctx?.creditName)}
                  </div>
                {/if}
              </td>
              <td class="align-middle">
                {#if refund.reason}{refund.reason}{:else}<span class="text-body-secondary">—</span
                  >{/if}
              </td>
              <td class="align-middle">
                {#if refund.origin && isKnownRefundOrigin(refund.origin)}
                  {$_(`enums.refund-origin.${refund.origin}`)}
                {:else}
                  {refund.origin ?? '—'}
                {/if}
                {#if refund.initiatedByUsername}
                  <div class="text-body-secondary">{refund.initiatedByUsername}</div>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if refund.buyerActionUrl}
                  <CopyButton
                    text={refund.buyerActionUrl}
                    label={$_('pages.order-detail.copy-link')} />
                {:else}
                  <span class="text-body-secondary">—</span>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                <DateComponent time={refund.completedAt ?? refund.createdAt} />
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  </div>
{/if}

<script>
  import { CardHeader, Date as DateComponent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import { fmt } from '../../utils/locale.js';
  import CopyButton from '../CopyButton.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { refundActions } from './actions.js';
  import ControlDropdown from './ControlDropdown.svelte';
  import { isKnownRefundOrigin, refundHasSplit } from './model.js';

  // onRetry(refund) / onCancel(refund): the page asks for a confirmation and runs the request.
  let { detail, ctx = null, user = null, onRetry = () => {}, onCancel = () => {} } = $props();

  const refunds = $derived(detail?.refunds ?? []);
  const currency = $derived(detail?.order?.currency);
  const split = refundHasSplit;

  function menu(refund) {
    return refundActions(refund, user).map((id) =>
      id === 'retry'
        ? {
            key: id,
            label: $_('pages.order-detail.actions.retry'),
            icon: 'fa-rotate-right',
            onclick: () => onRetry(refund),
          }
        : {
            key: id,
            label: $_('pages.order-detail.actions.cancel-row'),
            icon: 'fa-ban',
            danger: true,
            onclick: () => onCancel(refund),
          },
    );
  }
</script>
