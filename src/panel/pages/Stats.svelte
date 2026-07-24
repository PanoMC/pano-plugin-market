<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams }
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.stats.title');

    const view = searchParams.get('view') === 'chart' ? 'chart' : 'table';

    // The recent-sales widget's page + search live in the URL (?page= / ?search=)
    // alongside the ?view= toggle; load() reads them and fetches the orders list.
    const pageNum = parseInt(searchParams.get('page')) || 1;
    const search = searchParams.get('search');

    const fetchOrders = (p) =>
      ApiUtil.get({
        path:
          '/api/panel/market/orders' +
          buildQueryParams({ page: p === 1 ? null : p, search }),
        request: event
      });

    let effectivePage = pageNum;
    let [statsRes, ordersRes] = await Promise.all([
      ApiUtil.get({ path: '/api/panel/market/stats', request: event }),
      fetchOrders(pageNum)
    ]);

    // A stale ?page= (bookmark / back button) can point past the last page; fall
    // back to page 1 with the same search instead of showing an empty widget.
    if (ordersRes?.error === 'PAGE_NOT_FOUND' && pageNum > 1) {
      effectivePage = 1;
      ordersRes = await fetchOrders(1);
    }

    return {
      data: {
        view,
        page: effectivePage,
        search: search || '',
        stats: statsRes && !statsRes.error ? statsRes : null,
        orders: ordersRes && !ordersRes.error ? ordersRes.orders || [] : [],
        totalPage: ordersRes && !ordersRes.error ? ordersRes.totalPage || 1 : 1
      }
    };
  }
</script>

<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { _ } from '../../i18n';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput, NoContent, Date as DateComponent } from '@panomc/sdk/components/panel';
  import { base, page as pageStore, goto } from '@panomc/sdk/svelte';
  import {
    Chart,
    LineController,
    LineElement,
    PointElement,
    LinearScale,
    CategoryScale,
    Filler,
    BarController,
    BarElement,
    DoughnutController,
    ArcElement
  } from 'chart.js';

  Chart.register(
    LineController,
    LineElement,
    PointElement,
    LinearScale,
    CategoryScale,
    Filler,
    BarController,
    BarElement,
    DoughnutController,
    ArcElement
  );

  let { data } = $props();

  // 'table' | 'chart' — the URL (?view=) is the source of truth; load() parses it.
  let view = $derived(data.view);

  // Recent-sales page + search are read from the URL that produced load()'s data,
  // so the address bar stays deep-linkable and the back button is correct.
  let page = $derived(data.page || 1);
  let search = $derived(data.search || '');
  let isSearching = $state(false);

  // Stats summary + chart data from GET /stats (loaded in load())
  const stats = $derived(data.stats); // { summary, charts, statsCurrency, statsCurrencySymbol }

  // STATS-currency symbol from the /stats response; all revenue values from the
  // backend are already converted into the stats currency (do NOT convert again).
  const statsCurrencySymbol = $derived(stats?.statsCurrencySymbol || '');

  // Latest sales table (reuses GET /orders): data comes from load() and
  // re-derives whenever it re-runs; search/pagination navigate via the URL.
  let orders = $derived(data.orders);
  let totalPage = $derived(data.totalPage);

  // Recent-sales rows carry each order's OWN currency (raw, un-converted amounts),
  // so they display with the order's currency symbol — NOT the stats symbol, which
  // is only correct for the backend-converted summary/chart figures.
  const CURRENCY_SYMBOLS = { TRY: '₺', USD: '$', EUR: '€', GBP: '£' };

  const COLORS = { weekly: '#6c757d', monthly: '#0dcaf0', total: '#0d6efd' };
  const PAYMENT_PALETTE = ['#0d6efd', '#0dcaf0', '#ffc107', '#6c757d', '#198754', '#dc3545', '#6610f2'];

  // backend OrderStatus name -> badge display
  const ORDER_STATUS = {
    COMPLETED: { label: 'pages.stats.order-status.completed', cls: 'text-bg-success' },
    REFUNDED: { label: 'pages.stats.order-status.refunded', cls: 'text-bg-danger' },
    PENDING: { label: 'pages.stats.order-status.pending', cls: 'text-bg-warning' },
    FAILED: { label: 'pages.stats.order-status.failed', cls: 'text-bg-danger' }
  };

  function formatMoney(value, symbol) {
    const formatted = (Number(value) || 0).toLocaleString('tr-TR', {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2
    });
    return symbol ? `${formatted} ${symbol}` : formatted;
  }

  const summaryStats = $derived.by(() => {
    const s = stats?.summary;
    const block = (b, color) => ({
      count: b?.count ?? 0,
      revenue: formatMoney(b?.revenue ?? 0, statsCurrencySymbol),
      color,
      change: Math.abs(Math.round(b?.trend ?? 0)),
      trend: (b?.trend ?? 0) >= 0 ? 'up' : 'down',
      spark: b?.spark ?? []
    });
    return {
      weekly: block(s?.weekly, COLORS.weekly),
      monthly: block(s?.monthly, COLORS.monthly),
      total: block(s?.total, COLORS.total)
    };
  });

  // View toggle goes through the router (replaceState keeps history clean and
  // $page in sync — raw window.history.replaceState would desync SvelteKit);
  // the default 'table' keeps the URL param-free.
  function setView(next) {
    if (next === view) return;
    const params = $pageStore.url.searchParams;
    goto(
      $pageStore.url.pathname +
        buildQueryParams({
          view: next === 'chart' ? 'chart' : null,
          // Preserve the recent-sales paging/search so toggling views doesn't
          // silently reset the list back to page 1 with no search.
          page: params.get('page'),
          search: params.get('search')
        }),
      { replaceState: true, keepFocus: true, noscroll: true }
    );
  }

  // Navigate to the same route with the recent-sales page/search encoded in the
  // URL (the ?view= toggle is preserved) so load() re-runs with the new params.
  // The panel host remounts the plugin page on every load() re-run ({#key data});
  // that remount is the accepted cost of URL-driven navigation here.
  async function navigate({ page: pageNum = page, search: searchVal = search } = {}) {
    isSearching = true;

    const queryParams = buildQueryParams({
      view: view === 'chart' ? 'chart' : null,
      page: pageNum && pageNum !== 1 ? pageNum : null,
      search: searchVal ? searchVal.trim() || null : null
    });

    await goto(`${base}/market${queryParams}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true
    });
  }

  // Re-initialize Bootstrap popovers whenever the rows change (load() re-run).
  $effect(() => {
    const unused = orders;
    if (typeof window !== 'undefined' && window.bootstrap) {
      let popovers = [];
      const timer = setTimeout(() => {
        const popoverTriggerList = document.querySelectorAll('[data-bs-toggle="popover"]');
        popovers = [...popoverTriggerList].map((el) => new window.bootstrap.Popover(el));
      }, 50);
      return () => {
        clearTimeout(timer);
        popovers.forEach((p) => p.dispose());
      };
    }
  });

  let weeklyChartElement = $state();
  let monthlyChartElement = $state();
  let totalChartElement = $state();

  let salesWeeklyChartElement = $state();
  let salesMonthlyChartElement = $state();
  let topProductsChartElement = $state();
  let paymentMethodsChartElement = $state();

  let weeklyChart, monthlyChart, totalChart;
  let salesWeeklyChart, salesMonthlyChart, topProductsChart, paymentMethodsChart;

  function onPageClick(pageNum) {
    navigate({ page: pageNum });
  }

  function copyToClipboard(text) {
    navigator.clipboard.writeText(text);
  }

  function createSparkline(element, data, color) {
    if (!element) return null;
    return new Chart(element, {
      type: 'line',
      data: {
        labels: data.map((_, i) => i),
        datasets: [{
          data: data,
          borderColor: color,
          borderWidth: 2,
          pointRadius: 0,
          tension: 0.4,
          fill: true,
          backgroundColor: color + '15' // Subtle fill
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        plugins: { legend: { display: false }, tooltip: { enabled: false } },
        scales: {
          x: { display: false },
          y: { display: false }
        }
      }
    });
  }

  // ISO week key "YYYYWW" -> "WW. Hafta"
  function weekLabel(key) {
    return $_('pages.stats.week-label', { values: { week: parseInt(String(key).slice(-2), 10) } });
  }

  function initCharts() {
    const charts = stats?.charts;
    if (!charts) return;

    // 1 Week Sales Chart (last 8 ISO weeks)
    if (salesWeeklyChartElement) {
      if (salesWeeklyChart) salesWeeklyChart.destroy();
      salesWeeklyChart = new Chart(salesWeeklyChartElement, {
        type: 'line',
        data: {
          labels: (charts.weeklyRevenue?.labels || []).map(weekLabel),
          datasets: [{
            label: $_('pages.stats.chart.weekly-revenue'),
            data: charts.weeklyRevenue?.values || [],
            borderColor: '#0d6efd',
            backgroundColor: '#0d6efd20',
            fill: true,
            tension: 0.4
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: { callbacks: { label: (ctx) => formatMoney(ctx.parsed.y, statsCurrencySymbol) } }
          }
        }
      });
    }

    // 1 Month Sales Chart (last 6 months)
    if (salesMonthlyChartElement) {
      if (salesMonthlyChart) salesMonthlyChart.destroy();
      salesMonthlyChart = new Chart(salesMonthlyChartElement, {
        type: 'line',
        data: {
          labels: charts.monthlyRevenue?.labels || [],
          datasets: [{
            label: $_('pages.stats.chart.monthly-revenue'),
            data: charts.monthlyRevenue?.values || [],
            borderColor: '#0dcaf0',
            backgroundColor: '#0dcaf020',
            fill: true,
            tension: 0.4
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: { callbacks: { label: (ctx) => formatMoney(ctx.parsed.y, statsCurrencySymbol) } }
          }
        }
      });
    }

    // En Çok Satılan Ürünler Chart (revenue per product)
    if (topProductsChartElement) {
      if (topProductsChart) topProductsChart.destroy();
      topProductsChart = new Chart(topProductsChartElement, {
        type: 'bar',
        data: {
          labels: charts.topProducts?.labels || [],
          datasets: [{
            label: $_('pages.stats.chart.revenue'),
            data: charts.topProducts?.values || [],
            backgroundColor: '#0d6efd',
            borderRadius: 6
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false },
            tooltip: { callbacks: { label: (ctx) => formatMoney(ctx.parsed.y, statsCurrencySymbol) } }
          },
          scales: { y: { beginAtZero: true } }
        }
      });
    }

    // En Çok Kullanılan Ödeme Yöntemi Chart (order counts)
    if (paymentMethodsChartElement) {
      if (paymentMethodsChart) paymentMethodsChart.destroy();
      const pmLabels = charts.paymentMethods?.labels || [];
      paymentMethodsChart = new Chart(paymentMethodsChartElement, {
        type: 'doughnut',
        data: {
          labels: pmLabels,
          datasets: [{
            data: charts.paymentMethods?.values || [],
            backgroundColor: pmLabels.map((_, i) => PAYMENT_PALETTE[i % PAYMENT_PALETTE.length]),
            borderWidth: 0
          }]
        },
        options: {
          responsive: true,
          maintainAspectRatio: false,
          plugins: {
            legend: { display: false }
          },
          cutout: '70%'
        }
      });
    }
  }

  $effect(() => {
    if (weeklyChartElement && !weeklyChart && summaryStats.weekly.spark.length) {
      weeklyChart = createSparkline(weeklyChartElement, summaryStats.weekly.spark, COLORS.weekly);
    }
    if (monthlyChartElement && !monthlyChart && summaryStats.monthly.spark.length) {
      monthlyChart = createSparkline(monthlyChartElement, summaryStats.monthly.spark, COLORS.monthly);
    }
    if (totalChartElement && !totalChart && summaryStats.total.spark.length) {
      totalChart = createSparkline(totalChartElement, summaryStats.total.spark, COLORS.total);
    }

    return () => {
      weeklyChart?.destroy();
      monthlyChart?.destroy();
      totalChart?.destroy();
      weeklyChart = null;
      monthlyChart = null;
      totalChart = null;
    };
  });

  $effect(() => {
    if (view === 'chart' && stats) {
      // Use requestAnimationFrame to ensure DOM is updated
      const raf = requestAnimationFrame(() => {
        if (salesWeeklyChartElement || salesMonthlyChartElement || topProductsChartElement || paymentMethodsChartElement) {
          initCharts();
        }
      });
      return () => cancelAnimationFrame(raf);
    }
    return () => {
      salesWeeklyChart?.destroy();
      salesMonthlyChart?.destroy();
      topProductsChart?.destroy();
      paymentMethodsChart?.destroy();
      salesWeeklyChart = null;
      salesMonthlyChart = null;
      topProductsChart = null;
      paymentMethodsChart = null;
    };
  });
</script>

<MarketLayout>
  <!-- Summary Cards -->
  <div class="row g-3 justify-content-between animate__animated animate__slideInUp">
    <!-- Weekly Sales -->
    <div class="col-lg-4">
      <div class="card text-bg-secondary h-100 overflow-hidden">
        <div class="card-body p-0 d-flex flex-column">
           <div class="p-3 pb-2">
              <div class="d-flex justify-content-between align-items-start">
                <p class="card-text mb-1 small">{$_('pages.stats.summary.weekly-title')}</p>
                <div class="d-flex align-items-center gap-1 small opacity-75">
                  <i class="fas fa-arrow-{summaryStats.weekly.trend === 'up' ? 'up' : 'down'}"></i>
                  <span>%{summaryStats.weekly.change}</span>
                </div>
              </div>
              <div class="d-flex align-items-baseline gap-2">
                <h3 class="mb-0">{summaryStats.weekly.revenue}</h3>
                <span class="small">{$_('pages.stats.summary.sales-count', { values: { count: summaryStats.weekly.count } })}</span>
              </div>
           </div>
          <div style="height: 60px; min-height: 60px; width: 100%; margin-top: auto;">
            <canvas bind:this={weeklyChartElement}></canvas>
          </div>
        </div>
      </div>
    </div>

    <!-- Monthly Sales -->
    <div class="col-lg-4">
      <div class="card text-bg-info h-100 overflow-hidden text-white">
        <div class="card-body p-0 d-flex flex-column">
           <div class="p-3 pb-2">
              <div class="d-flex justify-content-between align-items-start">
                <p class="card-text mb-1 small">{$_('pages.stats.summary.monthly-title')}</p>
                <div class="d-flex align-items-center gap-1 small opacity-75">
                  <i class="fas fa-arrow-{summaryStats.monthly.trend === 'up' ? 'up' : 'down'}"></i>
                  <span>%{summaryStats.monthly.change}</span>
                </div>
              </div>
              <div class="d-flex align-items-baseline gap-2">
                <h3 class="mb-0">{summaryStats.monthly.revenue}</h3>
                <span class="small">{$_('pages.stats.summary.sales-count', { values: { count: summaryStats.monthly.count } })}</span>
              </div>
           </div>
          <div style="height: 60px; min-height: 60px; width: 100%; margin-top: auto;">
            <canvas bind:this={monthlyChartElement}></canvas>
          </div>
        </div>
      </div>
    </div>

    <!-- Total Sales -->
    <div class="col-lg-4">
      <div class="card text-bg-primary h-100 overflow-hidden">
        <div class="card-body p-0 d-flex flex-column">
           <div class="p-3 pb-2">
              <div class="d-flex justify-content-between align-items-start">
                <p class="card-text mb-1 small">{$_('pages.stats.summary.total-title')}</p>
                <div class="d-flex align-items-center gap-1 small opacity-75">
                  <i class="fas fa-arrow-{summaryStats.total.trend === 'up' ? 'up' : 'down'}"></i>
                  <span>%{summaryStats.total.change}</span>
                </div>
              </div>
              <div class="d-flex align-items-baseline gap-2">
                <h3 class="mb-0">{summaryStats.total.revenue}</h3>
                <span class="small">{$_('pages.stats.summary.sales-count', { values: { count: summaryStats.total.count } })}</span>
              </div>
           </div>
          <div style="height: 60px; min-height: 60px; width: 100%; margin-top: auto;">
            <canvas bind:this={totalChartElement}></canvas>
          </div>
        </div>
      </div>
    </div>
  </div>

  <!-- Son Satışlar Section -->
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.stats.recent-sales')}
      </div>
      <div slot="middle" style="width: 250px;">
        {#if view === 'table'}
          <SearchInput
            initialValue={search}
            searching={isSearching}
            debounceMs={500}
            onchange={(e) => navigate({ search: e, page: 1 })} />
        {/if}
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button onclick={() => setView('table')} active={view === 'table'}>
          {$_('pages.stats.view.table')}
        </CardFiltersItem>
        <CardFiltersItem button onclick={() => setView('chart')} active={view === 'chart'}>
          {$_('pages.stats.view.chart')}
        </CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if view === 'chart'}
      <div class="card-body animate__animated animate__fadeIn">
        <div class="row g-3">
          <!-- Weekly Sales -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <h6 class="mb-0">{$_('pages.stats.chart.weekly-heading')}</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={salesWeeklyChartElement}></canvas>
            </div>
          </div>

          <!-- Monthly Sales -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <h6 class="mb-0">{$_('pages.stats.chart.monthly-heading')}</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={salesMonthlyChartElement}></canvas>
            </div>
          </div>

          <!-- Top Products -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <h6 class="mb-0">{$_('pages.stats.chart.top-products-heading')}</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={topProductsChartElement}></canvas>
            </div>
          </div>

          <!-- Payment Methods -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <h6 class="mb-0">{$_('pages.stats.chart.payment-methods-heading')}</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={paymentMethodsChartElement}></canvas>
            </div>
          </div>
        </div>
      </div>
    {:else}
      {#if orders.length === 0}
        <NoContent />
      {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle mb-0">
          <thead>
            <tr>
              <th class="ps-3" style="width: 120px;">{$_('pages.stats.table.sale-id')}</th>
              <th>{$_('pages.stats.table.player')}</th>
              <th>{$_('pages.stats.table.product')}</th>
              <th>{$_('pages.stats.table.price')}</th>
              <th>{$_('pages.stats.table.payment-method')}</th>
              <th>{$_('common.status')}</th>
              <th class="pe-3">{$_('pages.stats.table.date')}</th>
            </tr>
          </thead>
          <tbody>
            {#each orders as order (order.id)}
              {@const products = (order.items || []).map((i) => i.productName)}
              <tr>
                <td class="ps-3">
                  <button
                    type="button"
                    class="btn btn-link p-0 text-decoration-none font-monospace user-select-all cursor-pointer focus-ring rounded border-0"
                    title={$_('pages.stats.copy')}
                    onclick={() => copyToClipboard(order.id)}>
                    #{order.id}
                  </button>
                </td>
                <td>
                  {#if order.playerUsername}
                    <a href="{base}/players/detail/{order.playerUsername}" class="text-decoration-none d-flex align-items-center focus-ring rounded" title={$_('pages.stats.view-title')}>
                      <img src="https://minotar.net/avatar/{order.playerUsername}/24" class="rounded-circle me-2" style="width: 24px; height: 24px;" alt={order.playerUsername} />
                      <span>{order.playerUsername}</span>
                    </a>
                  {:else}
                    <span class="text-body-secondary">-</span>
                  {/if}
                </td>
                <td>
                  <div class="d-flex flex-wrap gap-1 align-items-center">
                    {#if products.length > 0}
                      <span class="badge text-bg-primary focus-ring rounded" title={$_('pages.stats.view-title')}>
                        {products[0]}
                      </span>
                      {#if products.length > 1}
                        <span
                          class="badge text-bg-secondary cursor-help rounded-pill"
                          data-bs-toggle="popover"
                          data-bs-trigger="hover focus"
                          data-bs-placement="top"
                          data-bs-html="true"
                          data-bs-content={products.slice(1).map(p => `<span class='badge text-bg-primary me-1'>${p}</span>`).join('')}>
                          +{products.length - 1}
                        </span>
                      {/if}
                    {/if}
                  </div>
                </td>
                <td>
                  <span class="badge text-bg-secondary focus-ring rounded" title={$_('common.edit')}>
                    {formatMoney(order.totalPrice, CURRENCY_SYMBOLS[order.currency] || '')}
                  </span>
                </td>
                <td>
                  {order.paymentLabel || '-'}
                </td>
                <td>
                  {#if ORDER_STATUS[order.status]}
                    <span class="badge {ORDER_STATUS[order.status].cls}">{$_(ORDER_STATUS[order.status].label)}</span>
                  {:else}
                    <span class="badge text-bg-secondary">{order.status}</span>
                  {/if}
                </td>
                <td class="pe-3"><DateComponent time={order.createdAt} relativeFormat={true} /></td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>

      <div class="card-footer">
        <Pagination
          {page}
          {totalPage}
          on:firstPageClick={() => onPageClick(1)}
          on:lastPageClick={() => onPageClick(totalPage)}
          on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
      </div>
      {/if}
    {/if}
  </div>
</MarketLayout>
