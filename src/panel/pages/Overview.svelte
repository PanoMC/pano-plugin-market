<MarketLayout area="overview">
  {#snippet right()}
    {#if data.canStats}
      <RangeBar range={data.range} onSelect={selectRange} onCustom={() => customModal?.open()} />
    {/if}
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <AttentionAlerts
      ctx={data.ctx}
      servers={data.servers}
      health={data.health}
      reviewCount={data.reviewCount}
      orders={data.orders} />

    {#if data.canStats}
      {#if data.stats}
        <div class="row g-3">
          {#each cards as card (card.key)}
            <div class="col-12 col-md-6 col-xl">
              <StatCard
                {card}
                title={$_(`pages.overview.card.${card.key}`)}
                value={card.kind === 'count'
                  ? String(card.count)
                  : fmt.money(card.amount, currency)}
                secondary={secondaryOf(card)} />
            </div>
          {/each}
        </div>

        <div class="card">
          <CardHeader>
            <div slot="left">
              {data.canOrders ? $_('pages.overview.recent-orders') : $_('pages.overview.charts')}
            </div>
            <div slot="middle"></div>
            <CardFilters slot="right">
              {#if data.canOrders}
                <CardFiltersItem
                  button
                  active={data.view === 'table'}
                  onclick={() => setView('table')}>
                  {$_('pages.overview.view.table')}
                </CardFiltersItem>
                <CardFiltersItem
                  button
                  active={data.view === 'chart'}
                  onclick={() => setView('chart')}>
                  {$_('pages.overview.view.chart')}
                </CardFiltersItem>
              {/if}
            </CardFilters>
          </CardHeader>

          {#if data.canOrders && data.view === 'table'}
            {#if data.ordersError}
              <div class="card-body"><LoadError error={data.ordersError} /></div>
            {:else}
              <RecentOrders orders={data.orders} />
            {/if}
          {:else}
            <RevenueCharts stats={data.stats} {currency} />
          {/if}
        </div>
      {:else}
        <LoadError error={data.statsError ?? 'NETWORK_ERROR'} />
      {/if}
    {:else if alertCount === 0}
      <NoContent icon="" />
    {/if}
  {/if}
</MarketLayout>

<CustomRangeModal
  bind:this={customModal}
  from={data.from}
  to={data.to}
  onApply={(from, to) => navigate({ range: 'custom', from, to })} />

<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';
  import { loadOverviewWith } from '../components/overview/load.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadOverviewWith({ get: (options) => ApiUtil.get(options), buildQueryParams }, event);
  }
</script>

<script>
  import {
    CardHeader,
    CardFilters,
    CardFiltersItem,
    NoContent,
  } from '@panomc/sdk/components/panel';
  import { base, goto } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import AttentionAlerts from '../components/AttentionAlerts.svelte';
  import LoadError from '../components/LoadError.svelte';
  import CustomRangeModal from '../components/overview/CustomRangeModal.svelte';
  import RangeBar from '../components/overview/RangeBar.svelte';
  import RecentOrders from '../components/overview/RecentOrders.svelte';
  import RevenueCharts from '../components/overview/RevenueCharts.svelte';
  import StatCard from '../components/overview/StatCard.svelte';
  import { alertsFor } from '../components/overview/alerts.js';
  import { overviewQuery } from '../components/overview/range.js';
  import { summaryCards } from '../components/overview/summary.js';
  import { fmt } from '../utils/locale.js';

  let { data } = $props();

  let customModal = $state(null);

  const currency = $derived(data.ctx?.statsCurrency ?? data.stats?.statsCurrency ?? null);
  const cards = $derived(summaryCards(data.stats?.summary));
  // Used only for the "nothing to show" notice of users without STATS.
  const alertCount = $derived(
    alertsFor(data.health, data.servers, data.ctx, { reviewCount: data.reviewCount }).length,
  );

  // A trend (percent vs the previous period) is the secondary value of the money cards; the
  // subscription card has none.
  function secondaryOf(card) {
    if (card.kind === 'count' || !card.trend) return '';
    const value = Math.round(card.trend);
    return `${value > 0 ? '+' : ''}${value}%`;
  }

  // The URL is the source of truth; load() re-runs on every navigation.
  function navigate(next) {
    const query = overviewQuery({
      range: data.range,
      from: data.from,
      to: data.to,
      view: data.view,
      ...next,
    });
    return goto(`${base}/market${buildQueryParams(query)}`, { keepFocus: true, noScroll: true });
  }

  function selectRange(range) {
    if (range !== data.range) navigate({ range, from: null, to: null });
  }

  function setView(view) {
    if (view !== data.view) navigate({ view });
  }
</script>
