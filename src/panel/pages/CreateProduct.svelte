<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { Editor } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';
  import tooltip from '@panomc/sdk/utils/tooltip';

  let product = $state({
    name: '',
    type: 'Eşya',
    category: -1,
    price: '',
    stock: '',
    status: 'active',
    description: '',
    image: null
  });

  let loading = $state(false);
  let isEditorEmpty = $state(true);

  // Mock categories for the select box
  const categories = [
    { id: 1, name: 'VIP Üyelikler' },
    { id: 2, name: 'Kredi Paketleri' },
    { id: 3, name: 'Kasa Anahtarları' },
    { id: 4, name: 'Özel Eşyalar' },
    { id: 5, name: 'Kozmetik Ürünler' }
  ];

  function handleImageUpload(e) {
    const file = e.target.files[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (e) => {
        product.image = e.target.result;
      };
      reader.readAsDataURL(file);
    }
  }

  function removeImage() {
    product.image = null;
  }

  async function saveProduct(publish) {
    loading = true;
    if (publish) {
      product.status = 'active';
    } else {
      product.status = 'inactive';
    }
    console.log('Saving product:', product);
    // Simulate API call
    setTimeout(() => {
      loading = false;
      alert(publish ? 'Ürün başarıyla yayınlandı!' : 'Ürün taslak olarak kaydedildi!');
    }, 1000);
  }
</script>

<MarketLayout>
  {#snippet left()}
    <a href="/panel/market/products" class="btn btn-link px-0 text-decoration-none d-flex align-items-center gap-2">
      <i class="fas fa-arrow-left"></i>
      <span>Ürünlere Dön</span>
    </a>
  {/snippet}

  {#snippet right()}
    <button
      class="btn btn-link"
      type="button"
      class:disabled={loading || !product.name}
      onclick={() => saveProduct(false)}
      use:tooltip={['Taslak Olarak Kaydet', { placement: 'bottom' }]}
      aria-label="Kaydet">
      <i class="fas fa-save"></i>
    </button>

    <button
      class="btn btn-primary d-flex align-items-center gap-2"
      type="button"
      class:disabled={loading || !product.name}
      onclick={() => saveProduct(true)}>
      {#if loading}
        <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
      {:else}
        <i class="fas fa-paper-plane"></i>
      {/if}
      <span class="d-lg-inline d-none">Yayınla</span>
    </button>
  {/snippet}

  <div class="row g-4">
    <!-- Main Content -->
    <div class="col-lg-8">
      <div class="card h-100 w-100 border-0 shadow-sm">
        <div class="card-body p-4 pt-4 d-flex flex-column gap-4">
          <div class="w-100">
            <label for="product-name" class="form-label fw-semibold small text-uppercase text-muted lh-1 mb-2">Ürün Adı</label>
            <input
              id="product-name"
              class="form-control form-control-lg fw-medium"
              type="text"
              placeholder="Ürün Adı (Örn: VIP Üyelik)"
              bind:value={product.name} />
          </div>

          <div class="w-100 flex-grow-1 d-flex flex-column min-vh-50">
            <!-- Editor -->
            <label for="product-description" class="form-label fw-semibold small text-uppercase text-muted lh-1 mb-2">Ürün Açıklaması</label>
            <div class="flex-grow-1">
              <textarea 
                id="product-description"
                class="form-control bg-light border-0" 
                rows="10" 
                bind:value={product.description}></textarea>
            </div>
            <!-- Editor End -->
          </div>
        </div>
      </div>
    </div>

    <!-- Sidebar / Options -->
    <div class="col-lg-4">
      <div class="vstack gap-4">
        
        <!-- Options Card -->
        <div class="card border-0 shadow-sm">
          <div class="card-header bg-transparent border-0 pt-4 px-4 pb-2">
            <h5 class="card-title mb-0">Ürün Ayarları</h5>
          </div>
          <div class="card-body p-4 pt-2">
            <ul class="list-group list-group-flush p-0 m-0">
              
              <!-- Category -->
              <li class="list-group-item bg-transparent px-0 py-3 border-light">
                <div class="d-flex flex-column gap-2">
                  <label for="product-category" class="form-label fw-semibold small text-muted mb-0">Kategori</label>
                  <select id="product-category" class="form-select" bind:value={product.category}>
                    <option value={-1}>Kategori Seçilmedi</option>
                    {#each categories as category (category.id)}
                      <option value={category.id}>{category.name}</option>
                    {/each}
                  </select>
                </div>
              </li>

              <!-- Type -->
              <li class="list-group-item bg-transparent px-0 py-3 border-light">
                <div class="d-flex flex-column gap-2">
                  <label for="product-type" class="form-label fw-semibold small text-muted mb-0">Ürün Tipi</label>
                  <select id="product-type" class="form-select" bind:value={product.type}>
                    <option value="Süreli">Süreli (Aylık/Yıllık)</option>
                    <option value="Cüzdan">Cüzdan / Kredi</option>
                    <option value="Eşya">Oyun İçi Eşya</option>
                    <option value="Kozmetik">Kozmetik</option>
                    <option value="Efekt">Efekt</option>
                  </select>
                </div>
              </li>

              <!-- Price & Stock -->
              <li class="list-group-item bg-transparent px-0 py-3 border-light">
                <div class="row g-2">
                  <div class="col-6">
                    <label for="product-price" class="form-label fw-semibold small text-muted mb-2">Fiyat (₺)</label>
                    <input id="product-price" type="number" class="form-control" placeholder="0.00" bind:value={product.price} />
                  </div>
                  <div class="col-6">
                    <label for="product-stock" class="form-label fw-semibold small text-muted mb-2">Stok</label>
                    <input id="product-stock" type="number" class="form-control" placeholder="Sınırsız" bind:value={product.stock} />
                  </div>
                </div>
              </li>
              
              <!-- Status -->
              <li class="list-group-item bg-transparent px-0 py-3 border-0">
                <div class="d-flex justify-content-between align-items-center">
                  <span class="fw-semibold small text-muted">Durum</span>
                  <div>
                    {#if product.status === 'active'}
                      <span class="badge text-bg-success">Aktif / Yayında</span>
                    {:else}
                      <span class="badge text-bg-secondary">Pasif / Taslak</span>
                    {/if}
                  </div>
                </div>
              </li>
            </ul>
          </div>
        </div>

        <!-- Thumbnail Card -->
        <div class="card border-0 shadow-sm overflow-hidden">
          <div class="card-header bg-transparent border-0 pt-4 px-4">
            <h5 class="card-title mb-0">Küçük Resim</h5>
          </div>
          <div class="card-body p-4 pt-2">
            {#if product.image}
              <div class="position-relative rounded overflow-hidden group border">
                <img src={product.image} alt="Ürün Önizleme" class="w-100 object-fit-cover" style="aspect-ratio: 16/9;" />
                <div class="position-absolute top-0 start-0 w-100 h-100 bg-dark bg-opacity-50 d-flex align-items-center justify-content-center opacity-0 hover-opacity-100 transition-all">
                  <button class="btn btn-danger btn-sm rounded-pill px-3" onclick={removeImage}>
                    <i class="fas fa-trash me-1"></i> Kaldır
                  </button>
                </div>
              </div>
            {:else}
              <label class="d-flex flex-column align-items-center justify-content-center border-2 border-dashed rounded p-5 cursor-pointer hover-bg-light transition-all text-muted" style="border-style: dashed !important;">
                <input type="file" class="visually-hidden" accept="image/*" onchange={handleImageUpload} />
                <i class="fas fa-image fa-3x mb-3 text-primary opacity-50"></i>
                <span class="small fw-semibold text-center">Görsel Yüklemek İçin Tıkla</span>
                <span class="x-small text-center mt-1">Önerilen: 1280x720px</span>
              </label>
            {/if}
          </div>
        </div>

      </div>
    </div>
  </div>
</MarketLayout>

<style>
  .min-vh-50 {
    min-height: 50vh;
  }
  
  /* Hover effects for image upload */
  .hover-bg-light:hover {
    background-color: var(--bs-light);
  }
  
  .hover-opacity-100 {
    opacity: 0;
    transition: opacity 0.2s ease;
  }
  
  .group:hover .hover-opacity-100 {
    opacity: 1;
  }

  .transition-all {
    transition: all 0.2s ease-in-out;
  }
  
  .cursor-pointer {
    cursor: pointer;
  }
  
  .x-small {
    font-size: 0.75rem;
  }
</style>
