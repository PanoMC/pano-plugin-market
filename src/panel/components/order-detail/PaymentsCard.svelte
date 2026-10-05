<div class="card">
  <CardHeader>
    <div slot="left">
      {$_('pages.order-detail.cards.payments', { values: { count: payments.length } })}
    </div>
  </CardHeader>

  {#if payments.length === 0}
    <NoContent icon="" />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover">
        <thead>
          <tr>
            <th class="align-middle text-nowrap" scope="col"></th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.method')}
            </th>
            <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.amount')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.transaction')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.message')}
            </th>
            <th class="align-middle text-nowrap" scope="col"
              >{$_('pages.order-detail.table.date')}</th>
          </tr>
        </thead>
        <tbody>
          {#each payments as payment (payment.id)}
            {@const message = paymentMessage(payment)}
            <tr>
              <th scope="row" class="align-middle">
                <ControlDropdown items={menu(payment)} />
              </th>
              <td class="align-middle">{payment.providerId}</td>
              <td class="align-middle">
                <StatusBadge kind="payment" value={payment.status} />
                {#if payment.duplicate}
                  <span class="badge text-bg-warning ms-1">
                    {$_('pages.order-detail.duplicate')}
                  </span>
                {/if}
                {#if payment.testMode}
                  <span class="badge text-bg-warning ms-1">{$_('common.test')}</span>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {fmt.money(payment.amount, currency)}
                {#if differs(payment)}
                  <div class="text-body-secondary">{fmt.money(payment.paidAmount, currency)}</div>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if payment.gatewayTransactionId}
                  {payment.gatewayTransactionId}
                  <CopyButton text={payment.gatewayTransactionId} />
                {:else}
                  <span class="text-body-secondary">—</span>
                {/if}
              </td>
              <td class="align-middle">
                {#if message}{message}{:else}<span class="text-body-secondary">—</span>{/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if paymentDate(payment)}
                  <DateComponent time={paymentDate(payment)} />
                {:else}
                  <span class="text-body-secondary">—</span>
                {/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</div>

<script>
  import { CardHeader, Date as DateComponent, NoContent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import { fmt } from '../../utils/locale.js';
  import CopyButton from '../CopyButton.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { paymentActions } from './actions.js';
  import ControlDropdown from './ControlDropdown.svelte';
  import { paymentDate, paymentMessage } from './model.js';

  // detail: GET /orders/:id; onEvents(payment) opens the events modal; onQuery(payment) runs the status query.
  let { detail, user = null, onEvents = () => {}, onQuery = () => {} } = $props();

  const payments = $derived(detail?.payments ?? []);
  const currency = $derived(detail?.order?.currency);

  const differs = (p) =>
    p.paidAmount !== null &&
    p.paidAmount !== undefined &&
    Number(p.paidAmount) > 0 &&
    Math.abs(Number(p.paidAmount) - Number(p.amount)) >= 0.005;

  function menu(payment) {
    return paymentActions(payment, user).map((id) =>
      id === 'events'
        ? {
            key: id,
            label: $_('pages.order-detail.actions.events'),
            icon: 'fa-list',
            onclick: () => onEvents(payment),
          }
        : {
            key: id,
            label: $_('pages.order-detail.actions.check-status'),
            icon: 'fa-magnifying-glass',
            onclick: () => onQuery(payment),
          },
    );
  }
</script>
