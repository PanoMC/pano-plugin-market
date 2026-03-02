<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';
  import tooltip from '@panomc/sdk/utils/tooltip';
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

  let view = $state('table'); // table | chart
  let page = $state(1);
  let search = $state('');
  let isSearching = $state(false);

  // Persistence: Get from URL on load
  import { onMount } from 'svelte';
  onMount(() => {
    const params = new URLSearchParams(window.location.search);
    const v = params.get('view');
    if (v === 'table' || v === 'chart') {
      view = v;
    }
  });

  // Persistence: Update URL when view changes
  $effect(() => {
    const url = new URL(window.location.href);
    if (url.searchParams.get('view') !== view) {
      url.searchParams.set('view', view);
      window.history.replaceState({}, '', url);
    }
  });

  // Mock data for design
  const summaryStats = {
    weekly: { count: 154, revenue: '1,250.00 ₺', color: '#6c757d', previous: 140, trend: 'up' },
    monthly: { count: 642, revenue: '5,400.00 ₺', color: '#0dcaf0', previous: 700, trend: 'down' },
    total: { count: 12450, revenue: '125,000.00 ₺', color: '#0d6efd', previous: 11000, trend: 'up' }
  };

  const latestSales = [
    { id: 1050, player: 'Kemal', products: ['100 Kredi'], price: '10.00 ₺', date: 'Bugün, 16:10', payment: 'Kredi Kartı: Tebex' },
    { id: 1049, player: 'Ahmet', products: ['VIP+ (Limitsiz)', 'Giriş Mesajı', 'Özel Kanat', 'Efekt Paketi'], price: '350.00 ₺', date: 'Bugün, 15:55', payment: 'EFT: Havale' },
    { id: 1048, player: 'Mehmet', products: ['Kasa Anahtarı x10'], price: '45.00 ₺', date: 'Bugün, 15:30', payment: 'Mobil Ödeme' },
    { id: 1047, player: 'Okan', products: ['500 Kredi'], price: '50.00 ₺', date: 'Bugün, 14:50', payment: 'Kredi Kartı: Shopier' },
    { id: 1046, player: 'Can', products: ['VIP (Aylık)'], price: '45.00 ₺', date: 'Bugün, 14:35', payment: 'Kredi Kartı: Stripe' },
    { id: 1045, player: 'Selim', products: ['VIP+ (Aylık)'], price: '75.00 ₺', date: 'Bugün, 14:20', payment: 'EFT: Havale' },
    { id: 1044, player: 'Cihan', products: ['1000 Kredi', 'Ek Renk'], price: '110.00 ₺', date: 'Bugün, 12:45', payment: 'Kredi Kartı: Tebex' },
    { id: 1043, player: 'Eren', products: ['Kasa Anahtarı x5'], price: '25.00 ₺', date: 'Dün, 23:10', payment: 'Mobil Ödeme' },
    { id: 1042, player: 'Yavuz', products: ['VIP (Limitsiz)'], price: '250.00 ₺', date: 'Dün, 18:30', payment: 'Kredi Kartı: Shopier' },
    { id: 1041, player: 'Mert', products: ['İsim Değiştirme'], price: '15.00 ₺', date: '2 gün önce', payment: 'EFT: Havale' }
  ];

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
    page = pageNum;
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


  
  function initCharts() {
    // 1 Week Sales Chart
    if (salesWeeklyChartElement) {
      if (salesWeeklyChart) salesWeeklyChart.destroy();
      salesWeeklyChart = new Chart(salesWeeklyChartElement, {
        type: 'line',
        data: {
          labels: ['Pzt', 'Sal', 'Çar', 'Per', 'Cum', 'Cmt', 'Paz'],
          datasets: [{
            label: 'Son 7 Gün (₺)',
            data: [120, 190, 300, 250, 420, 580, 490],
            borderColor: '#0d6efd',
            backgroundColor: '#0d6efd20',
            fill: true,
            tension: 0.4
          }]
        },
        options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } } }
      });
    }

    // 1 Month Sales Chart
    if (salesMonthlyChartElement) {
      if (salesMonthlyChart) salesMonthlyChart.destroy();
      salesMonthlyChart = new Chart(salesMonthlyChartElement, {
        type: 'line',
        data: {
          labels: ['1. Hafta', '2. Hafta', '3. Hafta', '4. Hafta'],
          datasets: [{
            label: 'Son 30 Gün (₺)',
            data: [1200, 1900, 1500, 2400],
            borderColor: '#0dcaf0',
            backgroundColor: '#0dcaf020',
            fill: true,
            tension: 0.4
          }]
        },
        options: { responsive: true, maintainAspectRatio: false, plugins: { legend: { display: false } } }
      });
    }

    // En Çok Satılan Ürünler Chart
    if (topProductsChartElement) {
      if (topProductsChart) topProductsChart.destroy();
      topProductsChart = new Chart(topProductsChartElement, {
        type: 'bar',
        data: {
          labels: ['VIP+', 'Kredi', 'Kasa', 'Renk', 'İsim'],
          datasets: [{
            label: 'Satış Adedi',
            data: [45, 82, 36, 24, 12],
            backgroundColor: '#0d6efd',
            borderRadius: 6
          }]
        },
        options: { 
          responsive: true, 
          maintainAspectRatio: false, 
          plugins: { legend: { display: false } },
          scales: { y: { beginAtZero: true } }
        }
      });
    }

    // En Çok Kullanılan Ödeme Yöntemi Chart
    if (paymentMethodsChartElement) {
      if (paymentMethodsChart) paymentMethodsChart.destroy();
      paymentMethodsChart = new Chart(paymentMethodsChartElement, {
        type: 'doughnut',
        data: {
          labels: ['Kredi Kartı', 'EFT/Havale', 'Mobil Ödeme', 'Stripe'],
          datasets: [{
            data: [55, 25, 15, 5],
            backgroundColor: ['#0d6efd', '#0dcaf0', '#ffc107', '#6c757d'],
            borderWidth: 0
          }]
        },
        options: { 
          responsive: true, 
          maintainAspectRatio: false, 
          plugins: { 
            legend: { position: 'bottom', labels: { usePointStyle: true, boxWidth: 6 } } 
          },
          cutout: '70%'
        }
      });
    }
  }



  $effect(() => {
    if (weeklyChartElement && !weeklyChart) {
      weeklyChart = createSparkline(weeklyChartElement, [10, 15, 8, 12, 20, 18, 25], summaryStats.weekly.color);
    }
    if (monthlyChartElement && !monthlyChart) {
      monthlyChart = createSparkline(monthlyChartElement, [100, 120, 110, 140, 130, 160, 150, 180], summaryStats.monthly.color);
    }
    if (totalChartElement && !totalChart) {
      totalChart = createSparkline(totalChartElement, [1000, 2000, 3500, 5000, 7500, 90000, 110000, 125000], summaryStats.total.color);
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
    console.log('Current view changed:', view);
    if (view === 'chart') {
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
                <p class="card-text mb-1 small">Bu Haftanın Satışları</p>
                <div class="d-flex align-items-center gap-1 small opacity-75">
                  <i class="fas fa-arrow-{summaryStats.weekly.trend === 'up' ? 'up' : 'down'}"></i>
                  <span>{summaryStats.weekly.previous}</span>
                </div>
              </div>
              <div class="d-flex align-items-baseline gap-2">
                <h3 class="mb-0">{summaryStats.weekly.revenue}</h3>
                <span class="small">{summaryStats.weekly.count} Satış</span>
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
                <p class="card-text mb-1 small">Bu Ayın Satışları</p>
                <div class="d-flex align-items-center gap-1 small opacity-75">
                  <i class="fas fa-arrow-{summaryStats.monthly.trend === 'up' ? 'up' : 'down'}"></i>
                  <span>{summaryStats.monthly.previous}</span>
                </div>
              </div>
              <div class="d-flex align-items-baseline gap-2">
                <h3 class="mb-0">{summaryStats.monthly.revenue}</h3>
                <span class="small">{summaryStats.monthly.count} Satış</span>
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
                <p class="card-text mb-1 small">Toplam Satış</p>
                <div class="d-flex align-items-center gap-1 small opacity-75">
                  <i class="fas fa-arrow-{summaryStats.total.trend === 'up' ? 'up' : 'down'}"></i>
                  <span>{summaryStats.total.previous}</span>
                </div>
              </div>
              <div class="d-flex align-items-baseline gap-2">
                <h3 class="mb-0">{summaryStats.total.revenue}</h3>
                <span class="small">{summaryStats.total.count} Satış</span>
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
        Son Satışlar
      </div>
      <div slot="middle" style="width: 250px;">
        {#if view === 'table'}
          <SearchInput
            initialValue={search}
            searching={isSearching}
            debounceMs={500}
            onchange={(e) => {
              search = e;
              console.log('Searching for:', search);
            }} />
        {/if}
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button onclick={() => view = 'table'} active={view === 'table'}>
          Tablo
        </CardFiltersItem>
        <CardFiltersItem button onclick={() => view = 'chart'} active={view === 'chart'}>
          Grafik
        </CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if view === 'chart'}
      <div class="card-body animate__animated animate__fadeIn">
        <div class="row g-3">
          <!-- Weekly Sales -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <i class="fas fa-chart-line text-primary"></i>
              <h6 class="fw-bold mb-0">Haftalık Satış Grafiği</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={salesWeeklyChartElement}></canvas>
            </div>
          </div>

          <!-- Monthly Sales -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <i class="fas fa-calendar-alt text-info"></i>
              <h6 class="fw-bold mb-0">Aylık Satış Grafiği</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={salesMonthlyChartElement}></canvas>
            </div>
          </div>

          <!-- Top Products -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <i class="fas fa-fire text-danger"></i>
              <h6 class="fw-bold mb-0">En Çok Satılan Ürünler</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={topProductsChartElement}></canvas>
            </div>
          </div>

          <!-- Payment Methods -->
          <div class="col-md-6">
            <div class="d-flex align-items-center gap-2 mb-3">
              <i class="fas fa-credit-card text-success"></i>
              <h6 class="fw-bold mb-0">En Çok Kullanılan Ödeme Yöntemleri</h6>
            </div>
            <div class="p-3  border rounded-3" style="height: 280px;">
              <canvas bind:this={paymentMethodsChartElement}></canvas>
            </div>
          </div>
        </div>
      </div>
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle mb-0">
          <thead>
            <tr>
              <th class="ps-3" style="width: 120px;">Satış ID</th>
              <th>Oyuncu</th>
              <th>Ürün</th>
              <th>Fiyat</th>
              <th>Ödeme Yöntemi</th>
              <th class="pe-3">Tarih</th>
            </tr>
          </thead>
          <tbody>
            {#each latestSales as sale}
              <tr>
                <td class="ps-3">
                  <code 
                    class="user-select-all cursor-pointer focus-ring rounded" 
                    use:tooltip={['Kopyala']}
                    onclick={() => copyToClipboard(sale.id)}>
                    #{sale.id}
                  </code>
                </td>
                <td>
                  <a href="/players/{sale.player}" class="text-decoration-none d-flex align-items-center focus-ring rounded" use:tooltip={['Görüntüle']}>
                    <img src="https://minotar.net/avatar/{sale.player}/24" class="rounded-circle me-2" style="width: 24px; height: 24px;" alt={sale.player} />
                    <span>{sale.player}</span>
                  </a>
                </td>
                <td>
                  <div class="d-flex flex-wrap gap-1 align-items-center">
                    {#if sale.products.length > 0}
                      <a href="#" class="badge text-bg-primary text-decoration-none focus-ring rounded" use:tooltip={['Görüntüle']}>
                        {sale.products[0]}
                      </a>
                      {#if sale.products.length > 1}
                        <span 
                          class="badge text-bg-secondary cursor-help rounded-pill" 
                          data-bs-toggle="popover"
                          data-bs-trigger="hover focus"
                          data-bs-placement="top"
                          data-bs-html="true"
                          data-bs-content={sale.products.slice(1).map(p => `<span class='badge text-bg-primary me-1'>${p}</span>`).join('')}>
                          +{sale.products.length - 1}
                        </span>
                      {/if}
                    {/if}
                  </div>
                </td>
                <td>
                  <a href="#" class="badge text-bg-secondary focus-ring rounded text-decoration-none" use:tooltip={['Düzenle']}>
                    {sale.price}
                  </a>
                </td>
                <td>
                  {sale.payment}
                </td>
                <td>{sale.date}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>

      <div class="card-footer">
        <Pagination
          {page}
          totalPage={10}
          on:firstPageClick={() => onPageClick(1)}
          on:lastPageClick={() => onPageClick(10)}
          on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
      </div>
    {/if}
  </div>
</MarketLayout>
