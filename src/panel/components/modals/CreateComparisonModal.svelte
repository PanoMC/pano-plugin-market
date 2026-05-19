<script>
  import { NoContent } from '@panomc/sdk/components/panel';
  import { onMount } from 'svelte';

  let { isEdit = $bindable(false) } = $props();

  let comparisonName = $state('');
  let status = $state('active');
  
  const products = [
    { id: 1, name: 'VIP Üyelik (Aylık)' },
    { id: 2, name: 'VIP+ Üyelik (Aylık)' },
    { id: 3, name: 'MVP Üyelik (Aylık)' },
    { id: 4, name: 'MVP+ Üyelik (Aylık)' },
    { id: 5, name: '1000 Kredi' },
    { id: 6, name: 'Kasa Anahtarı x10' }
  ];
  
  let selectedProducts = $state([]);

  function toggleProduct(id) {
    if (selectedProducts.includes(id)) {
      selectedProducts = selectedProducts.filter(pId => pId !== id);
    } else {
      selectedProducts = [...selectedProducts, id];
    }
  }

  let features = $state([
    { id: 1, name: 'Özel Giriş Mesajı' },
    { id: 2, name: 'Uçma Yetkisi' },
    { id: 3, name: 'Renk Değiştirme' }
  ]);

  function addFeature() {
    features = [...features, { id: Date.now(), name: '' }];
  }

  function removeFeature(id) {
    features = features.filter(f => f.id !== id);
  }

  let selectedProductObjects = $derived(
    products.filter(p => selectedProducts.includes(p.id))
  );

  let cellValues = $state({});

  onMount(() => {
    const modalEl = document.getElementById('createComparisonModal');
    if (modalEl) {
      const handleShow = (event) => {
        const trigger = event.relatedTarget;
        if (trigger && (trigger.classList.contains('dropdown-item') || trigger.closest('.dropdown-item') || trigger.classList.contains('btn-link') || trigger.closest('.btn-link'))) {
          isEdit = true;
        } else {
          isEdit = false;
        }
      };
      modalEl.addEventListener('show.bs.modal', handleShow);
      return () => {
        modalEl.removeEventListener('show.bs.modal', handleShow);
      };
    }
  });
</script>

<div class="modal fade" id="createComparisonModal" tabindex="-1" aria-labelledby="createComparisonModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createComparisonModalLabel">
          {isEdit ? 'Karşılaştırmayı Düzenle' : 'Karşılaştırma Oluştur'}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body">
        <!-- En Üst Kısım: Yarı yarıya bölünmüş -->
        <div class="row g-3 align-items-center mb-4">
          <div class="col-md-6">
            <div class="form-check form-switch m-0 fs-5 d-flex align-items-center">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="statusSwitch" 
                     checked={status === 'active'} 
                     onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                     style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-2 cursor-pointer small" for="statusSwitch">
                {status === 'active' ? 'Aktif' : 'Pasif'}
              </label>
            </div>
          </div>
          <div class="col-md-6">
            <div class="form-floating">
              <input type="text" class="form-control" id="compNameInput" bind:value={comparisonName} placeholder="Örn: VIP Paketleri" />
              <label for="compNameInput">Karşılaştırma Adı</label>
            </div>
          </div>
        </div>

        <!-- Orta Kısım: Ürünler ve Özellik Ekleme -->
        <div class="row g-4 mb-4">
          <!-- Sol Kolon: Ürün Seçimi -->
          <div class="col-md-6">
            <label class="form-label mb-2">Karşılaştırılacak Ürünler</label>
            <div class="list-group border rounded overflow-y-auto" style="max-height: 200px;">
              {#each products as product}
                <label class="list-group-item d-flex align-items-center gap-3 py-2 cursor-pointer list-group-item-action border-0 border-bottom">
                  <input class="form-check-input flex-shrink-0 mt-0 cursor-pointer" type="checkbox" value={product.id} 
                         checked={selectedProducts.includes(product.id)}
                         onchange={() => toggleProduct(product.id)}>
                  <span>{product.name}</span>
                </label>
              {/each}
            </div>
            <div class="form-text small mt-1">Karşılaştırma tablosunda görünecek ürünleri seçin.</div>
          </div>

          <!-- Sağ Kolon: Özellik Ekleme -->
          <div class="col-md-6">
            <div class="d-flex align-items-center justify-content-between mb-2">
              <label class="form-label m-0">Karşılaştırma Özellikleri</label>
              <button type="button" class="btn btn-sm btn-primary" onclick={addFeature} title="Ekle">
                <i class="fas fa-plus"></i>
              </button>
            </div>
            
            <div class="vstack gap-2 overflow-y-auto pe-2" style="max-height: 200px;">
              {#each features as feature (feature.id)}
                <div class="input-group">
                  <span class="input-group-text">
                    <i class="fas fa-list-ul text-body-secondary small"></i>
                  </span>
                  <input type="text" class="form-control" bind:value={feature.name} placeholder="Özellik adı..." />
                  <button class="btn btn-outline-danger" type="button" onclick={() => removeFeature(feature.id)}>
                    <i class="fas fa-times"></i>
                  </button>
                </div>
              {/each}
              
              {#if features.length === 0}
                <div class="text-center py-4 text-body-secondary border rounded">
                  <i class="fas fa-info-circle mb-2 d-block fs-4 opacity-50"></i>
                  <span class="small">Henüz özellik eklenmemiş.</span>
                </div>
              {/if}
            </div>
            <div class="form-text small mt-1">Her bir ürün için bu özelliklerin durumunu tablodan yönetebileceksiniz.</div>
          </div>
        </div>

        <!-- En Alt Kısım: Karşılaştırma Tablosu -->
        <div class="border-top pt-4">
          <h6 class="mb-3">Karşılaştırma Tablosu Önizleme</h6>
          
          {#if selectedProducts.length === 0 || features.length === 0}
            <NoContent text="" />
          {:else}
            <div class="table-responsive border rounded">
              <table class="table table-striped table-hover align-middle mb-0">
                <thead>
                  <tr>
                    <th scope="col" style="min-width: 150px;">Özellik</th>
                    {#each selectedProductObjects as prod}
                      <th scope="col" class="text-center" style="min-width: 120px;">{prod.name}</th>
                    {/each}
                  </tr>
                </thead>
                <tbody>
                  {#each features as feature (feature.id)}
                    <tr>
                      <td>{feature.name || 'İsimsiz Özellik'}</td>
                      {#each selectedProductObjects as prod}
                        <td>
                          <select class="form-select form-select-sm text-center" 
                                  value={cellValues[`${feature.id}-${prod.id}`] || 'yes'} 
                                  onchange={(e) => cellValues[`${feature.id}-${prod.id}`] = e.target.value}>
                            <option value="yes">✔</option>
                            <option value="no">❌</option>
                          </select>
                        </td>
                      {/each}
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {/if}
        </div>
      </div>
      <div class="modal-footer">
        {#if isEdit}
          <button type="button" class="btn btn-primary w-100">Kaydet</button>
        {:else}
          <button type="button" class="btn btn-secondary w-100">Oluştur</button>
        {/if}
      </div>
    </div>
  </div>
</div>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
  .list-group-item:last-child {
    border-bottom: 0 !important;
  }
</style>
