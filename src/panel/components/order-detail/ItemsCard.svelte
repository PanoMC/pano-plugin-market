<div class="card">
  <CardHeader>
    <div slot="left">
      {$_('pages.order-detail.cards.items', { values: { count: items.length } })}
    </div>
  </CardHeader>

  {#if items.length === 0}
    <NoContent icon="" />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover">
        <thead>
          <tr>
            <th class="align-middle text-nowrap" scope="col"></th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.product')}
            </th>
            <th class="align-middle text-nowrap" scope="col"
              >{$_('pages.order-detail.table.qty')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.unit-price')}
            </th>
            <th class="align-middle text-nowrap" scope="col"
              >{$_('pages.order-detail.table.vat')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.total')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.delivery')}
            </th>
          </tr>
        </thead>
        <tbody>
          {#each items as item (item.id)}
            {@const child = isBundleChild(item)}
            {@const state = itemDeliveryState(item.id, deliveries)}
            {@const fields = fieldEntries(item)}
            {@const server = itemTargetServer(item, deliveries)}
            <tr>
              <th scope="row" class="align-middle">
                <ControlDropdown items={menu(item)} />
              </th>
              <td class="align-middle" class:ps-4={child}>
                <div>{item.productName}</div>
                {#if item.variantName}
                  <div class="text-body-secondary">{item.variantName}</div>
                {/if}
                {#if item.sku}
                  <div class="text-body-secondary">{item.sku}</div>
                {/if}
                {#if fields.length > 0}
                  <dl class="row mb-0 small">
                    {#each fields as field (field.key)}
                      <dt class="col-sm-4 text-body-secondary fw-normal">{field.label}</dt>
                      <dd class="col-sm-8 mb-0">{field.value}</dd>
                    {/each}
                  </dl>
                {/if}
                {#if server}
                  <div class="small text-body-secondary">
                    <i class="fa-solid fa-server me-1" aria-hidden="true"></i>{server}
                  </div>
                {/if}
                {#if item.expiresAt}
                  <div class="small text-body-secondary">
                    {$_('pages.order-detail.expires')}
                    <DateComponent time={item.expiresAt} />
                  </div>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {item.quantity}
                {#if itemRefunded(item)}
                  <span class="text-body-secondary">(−{item.refundedQuantity})</span>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if child}
                  <span class="text-body-secondary">—</span>
                {:else}
                  {#if showListPrice(item)}
                    <s class="text-body-secondary me-1"
                      >{fmt.money(item.listUnitPrice, currency)}</s>
                  {/if}
                  {fmt.money(item.unitPrice, currency)}
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {child ? '—' : fmt.percent(item.vatPercent)}
              </td>
              <td class="align-middle text-nowrap">
                {child ? '—' : fmt.money(item.lineTotal, currency)}
              </td>
              <td class="align-middle">
                {#if state === 'none'}
                  <span class="text-body-secondary">—</span>
                {:else}
                  <span class="badge text-bg-{DELIVERY_STATE_KIND[state]}">
                    {$_(`pages.order-detail.delivery-state.${state}`)}
                  </span>
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
  import { itemActions } from './actions.js';
  import ControlDropdown from './ControlDropdown.svelte';
  import {
    DELIVERY_STATE_KIND,
    fieldEntries,
    isBundleChild,
    itemDeliveryState,
    itemRefunded,
    itemTargetServer,
    showListPrice,
  } from './model.js';

  // detail: GET /orders/:id; user: layout user; onRerun(item), onRevoke(item): the page runs them.
  let { detail, user = null, onRerun = () => {}, onRevoke = () => {} } = $props();

  const items = $derived(detail?.items ?? []);
  const deliveries = $derived(detail?.deliveries ?? []);
  const currency = $derived(detail?.order?.currency);

  function menu(item) {
    return itemActions(item, detail, user).map((id) =>
      id === 'rerun'
        ? {
            key: id,
            label: $_('pages.order-detail.actions.rerun'),
            icon: 'fa-rotate-right',
            onclick: () => onRerun(item),
          }
        : {
            key: id,
            label: $_('pages.order-detail.actions.revoke-item'),
            icon: 'fa-user-slash',
            danger: true,
            onclick: () => onRevoke(item),
          },
    );
  }
</script>
