{#if visible}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.order-detail.cards.shipments', { values: { count: shipments.length } })}
      </div>
      <div slot="right">
        <CardMenu items={cardMenu} />
      </div>
    </CardHeader>

    {#if shipments.length === 0}
      <NoContent icon="" />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover">
          <thead>
            <tr>
              <th class="align-middle text-nowrap" scope="col"></th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.order-detail.table.recipient')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.order-detail.table.carrier')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.order-detail.table.tracking')}
              </th>
              <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.order-detail.table.shipped')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.order-detail.table.delivered')}
              </th>
            </tr>
          </thead>
          <tbody>
            {#each shipments as shipment (shipment.id)}
              <tr>
                <th scope="row" class="align-middle">
                  <ControlDropdown items={menu(shipment)} />
                </th>
                <td class="align-middle">
                  {#if shipment.toAddress}
                    <div>
                      {[shipment.toAddress.firstName, shipment.toAddress.lastName]
                        .filter(Boolean)
                        .join(' ')}
                    </div>
                    <div class="text-body-secondary">{shipment.toAddress.city ?? ''}</div>
                  {:else}
                    <span class="text-body-secondary">—</span>
                  {/if}
                </td>
                <td class="align-middle">{shipment.carrierName ?? shipment.providerId ?? '—'}</td>
                <td class="align-middle text-nowrap">
                  {#if shipment.trackingNumber}
                    {#if isHttpUrl(shipment.trackingUrl)}
                      <a href={shipment.trackingUrl} target="_blank" rel="noopener">
                        {shipment.trackingNumber}
                      </a>
                    {:else}
                      {shipment.trackingNumber}
                    {/if}
                    <CopyButton text={shipment.trackingNumber} />
                  {:else}
                    <span class="text-body-secondary">—</span>
                  {/if}
                </td>
                <td class="align-middle">
                  <StatusBadge kind="shipment" value={shipment.status} />
                  {#if shipment.stale}
                    <span class="badge text-bg-warning ms-1">{$_('pages.order-detail.stale')}</span>
                  {/if}
                </td>
                <td class="align-middle text-nowrap">
                  {#if shipment.shippedAt}
                    <DateComponent time={shipment.shippedAt} />
                  {:else}
                    <span class="text-body-secondary">—</span>
                  {/if}
                </td>
                <td class="align-middle text-nowrap">
                  {#if shipment.deliveredAt}
                    <DateComponent time={shipment.deliveredAt} />
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
{/if}

<script>
  import { CardHeader, Date as DateComponent, NoContent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import CopyButton from '../CopyButton.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import { shipmentRowItems } from './actions.js';
  import CardMenu from './CardMenu.svelte';
  import ControlDropdown from './ControlDropdown.svelte';
  import { isHttpUrl } from './model.js';
  import { shipmentLabelPath } from './requests.js';
  import { can } from '../../utils/permissions.js';

  // onCreate() opens CreateShipmentModal (external.js; null = not wired, no menu); onView(shipment)
  // opens ShipmentModal (null = no View item); onTrack / onRetry / onCancel(shipment) are run by the page.
  let {
    detail,
    user = null,
    onCreate = null,
    onView = null,
    onTrack = () => {},
    onRetry = () => {},
    onCancel = () => {},
  } = $props();

  const visible = $derived(detail?.order?.requiresShipping === true);
  const shipments = $derived(detail?.shipments ?? []);

  const cardMenu = $derived(
    onCreate && detail?.allowed?.createShipment === true && can(user, 'OM')
      ? [
          {
            key: 'create',
            label: $_('pages.order-detail.actions.create-shipment'),
            icon: 'fa-truck',
            onclick: onCreate,
          },
        ]
      : [],
  );

  function menu(shipment) {
    return shipmentRowItems(shipment, user, Boolean(onView)).map((id) => {
      if (id === 'view')
        return {
          key: id,
          label: $_('pages.shipments.actions.view'),
          icon: 'fa-eye',
          onclick: () => onView(shipment),
        };
      if (id === 'label' || id === 'generic-label')
        return {
          key: id,
          label: $_(
            id === 'label'
              ? 'pages.order-detail.actions.label'
              : 'pages.order-detail.actions.generic-label',
          ),
          icon: 'fa-file-pdf',
          href: shipmentLabelPath(base, shipment.id, id === 'generic-label'),
          blank: true,
        };
      if (id === 'track')
        return {
          key: id,
          label: $_('pages.order-detail.actions.track'),
          icon: 'fa-location-crosshairs',
          onclick: () => onTrack(shipment),
        };
      if (id === 'retry')
        return {
          key: id,
          label: $_('pages.order-detail.actions.retry'),
          icon: 'fa-rotate-right',
          onclick: () => onRetry(shipment),
        };
      return {
        key: id,
        label: $_('pages.order-detail.actions.cancel-row'),
        icon: 'fa-ban',
        danger: true,
        onclick: () => onCancel(shipment),
      };
    });
  }
</script>
