<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { onMount } from 'svelte';

  let isEdit = $state(false);
  let loading = $state(false);
  
  // Model states
  let comparison = $state({
    name: '',
    status: 'active',
    priority: 0,
    selectedProducts: [null, null], // Starts with two empty column slots by default for clean UX
    features: [
      { id: 1, name: 'Özel Giriş Mesajı' },
      { id: 2, name: 'Uçma Yetkisi' },
      { id: 3, name: 'Renk Değiştirme' }
    ],
    cellValues: {}
  });

  const products = [
    { id: 1, name: 'VIP Üyelik (Aylık)' },
    { id: 2, name: 'VIP+ Üyelik (Aylık)' },
    { id: 3, name: 'MVP Üyelik (Aylık)' },
    { id: 4, name: 'MVP+ Üyelik (Aylık)' },
    { id: 5, name: '1000 Kredi' },
    { id: 6, name: 'Kasa Anahtarı x10' }
  ];

  function toggleProduct(id) {
    if (comparison.selectedProducts.includes(id)) {
      comparison.selectedProducts = comparison.selectedProducts.filter(pId => pId !== id);
    } else {
      comparison.selectedProducts = [...comparison.selectedProducts, id];
    }
  }

  function addColumn() {
    comparison.selectedProducts = [...comparison.selectedProducts, null];
  }

  function removeColumn(index) {
    comparison.selectedProducts = comparison.selectedProducts.filter((_, i) => i !== index);
  }

  function updateColumnProduct(index, productId) {
    const updated = [...comparison.selectedProducts];
    updated[index] = productId ? Number(productId) : null;
    comparison.selectedProducts = updated;
  }

  function addFeature() {
    comparison.features = [...comparison.features, { id: Date.now(), name: '' }];
  }

  function removeFeature(id) {
    comparison.features = comparison.features.filter(f => f.id !== id);
  }

  onMount(() => {
    const params = new URLSearchParams(window.location.search);
    const id = params.get('id');
    if (id) {
      isEdit = true;
      if (id === '1') {
        comparison.name = 'VIP Paketleri Karşılaştırması';
        comparison.selectedProducts = [1, 2, 3, 4];
        comparison.status = 'active';
        comparison.priority = 10;
        comparison.cellValues = {
          '1-1': 'yes', '1-2': 'yes', '1-3': 'yes', '1-4': 'yes',
          '2-1': 'no', '2-2': 'yes', '2-3': 'yes', '2-4': 'yes',
          '3-1': 'no', '3-2': 'no', '3-3': 'yes', '3-4': 'yes',
        };
      } else if (id === '2') {
        comparison.name = 'Kredi Paketleri Karşılaştırması';
        comparison.selectedProducts = [5, null];
        comparison.status = 'active';
        comparison.priority = 5;
      } else if (id === '3') {
        comparison.name = 'Kasa Anahtarları';
        comparison.selectedProducts = [6, null];
        comparison.status = 'inactive';
        comparison.priority = 0;
      }
    }
  });

  function handleSave() {
    loading = true;
    setTimeout(() => {
      loading = false;
      alert(isEdit ? 'Karşılaştırma başarıyla güncellendi!' : 'Karşılaştırma başarıyla oluşturuldu!');
      window.location.href = '/panel/market/comparisons';
    }, 800);
  }
</script>

<MarketLayout>
  {#snippet left()}
    <div class="d-flex align-items-center gap-4">
      <a href="/panel/market/comparisons" class="btn btn-link text-decoration-none p-0">
        <i class="fas fa-arrow-left"></i>
        <span class="ms-2">Karşılaştırmalar</span>
      </a>
    </div>
  {/snippet}

  {#snippet right()}
    <div class="hstack gap-1">
      <button 
        class="btn btn-secondary ms-2 shadow-sm" 
        onclick={handleSave} 
        disabled={loading || !comparison.name}>
        {#if loading}
          <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
        {:else}
          <i class="fas fa-save"></i>
        {/if}
        <span class="d-lg-inline d-none ms-2">{isEdit ? 'Kaydet' : 'Oluştur'}</span>
      </button>
    </div>
  {/snippet}

  <section class="row g-3">
    <!-- Ana Sütun (Table Matrix Builder) -->
    <div class="col-lg-8">
      <div class="vstack gap-3 animate__animated animate__fadeIn">
        <!-- Genel Bilgiler Kartı (Comparison Title) -->
        <div class="card w-100">
          <div class="card-body p-4">
            <div class="form-floating">
              <input
                type="text"
                class="form-control"
                id="comparisonName"
                placeholder="Karşılaştırma Başlığı"
                bind:value={comparison.name} />
              <label for="comparisonName">Karşılaştırma Başlığı</label>
            </div>
          </div>
        </div>

        <!-- İnteraktif Karşılaştırma Matrisi -->
        <div class="card w-100">
          <div class="card-body p-4">
            <div class="table-responsive">
              <table class="table table-bordered align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col" style="min-width: 250px;">Özellik Adı</th>
                    {#each comparison.selectedProducts as prodId, colIndex}
                      <th scope="col" class="text-center" style="min-width: 200px;">
                        <div class="d-flex align-items-center gap-1">
                          <select 
                            class="form-select text-truncate" 
                            value={prodId || ''} 
                            onchange={(e) => updateColumnProduct(colIndex, e.target.value)}>
                            <option value="">-- Ürün Seçin --</option>
                            {#each products as prod}
                              <option value={prod.id} disabled={comparison.selectedProducts.includes(prod.id) && prod.id !== prodId}>
                                {prod.name}
                              </option>
                            {/each}
                          </select>
                          <button 
                            type="button" 
                            class="btn-close" 
                            onclick={() => removeColumn(colIndex)}
                            title="Sütunu Sil"
                            aria-label="Sütunu Sil"></button>
                        </div>
                      </th>
                    {/each}
                    <th scope="col" class="text-center" style="width: 100px; min-width: 100px;">
                      <button 
                        type="button" 
                        class="btn btn-primary d-flex align-items-center justify-content-center mx-auto" 
                        onclick={addColumn}
                        title="Ürün Sütunu Ekle">
                        <i class="fas fa-plus"></i>
                      </button>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {#each comparison.features as feature (feature.id)}
                    <tr>
                      <td>
                        <input 
                          type="text" 
                          class="form-control" 
                          bind:value={feature.name} 
                          placeholder="Örn: Uçma Yetkisi" />
                      </td>
                      {#each comparison.selectedProducts as prodId}
                        <td class="text-center">
                          {#if prodId}
                            {@const val = comparison.cellValues[`${feature.id}-${prodId}`] || 'yes'}
                            {#if val !== 'yes' && val !== 'no'}
                              <div class="hstack gap-2 mx-auto" style="max-width: 160px;">
                                <input 
                                  type="text" 
                                  class="form-control text-center animate__animated animate__fadeIn animate__fast" 
                                  placeholder="Örn: 10 GB" 
                                  value={val === 'custom' ? '' : val} 
                                  oninput={(e) => comparison.cellValues[`${feature.id}-${prodId}`] = e.target.value} />
                                <button 
                                  type="button" 
                                  class="btn-close" 
                                  onclick={() => comparison.cellValues[`${feature.id}-${prodId}`] = 'yes'}
                                  title="Seçeneğe Geri Dön"
                                  aria-label="Seçeneğe Geri Dön"></button>
                              </div>
                            {:else}
                              <select 
                                class="form-select text-center mx-auto" 
                                value={val} 
                                onchange={(e) => comparison.cellValues[`${feature.id}-${prodId}`] = e.target.value}
                                style="max-width: 120px;">
                                <option value="yes">✔</option>
                                <option value="no">❌</option>
                                <option value="custom">Özel Tanım</option>
                              </select>
                            {/if}
                          {:else}
                            <span class="text-body-secondary font-monospace">- Seçilmemiş -</span>
                          {/if}
                        </td>
                      {/each}
                      <td class="text-center">
                        <button 
                          type="button" 
                          class="btn-close" 
                          onclick={() => removeFeature(feature.id)}
                          title="Satırı Sil"
                          aria-label="Satırı Sil"></button>
                      </td>
                    </tr>
                  {/each}
                  
                  {#if comparison.features.length === 0}
                    <tr>
                      <td colspan={comparison.selectedProducts.length + 2} class="text-center py-4 text-body-secondary">
                        <span>Henüz hiçbir özellik eklenmemiş.</span>
                      </td>
                    </tr>
                  {/if}

                  <!-- Özellik Ekleme Satırı -->
                  <tr>
                    <td colspan={comparison.selectedProducts.length + 2} class="p-3">
                      <button 
                        type="button" 
                        class="btn btn-primary d-inline-flex align-items-center justify-content-center" 
                        onclick={addFeature}
                        title="Özellik Satırı Ekle">
                        <i class="fas fa-plus"></i>
                      </button>
                    </td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Yan Sütun (Sidebar) -->
    <div class="col-lg-4">
      <div class="vstack gap-3">
        <!-- Ayarlar Kartı -->
        <div class="card">
          <div class="card-body">
            <ul class="list-group p-0 m-0">
              <!-- Durum Seçimi -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">Durum</div>
                  <div class="col-6 d-flex justify-content-end align-items-center gap-2">
                    <span>
                      {comparison.status === 'active' ? 'Aktif' : 'Pasif'}
                    </span>
                    <div class="form-check form-switch m-0">
                      <input 
                        class="form-check-input cursor-pointer" 
                        type="checkbox" 
                        role="switch" 
                        id="comparisonStatusSwitch" 
                        checked={comparison.status === 'active'}
                        onchange={(e) => comparison.status = e.target.checked ? 'active' : 'inactive'} />
                    </div>
                  </div>
                </div>
              </li>

              <!-- Sıralama -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">Öncelik</div>
                  <div class="col-6">
                    <input 
                      type="number" 
                      class="form-control text-end" 
                      placeholder="0" 
                      bind:value={comparison.priority} />
                  </div>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </div>
  </section>
</MarketLayout>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
</style>
