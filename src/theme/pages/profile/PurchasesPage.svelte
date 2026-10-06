<div class="vstack gap-4">
  {#if data.pills}
    <ProfilePills summary={data.summary} current="purchases" />
  {/if}

  {#if entitlements.length}
    <section aria-labelledby="market-active-title">
      <h2 class="h5" id="market-active-title">{$_('theme.profile.purchases.active-title')}</h2>
      <ul class="list-group">
        {#each entitlements as entitlement, index (entitlement.id ?? index)}
          <li
            class="list-group-item d-flex flex-wrap align-items-center justify-content-between gap-2">
            <div>
              <span class="fw-semibold">{entitlement.name}</span>
              {#if entitlement.variant}
                <span class="text-body-secondary">&middot; {entitlement.variant}</span>
              {/if}
              {#if entitlement.subscriptionId}
                <a class="ms-2 small" href="{base}/profile/subscriptions">
                  <i class="fa-solid fa-rotate me-1" aria-hidden="true"></i>{$_(
                    'theme.profile.purchases.subscription-link',
                  )}
                </a>
              {/if}
            </div>
            <span
              class={['small', entitlement.soon ? 'text-warning-emphasis' : 'text-body-secondary']}>
              {#if entitlement.permanent}
                {$_('theme.profile.purchases.permanent')}
              {:else}
                {$_('theme.profile.purchases.valid-until', {
                  values: { date: formatDateTime(entitlement.expiresAt) },
                })}
              {/if}
            </span>
          </li>
        {/each}
      </ul>
    </section>
  {/if}

  <section class="card">
    <div class="card-body">
      <GiftRedeemForm />
    </div>
  </section>

  <section aria-labelledby="market-orders-title">
    <div class="d-flex flex-wrap align-items-center justify-content-between gap-2 mb-3">
      <h2 class="h5 mb-0" id="market-orders-title">{$_('theme.profile.purchases.orders-title')}</h2>
      <div class="d-flex align-items-center gap-2">
        <label class="form-label mb-0 small" for="market-orders-filter">
          {$_('theme.profile.purchases.filter-label')}
        </label>
        <select
          id="market-orders-filter"
          class="form-select form-select-sm w-auto"
          value={filter.status}
          onchange={onStatus}>
          <option value="">{$_('theme.profile.purchases.filter-all')}</option>
          {#each ORDER_FILTERS as status (status)}
            <option value={status}>{$_(`theme.status.order.${status}`)}</option>
          {/each}
        </select>
      </div>
    </div>

    {#if orders.state === 'ERROR'}
      <ErrorAlert message={$_(messageKey(orders.code))} onretry={retry} />
    {:else}
      <div aria-busy={loading ? 'true' : 'false'}>
        {#if loading && !rows.length}
          <LoadingBlock rows={5} />
        {:else if rows.length}
          <div class={['table-responsive', loading && 'opacity-50']}>
            <table class="table align-middle">
              <caption class="visually-hidden"
                >{$_('theme.profile.purchases.orders-title')}</caption>
              <thead>
                <tr>
                  <th scope="col">{$_('theme.profile.purchases.col-number')}</th>
                  <th scope="col">{$_('theme.profile.purchases.col-date')}</th>
                  <th scope="col">{$_('theme.profile.purchases.col-items')}</th>
                  <th scope="col" class="text-end">{$_('theme.profile.purchases.col-total')}</th>
                  <th scope="col">{$_('theme.profile.purchases.col-status')}</th>
                </tr>
              </thead>
              <tbody>
                {#each rows as row (row.publicId)}
                  <tr>
                    <td class="text-nowrap"><a href="{base}{row.href}">#{row.number}</a></td>
                    <td class="text-nowrap">{formatDate(row.createdAt)}</td>
                    <td>
                      {row.items.names.join(', ')}
                      {#if row.items.more > 0}
                        <span class="text-body-secondary">
                          {$_('theme.profile.purchases.items-more', {
                            values: { count: row.items.more },
                          })}
                        </span>
                      {/if}
                      {#if row.gift}
                        <div class="small text-body-secondary">
                          <i class="fa-solid fa-gift me-1" aria-hidden="true"></i>
                          {#if row.gift.kind === 'RECEIVED'}
                            {$_('theme.profile.purchases.gift-received')}
                          {:else}
                            {$_('theme.profile.purchases.gift-for', {
                              values: { username: row.gift.username },
                            })}
                          {/if}
                        </div>
                      {/if}
                    </td>
                    <td class="text-end text-nowrap">
                      {formatMoney(row.total, row.currency, { removeCents })}
                    </td>
                    <td><OrderStatusBadge status={row.status} /></td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        {:else}
          <NoContent
            icon="fa-solid fa-bag-shopping fa-3x"
            text={$_('theme.profile.purchases.empty')} />
        {/if}

        <div class="mt-3">
          <Pager page={filter.page} totalPage={orders.totalPage} onpage={onPage} />
        </div>
      </div>
    {/if}
  </section>
</div>

<script module>
  import { redirect } from '@panomc/sdk/svelte';
  import { ordersQuery, parseListQuery, resolvePurchasesLoad } from '../../lib/profileModel.js';
  import { ensureSettings } from '../../stores/storeSettings.js';
  import { call } from '../../utils/api.js';
  import { has, loginUrl } from '../../utils/host.js';

  const ORDERS_PATH = '/api/market/me/orders';
  const ENTITLEMENTS_PATH = '/api/market/me/entitlements';
  const SUMMARY_PATH = '/api/market/me/summary';

  export async function load(event) {
    const returnTo = `${event.url.pathname}${event.url.search}`;
    const { session } = await event.parent();

    // a guest returns to this page after signing in (login-return-url) or lands on the plain login
    if (!session?.user) throw redirect(302, loginUrl(returnTo));

    const filter = parseListQuery(event.url.searchParams);
    const pills = !has('page-sidebar-id');

    const [orders, entitlements, settings, summary] = await Promise.all([
      call('GET', ORDERS_PATH, { event, query: ordersQuery(filter) }),
      call('GET', ENTITLEMENTS_PATH, { event, query: { active: true } }),
      ensureSettings(event),
      pills ? call('GET', SUMMARY_PATH, { event }) : null,
    ]);

    let list = orders;
    let used = filter;

    // a page past the end (the list shrank): back to the first page
    if (!orders.ok && orders.code === 'PAGE_NOT_FOUND' && filter.page !== 1) {
      used = { ...filter, page: 1 };
      list = await call('GET', ORDERS_PATH, { event, query: ordersQuery(used) });
    }

    const result = resolvePurchasesLoad({
      orders: list,
      entitlements,
      summary,
      filter: used,
      settings,
      features: { sidebar: has('page-sidebar-id'), meta: has('page-meta') },
    });

    if (result.redirect) throw redirect(302, loginUrl(returnTo));

    result.data.pills = pills;

    return result;
  }
</script>

<script>
  import { getContext, onMount, untrack } from 'svelte';
  import { base } from '@panomc/sdk/svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { _ } from '../../../i18n.js';
  import ErrorAlert from '../../components/common/ErrorAlert.svelte';
  import LoadingBlock from '../../components/common/LoadingBlock.svelte';
  import GiftRedeemForm from '../../components/profile/GiftRedeemForm.svelte';
  import OrderStatusBadge from '../../components/profile/OrderStatusBadge.svelte';
  import ProfilePills from '../../components/profile/ProfilePills.svelte';
  import Pager from '../../components/store/Pager.svelte';
  import { messageKey } from '../../lib/errorMap.js';
  import {
    ORDER_FILTERS,
    entitlementView,
    listSearch,
    orderRow,
    readOrders,
  } from '../../lib/profileModel.js';
  import { createSequencer } from '../../lib/storeFilter.js';
  import { now } from '../../stores/clock.js';
  import { bindSession, hostSession } from '../../stores/session.js';
  import { storeSettings } from '../../stores/storeSettings.js';
  import { formatDate, formatDateTime, formatMoney } from '../../utils/format.js';

  let { data } = $props();

  bindSession(hostSession(getContext));

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);

  let filter = $state(init.filter ?? { page: 1, status: '' });
  let orders = $state(init.orders ?? readOrders(null));
  let loading = $state(false);

  const seq = createSequencer();

  const entitlements = $derived((init.entitlements ?? []).map((e) => entitlementView(e, $now)));
  const rows = $derived((orders.orders ?? []).map(orderRow));
  const removeCents = $derived(
    (init.settings?.removeCents ?? $storeSettings?.removeCents) === true,
  );

  function writeUrl() {
    try {
      const search = listSearch(filter);

      window.history.replaceState(
        window.history.state,
        '',
        `${window.location.pathname}${search ? `?${search}` : ''}${window.location.hash}`,
      );
    } catch (e) {
      // no-op
    }
  }

  /** One request per change; an answer that is no longer the latest request is dropped. */
  async function fetchOrders(next) {
    filter = next;
    loading = true;

    const mine = seq.beginGrid();
    let res = await call('GET', ORDERS_PATH, { query: ordersQuery(next) });
    if (!seq.isGridLatest(mine)) return;

    if (!res.ok && res.code === 'PAGE_NOT_FOUND' && next.page !== 1) {
      filter = next = { ...next, page: 1 };
      res = await call('GET', ORDERS_PATH, { query: ordersQuery(next) });
      if (!seq.isGridLatest(mine)) return;
    }

    loading = false;
    orders = readOrders(res);
    if (res.ok) writeUrl();
  }

  function onStatus(event) {
    fetchOrders({ page: 1, status: event.currentTarget.value });
  }

  function onPage(page) {
    fetchOrders({ ...filter, page });
  }

  function retry() {
    fetchOrders(filter);
  }

  onMount(() => () => seq.invalidate());
</script>
