<script>
  import { onMount } from 'svelte';
  import ProductSelector from '../ProductSelector.svelte';

  let { isEdit = $bindable(false) } = $props();

  let name = $state('');
  let value = $state('');
  let unit = $state('%');
  let startDate = $state('');
  let expiryDate = $state('');
  let isExpiryUnlimited = $state(true);
  let usageLimit = $state('');
  let isLimitUnlimited = $state(true);
  let status = $state('active');
  let minPaymentAmount = $state('');
  let discountScope = $state('all'); // 'all', 'products', 'categories'

  const products = [
    { id: 1, name: 'VIP Üyelik (Aylık)' },
    { id: 2, name: '1000 Kredi' },
    { id: 3, name: 'Kasa Anahtarı x10' },
    { id: 4, name: 'Özel Kanat' },
    { id: 5, name: 'Efekt Paketi' }
  ];
  let selectedProducts = $state([]);

  const categories = [
    { id: 1, name: 'VIP Paketleri' },
    { id: 2, name: 'Kredi Paketleri' },
    { id: 3, name: 'Kasa Anahtarları' },
    { id: 4, name: 'Kozmetikler' }
  ];
  let selectedCategories = $state([]);

  let categorySearch = $state('');

  let filteredCategories = $derived(
    categories.filter(c => c.name.toLowerCase().includes(categorySearch.toLowerCase()))
  );

  function toggleCategory(id) {
    if (selectedCategories.includes(id)) {
      selectedCategories = selectedCategories.filter(cId => cId !== id);
    } else {
      selectedCategories = [...selectedCategories, id];
    }
  }

  onMount(() => {
    const modalEl = document.getElementById('createDiscountModal');
    if (modalEl) {
      const handleShow = (event) => {
        const trigger = event.relatedTarget;
        if (trigger && (trigger.classList.contains('dropdown-item') || trigger.closest('.dropdown-item'))) {
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

<div class="modal fade" id="createDiscountModal" tabindex="-1" aria-labelledby="createDiscountModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header p-3">
        <h5 class="modal-title" id="createDiscountModalLabel">
          {#if isEdit}İndirimi Düzenle{:else}İndirim Oluştur{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body p-3">
        <div class="vstack gap-3">
          <!-- Status Switch -->
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="discountStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="discountStatusSwitch">
              Aktif
            </label>
          </div>

          <!-- Discount Name -->
          <div class="form-floating">
            <input type="text" class="form-control" id="discountNameInput" bind:value={name} placeholder="İndirim Adı" />
            <label for="discountNameInput">İndirim Adı</label>
          </div>

          <!-- Value and Unit -->
          <div class="row g-3">
            <div class="col-8">
              <div class="form-floating">
                <input type="number" class="form-control" id="discountValueInput" bind:value={value} placeholder="Değer" />
                <label for="discountValueInput">İndirim Değeri</label>
              </div>
            </div>
            <div class="col-4">
              <div class="form-floating">
                <select class="form-select" id="discountUnitSelect" bind:value={unit}>
                  <option value="%">Yüzde (%)</option>
                  <option value="₺">Sabit (₺)</option>
                </select>
                <label for="discountUnitSelect">Birim</label>
              </div>
            </div>
          </div>

          <!-- Minimum Sepet Tutarı -->
          <div class="input-group">
            <div class="form-floating">
              <input type="number" class="form-control" id="discountMinPaymentInput" bind:value={minPaymentAmount} placeholder="Min Sepet Tutarı" min="0" />
              <label for="discountMinPaymentInput">Min Sepet Tutarı</label>
            </div>
            <span class="input-group-text">₺</span>
          </div>

          <!-- İndirim Kapsamı Seçimi -->
          <div class="mb-2">
            <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3 mb-2 mt-1">
              <div class="form-check m-0">
                <input class="form-check-input cursor-pointer" type="radio" name="discountScope" id="scopeAll" value="all" bind:group={discountScope}>
                <label class="form-check-label cursor-pointer" for="scopeAll">Tüm Ürünler</label>
              </div>
              <div class="form-check m-0">
                <input class="form-check-input cursor-pointer" type="radio" name="discountScope" id="scopeProducts" value="products" bind:group={discountScope}>
                <label class="form-check-label cursor-pointer" for="scopeProducts">Seçili Ürünler</label>
              </div>
              <div class="form-check m-0">
                <input class="form-check-input cursor-pointer" type="radio" name="discountScope" id="scopeCategories" value="categories" bind:group={discountScope}>
                <label class="form-check-label cursor-pointer" for="scopeCategories">Seçili Kategoriler</label>
              </div>
            </div>

            {#if discountScope === 'products'}
              <ProductSelector products={products} bind:selected={selectedProducts} multiple={true} maxHeight="150px" />
            {:else if discountScope === 'categories'}
              <input type="text" class="form-control form-control-sm mb-2" placeholder="Kategori ara..." bind:value={categorySearch} />
              <div class="list-group border rounded overflow-y-auto mb-0" style="max-height: 150px;">
                {#each filteredCategories as category}
                  <label class="list-group-item d-flex align-items-center gap-3 py-2 cursor-pointer list-group-item-action">
                    <input class="form-check-input flex-shrink-0 mt-0 cursor-pointer" type="checkbox" value={category.id}
                           checked={selectedCategories.includes(category.id)}
                           onchange={() => toggleCategory(category.id)}>
                    <span>{category.name}</span>
                  </label>
                {/each}
              </div>
            {/if}
          </div>

          <!-- Tarih Alanları (Süresiz switch aktifse tamamen gizlenir) -->
          <div class="vstack gap-2">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="discountExpirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="discountExpirySwitch">Süresiz</label>
            </div>
            
            {#if !isExpiryUnlimited}
              <div class="row g-3 animate__animated animate__fadeIn">
                <!-- Başlangıç Tarihi -->
                <div class="col-md-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="discountStartInput" bind:value={startDate} placeholder="Başlangıç Tarihi" />
                    <label for="discountStartInput">Başlangıç Tarihi</label>
                  </div>
                </div>

                <!-- Bitiş Tarihi -->
                <div class="col-md-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="discountExpiryInput" bind:value={expiryDate} placeholder="Bitiş Tarihi" />
                    <label for="discountExpiryInput">Bitiş Tarihi</label>
                  </div>
                </div>
              </div>
            {/if}
          </div>

          <!-- Usage Limit -->
          <div class="row g-3 align-items-center">
            <div class="col-6">
              <div class="form-check form-switch m-0">
                <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="discountLimitSwitch" bind:checked={isLimitUnlimited} style="width: 2.5em; height: 1.25em;">
                <label class="form-check-label ms-1 cursor-pointer mt-1" for="discountLimitSwitch">Limitsiz</label>
              </div>
            </div>
            <div class="col-6">
              {#if !isLimitUnlimited}
                <div class="form-floating animate__animated animate__fadeIn">
                  <input type="number" class="form-control" id="discountLimitInput" bind:value={usageLimit} placeholder="Kullanım Limiti" min="1" />
                  <label for="discountLimitInput">Kullanım Limiti</label>
                </div>
              {/if}
            </div>
          </div>
        </div>
      </div>
      <div class="modal-footer p-3">
        {#if isEdit}
          <button type="button" class="btn btn-primary w-100 m-0">Kaydet</button>
        {:else}
          <button type="button" class="btn btn-secondary w-100 m-0">Oluştur</button>
        {/if}
      </div>
    </div>
  </div>
</div>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
</style>
