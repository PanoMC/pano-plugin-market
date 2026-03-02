<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { _ } from '../../i18n';
  import tooltip from '@panomc/sdk/utils/tooltip';

  let settings = $state({
    storeName: 'Pano Market',
    currency: 'TRY',
    taxRate: 18,
    enableDiscounts: true,
    showStock: true,
    maintenanceMode: false
  });

  let loading = $state(false);

  async function saveSettings() {
    loading = true;
    setTimeout(() => {
      loading = false;
      alert('Ayarlar başarıyla kaydedildi!');
    }, 1000);
  }
</script>

{#snippet right()}
  <button 
    class="btn btn-primary d-flex align-items-center gap-2" 
    onclick={saveSettings}
    disabled={loading}>
    {#if loading}
      <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
    {:else}
      <i class="fas fa-check"></i>
    {/if}
    <span>Ayarları Kaydet</span>
  </button>
{/snippet}

<MarketLayout {right}>
  <div class="row g-4 animate__animated animate__fadeIn">
    <!-- General Settings -->
    <div class="col-lg-8">
      <div class="card border-0 shadow-sm mb-4">
        <div class="card-header bg-transparent border-0 pt-4 px-4">
          <h5 class="card-title mb-0">Genel Yapılandırma</h5>
        </div>
        <div class="card-body p-4 pt-2 vstack gap-4">
          <div class="row g-3">
            <div class="col-md-6">
              <label for="store-name" class="form-label fw-semibold small text-uppercase text-muted mb-2">Mağaza Adı</label>
              <input 
                id="store-name"
                type="text" 
                class="form-control bg-light border-0" 
                bind:value={settings.storeName} />
            </div>
            <div class="col-md-6">
              <label for="store-currency" class="form-label fw-semibold small text-uppercase text-muted mb-2">Para Birimi</label>
              <select id="store-currency" class="form-select bg-light border-0" bind:value={settings.currency}>
                <option value="TRY">Türk Lirası (₺)</option>
                <option value="USD">Amerikan Doları ($)</option>
                <option value="EUR">Euro (€)</option>
              </select>
            </div>
          </div>

          <div class="row g-3">
            <div class="col-md-6">
              <label for="tax-rate" class="form-label fw-semibold small text-uppercase text-muted mb-2">Vergi Oranı (%)</label>
              <input 
                id="tax-rate"
                type="number" 
                class="form-control bg-light border-0" 
                bind:value={settings.taxRate} />
            </div>
          </div>
        </div>
      </div>

      <!-- Feature Toggles -->
      <div class="card border-0 shadow-sm">
        <div class="card-header bg-transparent border-0 pt-4 px-4">
          <h5 class="card-title mb-0">Mağaza Özellikleri</h5>
        </div>
        <div class="card-body p-4 pt-2 vstack gap-3">
          <div class="d-flex align-items-center justify-content-between p-3 bg-light rounded-3 transition-all hover-bg-gray">
            <div>
              <h6 class="mb-1 fw-bold">İndirimleri Etkinleştir</h6>
              <p class="text-muted small mb-0">Ürünlerde kampanya ve indirim kuponlarını aktif eder.</p>
            </div>
            <div class="form-check form-switch fs-4">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" bind:checked={settings.enableDiscounts}>
            </div>
          </div>

          <div class="d-flex align-items-center justify-content-between p-3 bg-light rounded-3 transition-all hover-bg-gray">
            <div>
              <h6 class="mb-1 fw-bold">Stok Bilgisini Göster</h6>
              <p class="text-muted small mb-0">Sınırlı stoka sahip ürünlerin kalan miktarını müşteriye gösterir.</p>
            </div>
            <div class="form-check form-switch fs-4">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" bind:checked={settings.showStock}>
            </div>
          </div>

          <div class="d-flex align-items-center justify-content-between p-3 bg-danger bg-opacity-10 border border-danger border-opacity-25 rounded-3 transition-all">
            <div>
              <h6 class="mb-1 fw-bold text-danger">Bakım Modu</h6>
              <p class="text-muted small mb-0">Mağazayı müşterilere kapatır, sadece yöneticiler erişebilir.</p>
            </div>
            <div class="form-check form-switch fs-4">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" bind:checked={settings.maintenanceMode}>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Sidebar Info -->
    <div class="col-lg-4">
      <div class="alert alert-warning border-0 shadow-sm d-flex gap-3 mb-4">
        <i class="fas fa-exclamation-triangle mt-1"></i>
        <div>
          <span class="fw-bold d-block small">Dikkat</span>
          <span class="small">Para birimi değişikliği mevcut ürün fiyatlarını otomatik dönüştürmez. Fiyatları manuel güncellemeniz gerekir.</span>
        </div>
      </div>

      <div class="card border-0 shadow-sm bg-primary text-white overflow-hidden">
        <div class="card-body p-4 position-relative">
          <i class="fas fa-rocket fa-5x position-absolute bottom-0 end-0 opacity-10 mb-n3 me-n3"></i>
          <h5 class="fw-bold mb-3 text-white">Mağaza İstatistikleri</h5>
          <p class="x-small text-white text-opacity-75">Bu ayki toplam cironun %85'i kredi kartı ile gerçekleştirilmiştir. Ödeme yöntemlerini yapılandırmak için 'Ödemeler' sekmesini ziyaret edin.</p>
          <a href="/market" class="btn btn-white btn-sm text-primary fw-bold px-4 rounded-pill mt-2">Detaylı Analiz</a>
        </div>
      </div>
    </div>
  </div>
</MarketLayout>

<style>
  .hover-bg-gray:hover {
    background-color: #f1f3f5 !important;
  }
  .x-small {
    font-size: 0.8rem;
  }
  .cursor-pointer {
    cursor: pointer;
  }
  .transition-all {
    transition: all 0.2s ease;
  }
  .btn-white {
    background-color: white;
    color: var(--bs-primary);
    border: none;
  }
  .btn-white:hover {
    background-color: var(--bs-light);
  }
</style>
