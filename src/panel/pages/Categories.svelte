<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination } from '@panomc/sdk/components/panel';
  import tooltip from '@panomc/sdk/utils/tooltip';
  import { onMount } from 'svelte';
  import { flip } from 'svelte/animate';

  let page = $state(1);
  let view = $state('table');
  let draggedId = $state(null);

  let categories = $state([
    { id: 1, name: 'VIP Üyelikler', icon: 'fa-crown', description: 'Sunucumuzdaki tüm VIP paketlerini burada bulabilirsiniz.', productsCount: 5, status: 'active', color: '#0dcaf0', image: null, children: [] },
    { 
      id: 2, 
      name: 'Kredi Paketleri', 
      icon: 'fa-coins', 
      description: 'Mağaza içi harcamalarınız için kredi satın alın.', 
      productsCount: 12, 
      status: 'active', 
      color: '#0d6efd', 
      image: null, 
      children: [
        { id: 6, name: 'Bonus Paketler', icon: 'fa-gift', description: 'Ekstra bonus veren paketler.', productsCount: 3, status: 'active', color: '#198754', image: null, children: [] }
      ] 
    },
    { id: 3, name: 'Kasa Anahtarları', icon: 'fa-key', description: 'Gizemli kasaları açmak için gereken anahtarlar.', productsCount: 8, status: 'active', color: '#ffc107', image: null, children: [] },
    { id: 4, name: 'Özel Eşyalar', icon: 'fa-star', description: 'Sadece sınırlı süre için mevcut olan özel eşyalar.', productsCount: 15, status: 'inactive', color: '#6c757d', image: null, children: [] },
    { id: 5, name: 'Kozmetik Ürünler', icon: 'fa-shirt', description: 'Karakterinizi özelleştirebileceğiniz kozmetik ürünler.', productsCount: 20, status: 'active', color: '#d63384', image: null, children: [] }
  ]);

  function onPageClick(pageNum) {
    page = pageNum;
  }

  function onDragStart(e, id) {
    draggedId = id;
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move';
      const img = new Image();
      img.src = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
      e.dataTransfer.setDragImage(img, 0, 0);
    }
  }

  function onDragEnter(e, targetId) {
    if (!draggedId || draggedId === targetId) return;

    const items = [...categories];
    const fromIndex = items.findIndex((i) => i.id === draggedId);
    const targetIndex = items.findIndex((i) => i.id === targetId);

    if (fromIndex !== -1 && targetIndex !== -1) {
      const [movedItem] = items.splice(fromIndex, 1);
      items.splice(targetIndex, 0, movedItem);
      categories = items;
    }
  }

  function onDragEnd() {
    draggedId = null;
  }

  function onDrop(e) {
    e.preventDefault();
    draggedId = null;
  }

  onMount(() => {
    console.log('Categories page loaded');
  });
</script>

<MarketLayout>
  {#snippet right()}
    <a href="/panel/market/categories/create-category" class="btn btn-secondary border-0">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">Kategori Ekle</span>
    </a>
  {/snippet}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {categories.length} Kategori
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={view === 'table'} on:click={() => view = 'table'}>Tablo</CardFiltersItem>
        <CardFiltersItem button active={view === 'sort'} on:click={() => view = 'sort'}>Sıralama</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if view === 'table'}
      <div class="table-responsive">
      <table class="table table-hover align-middle">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col" style="width: 60px;"></th>
            <th scope="col">Kategori İçeriği</th>
            <th scope="col" class="text-center" style="width: 120px;">Durum</th>
            <th scope="col" class="text-center" style="width: 150px;">Ürün Sayısı</th>
          </tr>
        </thead>
        <tbody>
          {#each categories as category (category.id)}
            <tr>
              <th scope="row" class="align-middle text-center" style="width: 50px;">
                <div class="dropdown position-static">
                  <button
                    type="button"
                    class="btn btn-link"
                    data-bs-toggle="dropdown"
                    use:tooltip={['İşlemler']}
                    aria-label="İşlemler">
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-pen me-2"></i> Düzenle
                    </button>
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-trash me-2 text-danger"></i> Sil
                    </button>
                  </div>
                </div>
              </th>
              <td class="align-middle">
                <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded overflow-hidden" style="width: 40px; height: 40px;">
                  {#if category.image}
                    <img src={category.image} alt={category.name} class="w-100 h-100 object-fit-cover" />
                  {:else}
                    <img src="/assets/images/category.png" alt={category.name} class="w-100 h-100 object-fit-cover opacity-50" />
                  {/if}
                </div>
              </td>
              <td class="align-middle">
                <div class="d-flex align-items-center gap-3">
                  <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded" style="width: 32px; height: 32px; flex-shrink: 0;">
                    <i class="fas {category.icon} fs-6" style="color: {category.color}"></i>
                  </div>
                  <div>
                    <div class="fw-bold">{category.name}</div>
                    <div class="small text-muted text-truncate d-none d-md-block" style="max-width: 300px;">
                      {category.description || '-'}
                    </div>
                  </div>
                </div>
              </td>
              <td class="align-middle text-center">
                {#if category.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}
              </td>
              <td class="align-middle text-center text-nowrap">
                <span class="badge text-bg-primary fw-normal">{category.productsCount} Ürün</span>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>

      <div class="card-footer">
         <Pagination
            {page}
            totalPage={5}
            on:firstPageClick={() => onPageClick(1)}
            on:lastPageClick={() => onPageClick(5)}
            on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
      </div>
    {:else if view === 'sort'}
      <div class="card-body">
        <div class="alert alert-info border-0 bg-info-subtle text-info-emphasis d-flex align-items-center mb-4">
          <i class="fas fa-info-circle fs-4 me-3"></i>
          <div>
            Kategorileri sıralamak için sürükleyip bırakın. Değişiklikler otomatik olarak kaydedilir.
          </div>
        </div>
        
        <div class="list-group list-group-flush border rounded" ondragover={(e) => e.preventDefault()} ondrop={onDrop}>
          {#each categories as category (category.id)}
            <div
              class="list-group-item d-flex align-items-center gap-3 p-3 bg-body"
              style="cursor: grab; transition: background-color 0.2s, opacity 0.2s;"
              class:opacity-50={category.id === draggedId}
              class:bg-body-tertiary={category.id === draggedId}
              draggable="true"
              ondragstart={(e) => onDragStart(e, category.id)}
              ondragover={(e) => e.preventDefault()}
              ondragenter={(e) => onDragEnter(e, category.id)}
              ondragend={onDragEnd}
              animate:flip={{ duration: 300 }}>
              
              <div class="text-muted cursor-grab">
                <i class="fas fa-grip-vertical"></i>
              </div>
              
              <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded" style="width: 32px; height: 32px;">
                <i class="fas {category.icon} fs-6" style="color: {category.color}"></i>
              </div>
              
              <div class="fw-medium flex-grow-1">
                {category.name}
              </div>
              
              <div>
                <span class="badge text-bg-secondary fw-normal">{category.productsCount} Ürün</span>
              </div>
            </div>
          {/each}
        </div>
      </div>
    {/if}
  </div>
</MarketLayout>
