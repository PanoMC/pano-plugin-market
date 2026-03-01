<script>
  import { onMount } from 'svelte';
  import { _ } from '../../main';
  import MarketNav from '../components/MarketNav.svelte';
  import { CardHeader } from '@panomc/sdk/components/panel';

  let chartCanvas;
  let chartInstance;

  function initChart() {
    if (!window.Chart || !chartCanvas) return;
    if (chartInstance) chartInstance.destroy();
    
    chartInstance = new Chart(chartCanvas, {
      type: 'line',
      data: {
        labels: ['Oca', 'Şub', 'Mar', 'Nis', 'May', 'Haz'],
        datasets: [{
          label: 'Satışlar',
          data: [12, 19, 3, 5, 2, 3],
          borderColor: '#0d6efd',
          tension: 0.1,
          borderWidth: 2
        }, {
          label: 'Gelir (₺)',
          data: [1200, 1900, 300, 500, 200, 300],
          borderColor: '#198754',
          tension: 0.1,
          borderWidth: 2
        }]
      },
      options: {
        responsive: true,
        plugins: {
          legend: {
            position: 'top',
          },
          title: {
            display: false
          }
        },
        scales: {
          y: {
            beginAtZero: true
          }
        }
      }
    });
  }

  onMount(() => {
    // Chart js loaded synchronously from window or async script 
    setTimeout(initChart, 100);
  });
</script>

<svelte:head>
  <script src="https://cdn.jsdelivr.net/npm/chart.js" on:load={initChart}></script>
</svelte:head>

<div class="container vstack gap-3">
  <MarketNav />

  <div class="row">
    <div class="col-12 col-xl-8 mb-4 mb-xl-0">
      <div class="card h-100">
        <CardHeader>
          <div slot="left">Aylık Satış ve Gelir Özeti</div>
        </CardHeader>
        <div class="card-body">
          <canvas bind:this={chartCanvas}></canvas>
        </div>
      </div>
    </div>
    <div class="col-12 col-xl-4">
      <div class="card mb-3 bg-primary text-white border-0">
        <div class="card-body text-center py-4">
          <h5 class="fw-normal mb-2 text-white-50">Toplam Satış</h5>
          <h1 class="display-5 fw-bold mb-0">124</h1>
        </div>
      </div>
      <div class="card bg-success text-white border-0">
        <div class="card-body text-center py-4">
          <h5 class="fw-normal mb-2 text-white-50">Toplam Gelir</h5>
          <h1 class="display-5 fw-bold mb-0">18,450 ₺</h1>
        </div>
      </div>
    </div>
  </div>
</div>