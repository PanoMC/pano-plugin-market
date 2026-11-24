{#if error}
  <LoadError {error} onRetry={refresh} />
{:else if view.hasContent}
  <div class="vstack gap-3">
    <div class="row g-3">
      {#each view.stats as stat (stat.key)}
        <div class="col-6 col-lg-3">
          <div class="card {stat.cls}">
            <div class="card-body">
              <p class="m-0">{$_(`pages.player-market.stat.${stat.key}`)}</p>
              <span class="fs-2 lh-1 text-break">{stat.value}</span>
            </div>
          </div>
        </div>
      {/each}
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.player-market.orders', { values: { count: view.orders.length } })}
        </div>
        <div slot="right">
          {#if hasActions}
            <div class="dropdown">
              <button
                type="button"
                class="btn btn-sm btn-link"
                data-bs-toggle="dropdown"
                title={$_('common.actions')}
                aria-label={$_('common.actions')}>
                <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
              </button>
              <div class="dropdown-menu dropdown-menu-end">
                {#if view.actions.allOrders}
                  <a
                    class="dropdown-item rounded focus-ring text-decoration-none"
                    href="{base}{ordersLink(username)}">
                    <i class="fa-solid fa-list me-2" aria-hidden="true"></i>
                    {$_('pages.player-market.all-orders')}
                  </a>
                {/if}
                {#if view.actions.grant}
                  <button type="button" class="dropdown-item" onclick={() => openAdjust('grant')}>
                    <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
                    {$_('pages.credits.grant')}
                  </button>
                {/if}
                {#if view.actions.revoke}
                  <button type="button" class="dropdown-item" onclick={() => openAdjust('revoke')}>
                    <i class="fa-solid fa-minus me-2" aria-hidden="true"></i>
                    {$_('pages.credits.revoke')}
                  </button>
                {/if}
                {#if view.actions.createOrder}
                  <a
                    class="dropdown-item rounded focus-ring text-decoration-none"
                    href="{base}{createOrderLink(username)}">
                    <i class="fa-solid fa-cart-plus me-2" aria-hidden="true"></i>
                    {$_('pages.player-market.create-order')}
                  </a>
                {/if}
                {#if view.actions.block}
                  <a
                    class="dropdown-item link-danger rounded focus-ring text-decoration-none"
                    href="{base}{blocksLink(username)}">
                    <i class="fa-solid fa-ban me-2" aria-hidden="true"></i>
                    {$_('pages.player-market.block')}
                  </a>
                {/if}
              </div>
            </div>
          {/if}
        </div>
      </CardHeader>

      {#if view.orders.length === 0}
        <NoContent />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.orders.table.order')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.orders.table.products')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.orders.table.total')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.orders.table.date')}</th>
              </tr>
            </thead>
            <tbody>
              {#each view.orders as order (order.id)}
                {@const products = productsCell(order)}
                <tr
                  class="row-action"
                  use:rowLink={canOrders ? `${base}/market/orders/detail/${order.id}` : null}
                  title={canOrders ? $_('pages.orders.actions.view') : undefined}>
                  <td class="align-middle text-nowrap">
                    {#if canOrders}
                      <a
                        class="rounded focus-ring text-decoration-none"
                        href="{base}/market/orders/detail/{order.id}">#{order.id}</a>
                    {:else}
                      #{order.id}
                    {/if}
                    {#if order.testMode}
                      <span class="badge text-bg-warning ms-1">{$_('common.test')}</span>
                    {/if}
                  </td>
                  <td class="text-nowrap">
                    <div class="d-flex align-items-center gap-1" style="max-width: 240px;">
                      <span class="text-truncate" title={products.first || '—'}
                        >{products.first || '—'}</span>
                      {#if products.more > 0}
                        <span class="text-body-secondary flex-shrink-0">+{products.more}</span>
                      {/if}
                    </div>
                  </td>
                  <td class="align-middle text-nowrap"
                    >{fmt.money(order.totalPrice, order.currency)}</td>
                  <td class="text-nowrap"><StatusBadge kind="order" value={order.status} /></td>
                  <td class="align-middle text-nowrap">
                    <DateComponent time={order.paidAt ?? order.createdAt} />
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>

    {#if view.entitlements.length > 0}
      <div class="card">
        <CardHeader>
          <div slot="left">
            {$_('pages.player-market.entitlements', {
              values: { count: view.entitlements.length },
            })}
          </div>
        </CardHeader>
        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.player-market.table.product')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.player-market.table.starts')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.player-market.table.expires')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.orders.table.order')}</th>
              </tr>
            </thead>
            <tbody>
              {#each view.entitlements as entitlement (entitlement.id)}
                <tr
                  class="row-action"
                  use:rowLink={entitlement.orderId && canOrders
                    ? `${base}/market/orders/detail/${entitlement.orderId}`
                    : null}
                  title={entitlement.orderId && canOrders
                    ? $_('pages.orders.actions.view')
                    : undefined}>
                  <td class="text-nowrap">
                    <div
                      class="text-truncate"
                      style="max-width: 240px;"
                      title={entitlement.variantName
                        ? `${entitlement.productName ?? '—'} (${entitlement.variantName})`
                        : (entitlement.productName ?? '—')}>
                      {entitlement.productName ?? '—'}
                      {#if entitlement.variantName}
                        <span class="text-body-secondary">({entitlement.variantName})</span>
                      {/if}
                    </div>
                  </td>
                  <td class="text-nowrap"
                    ><StatusBadge kind="entitlement" value={entitlement.status} /></td>
                  <td class="align-middle text-nowrap">{dateText(entitlement.startsAt)}</td>
                  <td class="align-middle text-nowrap">
                    {entitlement.expiresAt
                      ? dateText(entitlement.expiresAt)
                      : $_('common.permanent')}
                  </td>
                  <td class="align-middle text-nowrap">
                    {#if entitlement.orderId && canOrders}
                      <a
                        class="rounded focus-ring text-decoration-none"
                        href="{base}/market/orders/detail/{entitlement.orderId}">
                        #{entitlement.orderId}
                      </a>
                    {:else if entitlement.orderId}
                      #{entitlement.orderId}
                    {:else}
                      &mdash;
                    {/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      </div>
    {/if}

    {#if view.subscriptions.length > 0}
      <div class="card">
        <CardHeader>
          <div slot="left">
            {$_('pages.player-market.subscriptions', {
              values: { count: view.subscriptions.length },
            })}
          </div>
        </CardHeader>
        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.subscriptions.table.product')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.subscriptions.table.price')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col">
                  {$_('pages.subscriptions.table.period-end')}
                </th>
              </tr>
            </thead>
            <tbody>
              {#each view.subscriptions as subscription (subscription.id)}
                <tr
                  class="row-action"
                  use:rowLink={canOrders
                    ? `${base}/market/subscriptions/detail/${subscription.id}`
                    : null}
                  title={canOrders ? $_('pages.subscriptions.actions.view') : undefined}>
                  <td class="text-nowrap">
                    {#if canOrders}
                      <a
                        class="rounded focus-ring text-decoration-none d-inline-block text-truncate align-bottom"
                        style="max-width: 240px;"
                        title={subscription.productName ?? `#${subscription.id}`}
                        href="{base}/market/subscriptions/detail/{subscription.id}">
                        {subscription.productName ?? `#${subscription.id}`}
                      </a>
                    {:else}
                      <div
                        class="text-truncate"
                        style="max-width: 240px;"
                        title={subscription.productName ?? `#${subscription.id}`}>
                        {subscription.productName ?? `#${subscription.id}`}
                      </div>
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap"
                    >{priceText(subscription, fmt.money, duration)}</td>
                  <td class="text-nowrap"
                    ><StatusBadge kind="subscription" value={subscription.status} /></td>
                  <td class="align-middle text-nowrap"
                    >{dateText(subscription.currentPeriodEnd)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      </div>
    {/if}

    {#if view.blocks.length > 0}
      <div class="card">
        <CardHeader>
          <div slot="left">
            {$_('pages.player-market.blocks', { values: { count: view.blocks.length } })}
          </div>
        </CardHeader>
        <div class="table-responsive">
          <table class="table table-hover align-middle mb-0">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.blocks.table.type')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.blocks.table.value')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.blocks.table.reason')}</th>
                <th class="align-middle text-nowrap" scope="col"
                  >{$_('pages.blocks.table.expires')}</th>
              </tr>
            </thead>
            <tbody>
              {#each view.blocks as block (block.id)}
                <tr>
                  <td class="align-middle text-nowrap">{$_(`enums.block-type.${block.type}`)}</td>
                  <td class="font-monospace text-nowrap"
                    ><div class="text-truncate" style="max-width: 200px;" title={block.value}>
                      {block.value}
                    </div></td>
                  <td class="text-nowrap"
                    ><div
                      class="text-truncate"
                      style="max-width: 280px;"
                      title={blockReason(block, $_)}>
                      {blockReason(block, $_)}
                    </div></td>
                  <td class="align-middle text-nowrap">
                    {block.expiresAt ? dateText(block.expiresAt) : $_('common.never')}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      </div>
    {/if}
  </div>

  <CreditAdjustModal bind:this={adjustModal} {ctx} onSaved={refresh} />
{/if}

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { CardHeader, Date as DateComponent, NoContent } from '@panomc/sdk/components/panel';
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { blockReason } from '../../utils/blocks.js';
  import LoadError from '../LoadError.svelte';
  import StatusBadge from '../StatusBadge.svelte';
  import CreditAdjustModal from '../modals/CreditAdjustModal.svelte';
  import { productsCell } from '../orders/filters.js';
  import { call } from '../../utils/api.js';
  import { formatDuration } from '../../utils/format.js';
  import { currentLocale, fmt } from '../../utils/locale.js';
  import { priceText } from '../../utils/subscriptions.js';
  import {
    blocksLink,
    buildSummaryView,
    createOrderLink,
    ordersLink,
    summaryPath,
  } from './summary.js';
  import { can } from '../../utils/permissions.js';
  import { rowLink } from '../../utils/row-link.js';

  // Rendered by PlayerMarket.svelte (tab) and PlayerMarketCard.svelte (hook card).
  let { summary = null, username = '', ctx = null, error = null } = $props();

  // After a credit change the summary is re-fetched locally (13 section 24).
  let refreshed = $state.raw(null);
  let refreshError = $state(null);
  let adjustModal = $state(null);

  const user = $derived($page.data?.user);
  const canOrders = $derived(can(user, 'OV'));
  const shownError = $derived(refreshError ?? error);
  const view = $derived(
    buildSummaryView({
      summary: refreshed ?? summary,
      username,
      ctx,
      user,
      error: shownError,
      fmt,
    }),
  );
  const hasActions = $derived(view.hasContent && Object.values(view.actions).some(Boolean));

  const duration = (unit, count) => formatDuration(unit, count, $_);
  const dateText = (epoch) => (epoch ? new Date(epoch).toLocaleString(currentLocale()) : '—');

  function openAdjust(mode) {
    if (view.account) adjustModal?.open({ mode, account: view.account });
  }

  async function refresh() {
    const result = await call(api.panel.get({ path: summaryPath(username) }));
    if (!result.ok) {
      refreshError = result.error;
      return;
    }
    refreshError = null;
    refreshed = result.body;
  }
</script>
