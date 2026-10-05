<MarketLayout area="orders" sections={sectionsFor('orders', user)} active="subscriptions">
  {#snippet left()}
    <div class="d-flex align-items-center gap-3 flex-wrap">
      <a class="btn btn-link px-0" href="{base}/market/subscriptions">
        <i class="fa-solid fa-arrow-left me-2" aria-hidden="true"></i>
        {$_('common.back')}
      </a>
      {#if !error}
        <span>{$_('pages.subscription-detail.heading', { values: { id: data.id } })}</span>
        <StatusBadge kind="subscription" value={subscription?.status} />
      {/if}
    </div>
  {/snippet}
  {#snippet right()}
    {#if !error && actions.length > 0}
      <div class="dropdown d-inline-block">
        <button
          type="button"
          class="btn btn-link"
          data-bs-toggle="dropdown"
          title={$_('common.actions')}
          aria-label={$_('common.actions')}>
          <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
        </button>
        <div class="dropdown-menu dropdown-menu-end animate__animated animate__fadeIn">
          {#if actions.includes('retry')}
            <button
              type="button"
              class="dropdown-item"
              onclick={() => confirmRetry(confirmModal, $_, subscription, afterAction)}>
              <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>
              {$_('pages.subscriptions.actions.retry')}
            </button>
          {/if}
          {#if actions.includes('cancel')}
            <button
              type="button"
              class="dropdown-item link-danger"
              onclick={() => cancelModal?.open(subscription)}>
              <i class="fa-solid fa-ban me-2" aria-hidden="true"></i>
              {$_('pages.subscriptions.actions.cancel')}
            </button>
          {/if}
        </div>
      </div>
    {/if}
  {/snippet}

  {#if error}
    <LoadError {error} onRetry={() => refresh()} />
  {:else if subscription}
    <div class="card">
      <div class="card-header">{$_('pages.subscription-detail.summary')}</div>
      <div class="card-body">
        <dl class="row mb-0">
          <dt class="col-sm-4">{$_('common.status')}</dt>
          <dd class="col-sm-8">
            <StatusBadge kind="subscription" value={subscription.status} />
            {#if subscription.cancelAtPeriodEnd}
              <span class="badge text-bg-warning ms-1">
                {$_('pages.subscriptions.cancels-at-period-end')}
              </span>
            {/if}
            {#if subscription.testMode}
              <span class="badge text-bg-secondary ms-1">{$_('common.test')}</span>
            {/if}
          </dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.player')}</dt>
          <dd class="col-sm-8">
            {#if subscription.playerUsername}
              <PlayerCell username={subscription.playerUsername} />
            {:else}
              —
            {/if}
          </dd>
          <dt class="col-sm-4">{$_('pages.subscriptions.table.product')}</dt>
          <dd class="col-sm-8">{subscription.productName ?? '—'}</dd>
          <dt class="col-sm-4">{$_('pages.subscriptions.table.mode')}</dt>
          <dd class="col-sm-8">
            {subscription.mode ? $_(`enums.subscription-mode.${subscription.mode}`) : '—'}
          </dd>
          <dt class="col-sm-4">{$_('pages.subscriptions.table.method')}</dt>
          <dd class="col-sm-8">{methodText(subscription) || '—'}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.price')}</dt>
          <dd class="col-sm-8">{priceText(subscription, fmt.money, duration)}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.cycles')}</dt>
          <dd class="col-sm-8">{cycleText(subscription)}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.period-start')}</dt>
          <dd class="col-sm-8">{dateText(subscription.currentPeriodStart)}</dd>
          <dt class="col-sm-4">{$_('pages.subscriptions.table.period-end')}</dt>
          <dd class="col-sm-8">{dateText(subscription.currentPeriodEnd)}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.next-charge')}</dt>
          <dd class="col-sm-8">{dateText(subscription.nextChargeAt)}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.grace-ends')}</dt>
          <dd class="col-sm-8">{dateText(subscription.graceEndsAt)}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.fail-count')}</dt>
          <dd class="col-sm-8">{subscription.failCount ?? 0}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.end-reason')}</dt>
          <dd class="col-sm-8">{endReasonText(subscription.endReason)}</dd>
          <dt class="col-sm-4">{$_('pages.subscription-detail.gateway-id')}</dt>
          <dd class="col-sm-8">
            {#if subscription.gatewaySubscriptionId}
              <span class="font-monospace text-break">{subscription.gatewaySubscriptionId}</span>
              <CopyButton
                text={subscription.gatewaySubscriptionId}
                label={$_('pages.subscription-detail.copy-gateway-id')} />
            {:else}
              —
            {/if}
          </dd>
        </dl>
      </div>
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.subscription-detail.renewal-count', { values: { count: renewals.length } })}
        </div>
      </CardHeader>
      {#if renewals.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscription-detail.table.index')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscription-detail.table.period')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscription-detail.table.amount')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscription-detail.table.attempts')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscription-detail.table.last-error')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.subscription-detail.table.order')}</th>
              </tr>
            </thead>
            <tbody>
              {#each renewals as renewal (renewal.periodIndex)}
                <tr>
                  <td>{renewal.periodIndex}</td>
                  <td class="text-nowrap">
                    {dateText(renewal.periodStart)} – {dateText(renewal.periodEnd)}
                  </td>
                  <td class="text-nowrap">{fmt.money(renewal.amount, renewal.currency)}</td>
                  <td class="text-nowrap">
                    {#if renewal.status}
                      <span class="badge {renewalBadge(renewal.status)}">
                        {$_(`enums.renewal.${renewal.status}`)}
                      </span>
                    {:else}
                      —
                    {/if}
                  </td>
                  <td>{renewal.attempts ?? 0}</td>
                  <td>{renewal.lastError || '—'}</td>
                  <td class="text-nowrap">
                    {#if renewal.orderId}
                      <a href="{base}/market/orders/detail/{renewal.orderId}">#{renewal.orderId}</a>
                    {:else}
                      —
                    {/if}
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.subscription-detail.order-count', { values: { count: orders.length } })}
        </div>
      </CardHeader>
      {#if orders.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.order')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.total')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.orders.table.date')}</th>
              </tr>
            </thead>
            <tbody>
              {#each orders as order (order.id)}
                <tr>
                  <td class="text-nowrap">
                    <a href="{base}/market/orders/detail/{order.id}">#{order.id}</a>
                  </td>
                  <td class="text-nowrap">{fmt.money(order.totalPrice, order.currency)}</td>
                  <td class="text-nowrap"><StatusBadge kind="order" value={order.status} /></td>
                  <td class="text-nowrap">{dateText(order.createdAt)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  {/if}
</MarketLayout>

<CancelSubscriptionModal bind:this={cancelModal} onSaved={afterAction} />
<ConfirmModal bind:this={confirmModal} />

<script module>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { marketPath } from '../utils/api.js';
  import { guard } from '../utils/guard.js';
  import { PLUGIN_ID } from '../utils/plugin.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const id = event.params.id;
    const allowed = await guard(event, ['OV']);
    if (allowed.denied) return { data: { id, error: 'NO_PERMISSION' } };
    allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.pages.subscription-detail.title`);

    const body = await ApiUtil.get({
      path: marketPath(`/subscriptions/${encodeURIComponent(id)}`),
      request: event,
    });
    if (!body || typeof body !== 'object' || body.error)
      return { data: { id, error: (body && typeof body === 'object' && body.error) || 'NETWORK_ERROR' } };
    return { data: { ...body, id } };
  }
</script>

<script>
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import CopyButton from '../components/CopyButton.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PlayerCell from '../components/PlayerCell.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import CancelSubscriptionModal from '../components/modals/CancelSubscriptionModal.svelte';
  import { confirmRetry } from '../components/subscriptions/retry.js';
  import { sectionsFor } from '../navigation.js';
  import { call } from '../utils/api.js';
  import { formatDuration } from '../utils/format.js';
  import { currentLocale, fmt } from '../utils/locale.js';
  import {
    actionsFor,
    cycleText,
    methodText,
    priceText,
    renewalBadge,
  } from '../utils/subscriptions.js';

  let { data } = $props();

  // Detail pages never invalidateAll() after a mutation: the loaded object lives in $state and a
  // local refresh() re-GETs it (13 section 1.4).
  let loaded = $state.raw(null);
  let refreshError = $state(null);
  let refreshing = false;
  let cancelModal = $state(null);
  let confirmModal = $state(null);

  const view = $derived(loaded ?? data);
  const user = $derived($page.data?.user);
  const error = $derived(refreshError ?? data.error ?? null);
  const subscription = $derived(view.subscription ?? null);
  const renewals = $derived(view.renewals ?? []);
  const orders = $derived(view.orders ?? []);
  const actions = $derived(subscription ? actionsFor(subscription, user) : []);

  const duration = (unit, count) => formatDuration(unit, count, $_);
  const dateText = (epoch) => (epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—');

  // end reasons are an open set: an unknown one is shown raw
  function endReasonText(reason) {
    if (!reason) return '—';
    const key = `enums.subscription-end-reason.${reason}`;
    const text = $_(key);
    return text === key || text === `plugins.pano-plugin-market.${key}` ? reason : text;
  }

  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    const result = await call(
      ApiUtil.get({ path: marketPath(`/subscriptions/${encodeURIComponent(data.id)}`) }),
    );
    refreshing = false;
    if (!result.ok) {
      refreshError = result.error;
      return;
    }
    refreshError = null;
    loaded = result.body;
  }

  // After an action (stale or not) the page re-GETs its own object.
  function afterAction() {
    return refresh();
  }
</script>
