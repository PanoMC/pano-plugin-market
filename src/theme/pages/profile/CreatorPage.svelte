{#if data.state === 'ERROR'}
  <div class="market-creator-page vstack gap-3">
    {#if data.pills}
      <ProfilePills summary={data.summary} current="creator" />
    {/if}
    <StoreStateCard
      icon="fa-solid fa-triangle-exclamation fa-3x"
      text={$_(messageKey(data.code))}
      onretry={() => location.reload()} />
  </div>
{:else}
  <div class="market-creator-page vstack gap-4">
    {#if data.pills}
      <ProfilePills summary={data.summary} current="creator" />
    {/if}

    <section aria-labelledby="market-creator-totals">
      <h2 class="market-creator-page__title visually-hidden" id="market-creator-totals">
        {$_('theme.profile.creator.title')}
      </h2>
      <div class="row g-3">
        {#each totalCards as card (card.id)}
          <div class="col-md-4">
            <div class="card h-100">
              <div class="market-creator-page__body card-body">
                <div class="small text-body-secondary mb-1">{$_(card.key)}</div>
                <div class="h4 mb-0">{formatMoney(card.value, data.totals.currency)}</div>
              </div>
            </div>
          </div>
        {/each}
      </div>
    </section>

    <section aria-labelledby="market-creator-codes">
      <h2 class="market-creator-page__codes-title h5 mb-3" id="market-creator-codes">
        {$_('theme.profile.creator.codes-title')}
      </h2>
      {#if data.codes.length}
        <div class="table-responsive">
          <table class="market-creator-page__table table align-middle">
            <caption class="visually-hidden">{$_('theme.profile.creator.codes-title')}</caption>
            <thead>
              <tr>
                <th scope="col">{$_('theme.profile.creator.col-code')}</th>
                <th scope="col">{$_('theme.profile.creator.col-discount')}</th>
                <th scope="col">{$_('theme.profile.creator.col-commission')}</th>
                <th scope="col" class="text-end">{$_('theme.profile.creator.col-uses')}</th>
                <th scope="col">{$_('theme.profile.creator.col-status')}</th>
              </tr>
            </thead>
            <tbody>
              {#each data.codes as row (row.code)}
                <tr>
                  <td class="text-nowrap">
                    <code>{row.code}</code>
                    <CopyButton text={row.code} label={$_('theme.profile.creator.copy-code')} />
                  </td>
                  <td>
                    {#if row.discount.percent !== undefined}
                      {$_('theme.profile.creator.percent', {
                        values: { value: row.discount.percent },
                      })}
                    {:else}
                      {formatMoney(row.discount.money, data.totals.currency)}
                    {/if}
                  </td>
                  <td>
                    {$_('theme.profile.creator.percent', {
                      values: { value: row.commissionPercent },
                    })}
                  </td>
                  <td class="text-end">{row.usedCount}</td>
                  <td>
                    <span class={['market-creator-page__badge', 'badge', row.badge.className]}>
                      {row.badge.key ? $_(row.badge.key) : row.badge.raw}
                    </span>
                  </td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {:else}
        <NoContent icon="fa-solid fa-tag fa-3x" text={$_('theme.profile.creator.codes-empty')} />
      {/if}
    </section>

    <section aria-labelledby="market-creator-earnings">
      <h2 class="market-creator-page__earnings-title h5 mb-3" id="market-creator-earnings">
        {$_('theme.profile.creator.earnings-title')}
      </h2>

      {#if earningsError}
        <ErrorAlert message={$_(messageKey(earningsError))} onretry={retry} />
      {:else}
        <div aria-busy={loading ? 'true' : 'false'}>
          {#if loading && !earnings.earnings.length}
            <LoadingBlock rows={5} />
          {:else if earnings.earnings.length}
            <div class={['table-responsive', loading && 'opacity-50']}>
              <table class="market-creator-page__table-2 table align-middle">
                <caption class="visually-hidden"
                  >{$_('theme.profile.creator.earnings-title')}</caption>
                <thead>
                  <tr>
                    <th scope="col">{$_('theme.profile.creator.col-order')}</th>
                    <th scope="col" class="text-end">{$_('theme.profile.creator.col-amount')}</th>
                    <th scope="col">{$_('theme.profile.creator.col-state')}</th>
                    <th scope="col">{$_('theme.profile.creator.col-date')}</th>
                  </tr>
                </thead>
                <tbody>
                  {#each earnings.earnings as row, index (`${row.orderNumber}-${index}`)}
                    <tr>
                      <td class="text-nowrap">#{row.orderNumber}</td>
                      <td class="text-end text-nowrap">
                        {formatMoney(row.amount, data.totals.currency)}
                      </td>
                      <td>
                        <span
                          class={['market-creator-page__badge-2', 'badge', row.badge.className]}>
                          {row.badge.key ? $_(row.badge.key) : row.badge.raw}
                        </span>
                      </td>
                      <td class="text-nowrap">{formatDate(row.createdAt)}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {:else}
            <NoContent
              icon="fa-solid fa-sack-dollar fa-3x"
              text={$_('theme.profile.creator.earnings-empty')} />
          {/if}

          <div class="mt-3">
            <Pager {page} totalPages={earnings.totalPages} onpage={onPage} />
          </div>
        </div>
      {/if}
    </section>

    {#if data.payouts.length}
      <section aria-labelledby="market-creator-payouts">
        <h2 class="market-creator-page__payouts-title h5 mb-3" id="market-creator-payouts">
          {$_('theme.profile.creator.payouts-title')}
        </h2>
        <ul class="market-creator-page__list list-group">
          {#each data.payouts as payout, index (index)}
            <li
              class="market-creator-page__item list-group-item d-flex flex-wrap align-items-center justify-content-between gap-2">
              <div>
                <span class="fw-semibold">{formatMoney(payout.amount, data.totals.currency)}</span>
                <span class="text-body-secondary">
                  &middot; {payout.method.key ? $_(payout.method.key) : payout.method.raw}
                </span>
              </div>
              <div class="d-flex align-items-center gap-2">
                {#if payout.paidAt}
                  <span class="small text-body-secondary">{formatDate(payout.paidAt)}</span>
                {/if}
                <span class={['market-creator-page__badge-3', 'badge', badgeClass(payout.badge.className)]}>
                  {payout.badge.key ? $_(payout.badge.key) : payout.badge.raw}
                </span>
              </div>
            </li>
          {/each}
        </ul>
      </section>
    {/if}
  </div>
{/if}

<script module>
  // page metadata (doc 01 section 2): the build registers this view as a page, no register.js entry
  export const view = { path: '/profile/creator', systemLayout: 'ProfileLayout' };

  import { error, redirect } from '@panomc/sdk/svelte';
  import { parseListQuery } from '../../lib/profileModel.js';
  import { CREATOR_PATH, resolveCreatorLoad } from '../../lib/subscriptionModel.js';
  import { plugin } from '@panomc/sdk/controllers';

  const SUMMARY_PATH = '/me/summary';

  export async function load(event) {
    const market = plugin('market');
    // a server load is made for its request; the browser has one host for the whole page
    const via = typeof window === 'undefined' ? { event } : undefined;
    const { call } = market.require('api', via).actions;
    const { has, loginUrl } = market.require('host', via).actions;

    const returnTo = `${event.url.pathname}${event.url.search}`;
    const { session } = await event.parent();

    // a guest returns to this page after signing in (login-return-url) or lands on the plain login
    if (!session?.user) throw redirect(302, loginUrl(returnTo));

    const { page } = parseListQuery(event.url.searchParams);
    const pills = !has('page-sidebar-id');

    const [first, summary] = await Promise.all([
      call('GET', CREATOR_PATH, { event, query: { page } }),
      pills ? call('GET', SUMMARY_PATH, { event }) : null,
    ]);

    let creator = first;
    let usedPage = page;

    // a page past the end: back to the first page
    if (!creator.ok && creator.code === 'PAGE_NOT_FOUND' && page !== 1) {
      usedPage = 1;
      creator = await call('GET', CREATOR_PATH, { event, query: { page: 1 } });
    }

    const result = resolveCreatorLoad({
      creator,
      summary,
      page: usedPage,
      features: { sidebar: has('page-sidebar-id'), meta: has('page-meta') },
    });

    if (result.redirect) throw redirect(302, loginUrl(returnTo));
    // a user who owns no creator code gets the not-found page, never an empty creator page
    if (result.notFound) throw error(404);

    result.data.pills = pills;

    return result;
  }
</script>

<script>
  import { onMount, untrack } from 'svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import CopyButton from '../../components/common/CopyButton.svelte';
  import ErrorAlert from '../../components/common/ErrorAlert.svelte';
  import LoadingBlock from '../../components/common/LoadingBlock.svelte';
  import ProfilePills from '../../components/profile/ProfilePills.svelte';
  import Pager from '../../components/store/Pager.svelte';
  import StoreStateCard from '../../components/store/StoreStateCard.svelte';
  import { messageKey } from '../../lib/errorMap.js';
  import { listSearch } from '../../lib/profileModel.js';
  import { createSequencer } from '../../lib/storeFilter.js';
  import { readEarnings } from '../../lib/subscriptionModel.js';
  import { badgeClass } from '../../lib/classes.js';

  const market = plugin('market');
  const _ = market._;
  const { call } = market.require('api').actions;
  const { formatDate, formatMoney } = market.require('format').actions;

  let { data } = $props();

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);

  let page = $state(init.page ?? 1);
  let earnings = $state(
    init.state === 'READY'
      ? { earnings: init.earnings, earningCount: init.earningCount, totalPages: init.totalPages }
      : readEarnings(null),
  );
  let earningsError = $state('');
  let loading = $state(false);

  const seq = createSequencer();

  const totalCards = $derived([
    { id: 'earned', key: 'theme.profile.creator.earned', value: data.totals?.earned },
    { id: 'paid', key: 'theme.profile.creator.paid-out', value: data.totals?.paidOut },
    { id: 'available', key: 'theme.profile.creator.available', value: data.totals?.available },
  ]);

  function writeUrl() {
    try {
      const search = listSearch({ page });

      window.history.replaceState(
        window.history.state,
        '',
        `${window.location.pathname}${search ? `?${search}` : ''}${window.location.hash}`,
      );
    } catch (e) {
      // no-op
    }
  }

  /** One request per page change; an answer that is no longer the latest request is dropped. */
  async function fetchEarnings(next) {
    page = next;
    loading = true;

    const mine = seq.beginGrid();
    let res = await call('GET', CREATOR_PATH, { query: { page: next } });
    if (!seq.isGridLatest(mine)) return;

    if (!res.ok && res.code === 'PAGE_NOT_FOUND' && next !== 1) {
      page = next = 1;
      res = await call('GET', CREATOR_PATH, { query: { page: 1 } });
      if (!seq.isGridLatest(mine)) return;
    }

    loading = false;

    if (res.ok) {
      earningsError = '';
      earnings = readEarnings(res);
      writeUrl();
    } else earningsError = res.code || 'NETWORK';
  }

  const onPage = (next) => fetchEarnings(next);
  const retry = () => fetchEarnings(page);

  onMount(() => () => seq.invalidate());
</script>
