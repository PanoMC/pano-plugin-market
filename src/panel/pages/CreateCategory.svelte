<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { Editor } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';
  import tooltip from '@panomc/sdk/utils/tooltip';

  let category = $state({
    name: '',
    description: '',
    status: 'active',
    order: 0,
    image: null
  });

  let loading = $state(false);

  function handleImageUpload(e) {
    const file = e.target.files[0];
    if (file) {
      const reader = new FileReader();
      reader.onload = (e) => {
        category.image = e.target.result;
      };
      reader.readAsDataURL(file);
    }
  }

  function removeImage() {
    category.image = null;
  }

  async function saveCategory() {
    loading = true;
    console.log('Saving category:', category);
    // Simulate API call
    setTimeout(() => {
      loading = false;
      alert('Kategori başarıyla oluşturuldu!');
    }, 1000);
  }
</script>

<MarketLayout>
  {#snippet left()}
    <a href="/panel/market/categories" class="btn btn-link px-0 text-decoration-none d-flex align-items-center gap-2">
      <i class="fas fa-arrow-left"></i>
      <span>Kategorilere Dön</span>
    </a>
  {/snippet}

  {#snippet right()}
    <button 
      class="btn btn-primary d-flex align-items-center gap-2" 
      onclick={saveCategory}
      disabled={loading || !category.name}>
      {#if loading}
        <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
      {:else}
        <i class="fas fa-save"></i>
      {/if}
      <span>Kaydet</span>
    </button>
  {/snippet}

  <div class="row g-4">
    <!-- Main Content -->
    <div class="col-lg-8">
      <div class="card h-100 border-0 shadow-sm">
        <div class="card-header bg-transparent border-0 pt-4 px-4">
          <h5 class="card-title mb-0">Genel Bilgiler</h5>
        </div>
        <div class="card-body p-4 pt-2 d-flex flex-column gap-4">
          <div class="form-group">
            <label for="category-name" class="form-label fw-semibold small text-uppercase text-muted lh-1 mb-2">Kategori Adı</label>
            <input 
              id="category-name"
              type="text" 
              class="form-control form-control-lg" 
              placeholder="Örn: VIP Üyelikler"
              bind:value={category.name} />
          </div>

          <div class="form-group flex-grow-1 d-flex flex-column">
            <label for="category-description" class="form-label fw-semibold small text-uppercase text-muted lh-1 mb-2">Açıklama</label>
            <div class="flex-grow-1 min-vh-50">
              <Editor id="category-description" bind:content={category.description} />
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Sidebar -->
    <div class="col-lg-4">
      <div class="vstack gap-4">
        <!-- Status & Visibility -->
        <div class="card border-0 shadow-sm">
          <div class="card-header bg-transparent border-0 pt-4 px-4">
            <h5 class="card-title mb-0">Ayarlar</h5>
          </div>
          <div class="card-body p-4 pt-2 vstack gap-3">
            <div class="form-group">
              <label for="category-status" class="form-label fw-semibold small text-uppercase text-muted lh-1 mb-2">Durum</label>
              <select id="category-status" class="form-select" bind:value={category.status}>
                <option value="active">Aktif</option>
                <option value="passive">Pasif</option>
                <option value="hidden">Gizli</option>
              </select>
            </div>

            <div class="form-group">
              <label for="category-order" class="form-label fw-semibold small text-uppercase text-muted lh-1 mb-2">Sıralama</label>
              <input 
                id="category-order"
                type="number" 
                class="form-control" 
                bind:value={category.order} />
              <div class="form-text small">Mağazada görünecek sıra (Küçükten büyüğe).</div>
            </div>
          </div>
        </div>

        <!-- Category Image -->
        <div class="card border-0 shadow-sm overflow-hidden">
          <div class="card-header bg-transparent border-0 pt-4 px-4">
            <h5 class="card-title mb-0">Kategori Görseli</h5>
          </div>
          <div class="card-body p-4 pt-2 vstack gap-3">
            {#if category.image}
              <div class="position-relative rounded overflow-hidden group border">
                <img src={category.image} alt="Kategori Önizleme" class="w-100 object-fit-cover" style="aspect-ratio: 16/9;" />
                <div class="position-absolute top-0 start-0 w-100 h-100 bg-dark bg-opacity-50 d-flex align-items-center justify-content-center opacity-0 hover-opacity-100 transition-all">
                  <button class="btn btn-danger btn-sm rounded-pill px-3" onclick={removeImage}>
                    <i class="fas fa-trash me-1"></i> Kaldır
                  </button>
                </div>
              </div>
            {:else}
              <label class="d-flex flex-column align-items-center justify-content-center border-2 border-dashed rounded p-5 cursor-pointer hover-bg-light transition-all text-muted" style="border-style: dashed !important;">
                <input type="file" class="visually-hidden" accept="image/*" onchange={handleImageUpload} />
                <i class="fas fa-cloud-upload-alt fa-2x mb-2 text-primary opacity-50"></i>
                <span class="small fw-semibold">Görsel Yüklemek İçin Tıkla</span>
                <span class="x-small">PNG, JPG (Max 2MB)</span>
              </label>
            {/if}
          </div>
        </div>

        <!-- Additional Options Hook -->
        <div class="alert alert-info border-0 shadow-sm d-flex gap-3 m-0">
          <i class="fas fa-info-circle mt-1"></i>
          <span class="small">Kategoriye eklediğiniz ürünler, mağaza sayfasında otomatik olarak listelenir.</span>
        </div>
      </div>
    </div>
  </div>
</MarketLayout>

<style>
  .min-vh-50 {
    min-height: 50vh;
  }
  
  /* Hover effects for custom designs */
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
