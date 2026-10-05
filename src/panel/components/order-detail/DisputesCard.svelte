{#if disputes.length > 0}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.order-detail.cards.disputes', { values: { count: disputes.length } })}
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
              {$_('pages.order-detail.table.opened')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.resolved')}
            </th>
          </tr>
        </thead>
        <tbody>
          {#each disputes as dispute (dispute.id)}
            <tr>
              <th scope="row" class="align-middle">
                <ControlDropdown items={menu(dispute)} />
              </th>
              <td class="align-middle"><StatusBadge kind="dispute" value={dispute.status} /></td>
              <td class="align-middle text-nowrap">
                {fmt.money(dispute.amount, dispute.currency ?? currency)}
              </td>
              <td class="align-middle">
                {#if dispute.reason}{dispute.reason}{:else}<span class="text-body-secondary">—</span
                  >{/if}
              </td>
              <td class="align-middle">
                {#if dispute.origin && isKnownDisputeOrigin(dispute.origin)}
                  {$_(`enums.dispute-origin.${dispute.origin}`)}
                {:else}
                  {dispute.origin ?? '—'}
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if dispute.openedAt}<DateComponent time={dispute.openedAt} />{:else}<span
                    class="text-body-secondary">—</span
                  >{/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if dispute.resolvedAt}<DateComponent time={dispute.resolvedAt} />{:else}<span
                    class="text-body-secondary">—</span
                  >{/if}
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
  import StatusBadge from '../StatusBadge.svelte';
  import { disputeActions } from './actions.js';
  import ControlDropdown from './ControlDropdown.svelte';
  import { isKnownDisputeOrigin } from './model.js';

  // onResolve(dispute, status) asks for a confirmation; status = WON | LOST | CLOSED.
  let { detail, user = null, onResolve = () => {} } = $props();

  const disputes = $derived(detail?.disputes ?? []);
  const currency = $derived(detail?.order?.currency);

  function menu(dispute) {
    return disputeActions(dispute, user).map((status) => ({
      key: status,
      label: $_(`pages.order-detail.actions.dispute-${status}`),
      icon: status === 'WON' ? 'fa-trophy' : status === 'LOST' ? 'fa-thumbs-down' : 'fa-xmark',
      danger: status === 'LOST',
      onclick: () => onResolve(dispute, status),
    }));
  }
</script>
