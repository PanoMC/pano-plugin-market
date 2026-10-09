<script module>
  import { buildQueryParams } from '@panomc/sdk/utils/api';
  import { api } from '@panomc/sdk/plugin-api';
  import { failureOf } from '../utils/api.js';
  import { loadContext } from '../utils/context.js';
  import { normalizeEarningState } from '../utils/discounts.js';
  import { pageOf } from '../utils/page.js';

  /** The three requests of the page: earnings (paged, filtered), payouts and the report row (balances). */
  function detailRequests(get, id, pageNum, state) {
    const encoded = encodeURIComponent(id);
    return Promise.all([
      get({
        path:
          `/creator-codes/${encoded}/earnings` +
          buildQueryParams({ page: pageNum > 1 ? pageNum : null, state }),
      }),
      get({ path: `/creator-codes/${encoded}/payouts` }),
      get({ path: '/creator-codes/report' }),
    ]);
  }

  const isBody = (body) => failureOf(body) === null;

  /** Joins the three bodies into the data of the page; the first failed one sets `error`. */
  function joinDetail(id, [earnings, payouts, report], state) {
    const failed = [earnings, payouts].find((body) => !isBody(body));
    const row = isBody(report) ? (report.creators ?? []).find((c) => String(c.id) === String(id)) : null;
    return {
      id,
      earningsPage: pageOf(isBody(earnings) ? earnings : null),
      payouts: isBody(payouts) ? (payouts.payouts ?? []) : [],
      summary: row ?? null,
      currency: isBody(report) ? (report.currency ?? '') : '',
      state,
      error: failed === undefined ? null : failureOf(failed),
    };
  }

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const { pageTitle } = await event.parent();
    pageTitle?.set?.('plugins.pano-plugin-market.pages.creator-detail.title');

    const id = event.params.id;
    const requested = parseInt(event.url.searchParams.get('page')) || 1;
    const state = normalizeEarningState(event.url.searchParams.get('state'));
    const get = (options) => api.panel.get({ ...options, request: event });

    let [bodies, ctx] = await Promise.all([detailRequests(get, id, requested, state), loadContext(event)]);
    // A stale ?page= (bookmark, back button) points past the last page: refetch page 1 once.
    if (failureOf(bodies[0]) === 'PAGE_NOT_FOUND' && requested > 1) {
      bodies = await detailRequests(get, id, 1, state);
    }
    return { data: { ...joinDetail(id, bodies, state), ctx } };
  }
</script>

<script>
  import FilterSelect from '../components/FilterSelect.svelte';
  import { CardFilters, CardHeader, NoContent, Pagination } from '@panomc/sdk/components/panel';
  import { base, goto, page } from '@panomc/sdk/svelte';
  import { _, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import PlayerCell from '../components/PlayerCell.svelte';
  import StatusBadge from '../components/StatusBadge.svelte';
  import PayoutModal from '../components/modals/PayoutModal.svelte';
  import { call } from '../utils/api.js';
  import { canCancelPayout, canPayOut } from '../utils/discounts.js';
  import { currentLocale, fmt } from '../utils/locale.js';
  import { can } from '../utils/permissions.js';
  import { toastError } from '../utils/toast.js';

  let { data } = $props();

  // Detail pages never invalidateAll() after a mutation: the loaded object lives in $state and a
  // local refresh() re-GETs it (13 section 1.4).
  let loaded = $state.raw(null);
  let refreshError = $state(null);
  let refreshing = $state(false);
  let confirmModal = $state(null);
  let payoutModal = $state(null);

  const view = $derived(loaded ?? data);
  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const error = $derived(refreshError ?? data.error ?? null);
  const mayPay = $derived(can(user, 'PAY'));
  const currency = $derived(view.currency || ctx?.currency || '');
  const summary = $derived(view.summary);
  const earningsPage = $derived(view.earningsPage ?? pageOf(null));
  const earnings = $derived(earningsPage.items);
  const payouts = $derived(view.payouts ?? []);
  const totalPage = $derived(earningsPage.totalPages);
  const currentPage = $derived(earningsPage.number);
  const state = $derived(view.state ?? null);
  const creatorName = $derived(summary?.creator ?? $page.url.searchParams.get('creator') ?? null);

  const FILTERS = [
    { value: null, label: 'common.all' },
    { value: 'AVAILABLE', label: 'enums.earning.AVAILABLE' },
    { value: 'PAID', label: 'enums.earning.PAID' },
    { value: 'REVERSED', label: 'enums.earning.REVERSED' },
  ];

  const dateText = (epoch) => (epoch ? new Date(epoch).toLocaleString(currentLocale()) : '—');
  const filterHref = (value) =>
    `/market/discounts/creator/${data.id}${buildQueryParams({ state: value })}`;

  async function refresh() {
    if (refreshing) return;
    refreshing = true;
    const get = (options) => api.panel.get(options);
    const bodies = await detailRequests(get, data.id, currentPage, state);
    refreshing = false;
    const next = joinDetail(data.id, bodies, state);
    refreshError = next.error;
    if (!next.error) loaded = next;
  }

  function cancelPayout(payout) {
    confirmModal?.open({
      icon: 'fa-solid fa-ban',
      title: $_('pages.creator-detail.cancel-title'),
      description: $_('pages.creator-detail.cancel-description', {
        values: { amount: fmt.money(payout.amount, payout.currency || currency) },
      }),
      confirmLabel: $_('pages.creator-detail.cancel-confirm'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(api.panel.post({ path: `/creator-payouts/${payout.id}/cancel` }));
        if (!result.ok) {
          toastError($_, result);
          // a payout that is no longer pending (or gone) is stale: show the current state
          if (['INVALID_STATE', 'NOT_FOUND'].includes(result.error)) setTimeout(refresh, 350);
          return false;
        }
        showSuccessToast($_('pages.creator-detail.toast-cancelled'));
        setTimeout(refresh, 350);
      },
    });
  }

  function openPayout() {
    payoutModal?.open({
      creator: { id: data.id, creator: creatorName ?? '', code: summary?.code ?? '', available: summary?.available },
      currency,
    });
  }

  function onPageClick(pageNum) {
    return goto(
      `${base}/market/discounts/creator/${data.id}${buildQueryParams({
        page: pageNum > 1 ? pageNum : null,
        state,
        creator: $page.url.searchParams.get('creator') || null,
      })}`,
      { invalidateAll: true, keepFocus: true, noscroll: true },
    );
  }
</script>

<MarketLayout area="discounts">
  {#snippet left()}
    <div class="d-flex align-items-center gap-3 flex-wrap">
      <a class="btn btn-link px-0" href="{base}/market/discounts?section=payouts">
        <i class="fa-solid fa-arrow-left me-2" aria-hidden="true"></i>
        {$_('common.back')}
      </a>
      {#if creatorName}
        <PlayerCell username={creatorName} />
      {:else}
        <span>{$_('pages.creator-detail.creator-id', { values: { id: data.id } })}</span>
      {/if}
      {#if summary}
        <span class="badge text-bg-secondary font-monospace">{summary.code}</span>
        <span class="badge {summary.available < 0 ? 'text-bg-danger' : 'text-bg-info'} fs-6">
          {$_('pages.creator-detail.available', {
            values: { amount: fmt.money(summary.available, currency) },
          })}
        </span>
      {/if}
    </div>
  {/snippet}
  {#snippet right()}
    {#if summary && canPayOut(summary, mayPay)}
      <button type="button" class="btn btn-secondary" onclick={openPayout}>
        <i class="fa-solid fa-money-bill-transfer" aria-hidden="true"></i>
        <span class="d-lg-inline d-none ms-2">{$_('discounts.payouts.pay-out')}</span>
      </button>
    {/if}
  {/snippet}

  {#if error}
    <LoadError {error} onRetry={() => refresh()} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.creator-detail.earning-count', { values: { count: earningsPage.totalItems } })}
        </div>
        <CardFilters slot="right">
          <FilterSelect
            label={$_('common.status')}
            current={state ?? 'ALL'}
            options={FILTERS.map((filter) => ({ key: filter.value ?? 'ALL', value: filter.value, label: $_(filter.label) }))}
            onSelect={(filter) => goto(base + filterHref(filter.value))} />
        </CardFilters>
      </CardHeader>

      {#if earnings.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.order')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.base-amount')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.commission')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.amount')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.state')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.date')}</th>
              </tr>
            </thead>
            <tbody>
              {#each earnings as earning (earning.id)}
                <tr>
                  <td class="align-middle text-nowrap">
                    {#if can(user, 'OV')}
                      <a href="{base}/market/orders/detail/{earning.orderId}">#{earning.orderId}</a>
                    {:else}
                      #{earning.orderId}
                    {/if}
                  </td>
                  <td class="align-middle text-nowrap">{fmt.money(earning.baseAmount, currency)}</td>
                  <td class="align-middle text-nowrap">{fmt.percent(earning.commissionPercent)}</td>
                  <td class="align-middle text-nowrap">{fmt.money(earning.amount, currency)}</td>
                  <td class="align-middle text-nowrap">
                    <StatusBadge kind="earning" value={earning.state} />
                  </td>
                  <td class="align-middle text-nowrap">{dateText(earning.createdAt)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
        {#if totalPage > 1}
          <div class="card-footer">
            <Pagination
              page={currentPage}
              {totalPage}
              on:firstPageClick={() => onPageClick(1)}
              on:lastPageClick={() => onPageClick(totalPage)}
              on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
          </div>
        {/if}
      {/if}
    </div>

    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.creator-detail.payout-count', { values: { count: payouts.length } })}
        </div>
      </CardHeader>

      {#if payouts.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col" style="width: 50px;"></th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.amount')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.method')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.state')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.note')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.paid-by')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.creator-detail.table.paid-at')}</th>
              </tr>
            </thead>
            <tbody>
              {#each payouts as payout (payout.id)}
                <tr>
                  <th scope="row" class="align-middle">
                    {#if canCancelPayout(payout, mayPay)}
                      <div class="dropdown position-static">
                        <button
                          type="button"
                          class="btn btn-link"
                          data-bs-toggle="dropdown"
                          title={$_('common.actions')}
                          aria-label={$_('common.actions')}>
                          <span class="fas fa-ellipsis-v"></span>
                        </button>
                        <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                          <button type="button" class="dropdown-item link-danger" onclick={() => cancelPayout(payout)}>
                            <i class="fas fa-ban me-2"></i>
                            {$_('pages.creator-detail.cancel')}
                          </button>
                        </div>
                      </div>
                    {/if}
                  </th>
                  <td class="align-middle text-nowrap">{fmt.money(payout.amount, payout.currency || currency)}</td>
                  <td class="align-middle text-nowrap">{$_(`enums.payout-method.${payout.method}`)}</td>
                  <td class="align-middle text-nowrap">
                    <StatusBadge kind="payout" value={payout.state} />
                  </td>
                  <td class="align-middle" style="min-width: 160px;">{payout.note ?? ''}</td>
                  <td class="align-middle">{payout.paidBy ?? ''}</td>
                  <td class="align-middle text-nowrap">{dateText(payout.paidAt)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {/if}
    </div>
  {/if}
</MarketLayout>

<ConfirmModal bind:this={confirmModal} />
<PayoutModal bind:this={payoutModal} {ctx} onSaved={() => refresh()} />
