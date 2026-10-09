{#if showShipments && shipments.length}
  <div class="market-shipment-list vstack gap-3">
    {#each shipments as shipment, index (shipment.id ?? index)}
      <div class="card">
        <div class="market-shipment-list__body card-body vstack gap-2">
          <div class="d-flex flex-wrap align-items-center gap-2">
            {#if shipment.carrierName}
              <span class="fw-semibold">{shipment.carrierName}</span>
            {/if}
            <span
              class={['market-shipment-list__badge', 'badge', shipmentBadge(shipment.status).cls]}>
              {$_(shipmentBadge(shipment.status).labelKey)}
            </span>
          </div>

          {#if shipment.trackingNumber}
            <div class="d-flex flex-wrap align-items-center gap-2">
              <span class="text-body-secondary small">{$_('theme.order.tracking-number')}</span>
              <code>{shipment.trackingNumber}</code>
              <CopyButton text={shipment.trackingNumber} />
            </div>
          {/if}

          {#if safeTrackingUrl(shipment.trackingUrl)}
            <div>
              <a
                href={safeTrackingUrl(shipment.trackingUrl)}
                target="_blank"
                rel="noopener noreferrer">
                {$_('theme.order.track')}
                <i class="fa-solid fa-arrow-up-right-from-square ms-1" aria-hidden="true"></i>
              </a>
            </div>
          {/if}

          <div class="small text-body-secondary d-flex flex-wrap gap-3">
            {#if shipment.shippedAt}
              <span>
                {$_('theme.order.shipped-at', {
                  values: { date: formatDateTime(shipment.shippedAt) },
                })}
              </span>
            {/if}
            {#if shipment.deliveredAt}
              <span>
                {$_('theme.order.delivered-at', {
                  values: { date: formatDateTime(shipment.deliveredAt) },
                })}
              </span>
            {/if}
          </div>
        </div>
      </div>
    {/each}
  </div>
{/if}

{#if owner && (shippingLines.length || billingLines.length || email)}
  <div class="market-shipment-list row g-3 mt-0">
    {#if shippingLines.length}
      <div class="col-md-6">
        <h3 class="market-shipment-list__title h6">{$_('theme.order.shipping-address')}</h3>
        <address class="mb-0">
          {#each shippingLines as line, index (index)}{line}<br />{/each}
          {countryName(shippingAddress.country)}
        </address>
      </div>
    {/if}
    {#if billingLines.length}
      <div class="col-md-6">
        <h3 class="market-shipment-list__billing-info h6">{$_('theme.order.billing-info')}</h3>
        <address class="mb-0">
          {#each billingLines as line, index (index)}{line}<br />{/each}
          {countryName(billingInfo.country)}
        </address>
      </div>
    {/if}
    {#if email}
      <div class="col-12">
        <span class="text-body-secondary small">{$_('theme.order.email')}</span>
        <span class="text-break ms-1">{email}</span>
      </div>
    {/if}
  </div>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { addressLines, safeTrackingUrl, shipmentBadge } from '../../lib/orderState.js';
  import CopyButton from '../common/CopyButton.svelte';

  const market = plugin('market');
  const { _ } = market;
  const { countryName, formatDateTime } = market.require('format').actions;

  /**
   * Shipments of an OrderView and, for the owner, the shipping address, billing info and e-mail (14 §11.4).
   * `showShipments` = the view state shows the shipments panel; `owner` = not a limited view.
   */
  let {
    shipments = [],
    shippingAddress = null,
    billingInfo = null,
    email = '',
    owner = false,
    showShipments = true,
  } = $props();

  const shippingLines = $derived(addressLines(shippingAddress));
  const billingLines = $derived(addressLines(billingInfo));
</script>
