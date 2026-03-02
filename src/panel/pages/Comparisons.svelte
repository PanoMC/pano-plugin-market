<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { _ } from '../../i18n';
</script>

<MarketLayout>
  <div class="card p-5 text-center border-0 shadow-sm animate__animated animate__zoomIn">
    <div class="mb-4">
      <i class="fas fa-balance-scale fa-4x text-primary opacity-25"></i>
    </div>
    <h1 class="fw-bold mb-3">Ürün Karşılaştırma</h1>
    <p class="text-muted max-w-lg mx-auto mb-4">Müşterilerinizin ürünlerinizi özellik bazında kıyaslamasını sağlayacak güçlü bir araç geliştiriyoruz. Bu özellik bir sonraki güncellemede kullanıma sunulacaktır.</p>
    <div class="d-flex justify-content-center gap-2">
      <span class="badge text-bg-light border px-3 py-2 rounded-pill text-uppercase small ls-1 fw-bold">Yakında</span>
      <span class="badge text-bg-primary px-3 py-2 rounded-pill text-uppercase small ls-1 fw-bold">Versiyon 1.1</span>
    </div>
  </div>
</MarketLayout>

<style>
  .max-w-lg {
    max-width: 500px;
    margin-left: auto;
    margin-right: auto;
  }
  .ls-1 {
    letter-spacing: 1px;
  }
</style>
