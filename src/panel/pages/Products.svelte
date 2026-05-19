<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';

  let page = $state(1);
  let search = $state('');

  const products = [
    { id: 1, name: 'VIP Üyelik (Aylık)', type: 'Süreli', category: 'VIP Üyelikler', price: '45.00 ₺', stock: 'Sınırsız', status: 'active', image: null, icon: 'fa-solid fa-crown text-warning' },
    { id: 2, name: '1000 Kredi', type: 'Cüzdan', category: 'Kredi Paketleri', price: '100.00 ₺', stock: 'Sınırsız', status: 'active', image: null, icon: 'fa-solid fa-coins text-info' },
    { id: 3, name: 'Kasa Anahtarı x10', type: 'Eşya', category: 'Kasa Anahtarları', price: '45.00 ₺', stock: 'Sınırsız', status: 'active', image: null, icon: 'fa-solid fa-key text-secondary' },
    { id: 4, name: 'Özel Kanat', type: 'Kozmetik', category: 'Özel Eşyalar', price: '150.00 ₺', stock: '5 Adet', status: 'inactive', image: null, icon: 'fa-solid fa-feather text-success' },
    { id: 5, name: 'Efekt Paketi', type: 'Efekt', category: 'Kozmetik Ürünler', price: '25.00 ₺', stock: 'Sınırsız', status: 'active', image: null, icon: 'fa-solid fa-wand-magic-sparkles text-danger' }
  ];

  function onPageClick(pageNum) {
    page = pageNum;
  }
</script>
<MarketLayout>
  {#snippet right()}
    <a href="/panel/market/products/create-product" class="btn btn-secondary">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">Ürün Ekle</span>
    </a>
  {/snippet}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {products.length} Ürün
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={search}
          placeholder="Ürün ara..."
          onchange={(val) => (search = val)} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={true}>Tümü</CardFiltersItem>
        <CardFiltersItem button>Aktif</CardFiltersItem>
        <CardFiltersItem button>Pasif</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    <div class="table-responsive">
      <table class="table table-hover align-middle">
        <thead>
          <tr>
            <th scope="col" style="width: 60px;"></th>
            <th scope="col" class="text-nowrap" style="width: 60px;">İkon</th>
            <th scope="col" class="text-nowrap">Küçük Resim</th>
            <th scope="col" class="text-nowrap">Ürün Adı</th>
            <th scope="col" class="text-nowrap">Durum</th>
            <th scope="col" class="text-nowrap">Kategori</th>
            <th scope="col" class="text-nowrap">Fiyat</th>
            <th scope="col" class="text-nowrap">Stok</th>
          </tr>
        </thead>
        <tbody>
          {#each products as product}
            <tr>
              <th scope="row">
                <div class="dropdown position-static">
                  <button
                    type="button"
                    class="btn btn-link"
                    data-bs-toggle="dropdown"
                    title="İşlemler"
                    aria-label="İşlemler">
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-pen me-2"></i>
                      Düzenle
                    </button>
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-clone me-2"></i>
                      Klonla
                    </button>
                    <button type="button" class="dropdown-item text-danger">
                      <i class="fas fa-trash me-2"></i>
                      Sil
                    </button>
                  </div>
                </div>
              </th>
              <td>
                <div class="d-flex align-items-center justify-content-center bg-body-secondary rounded-circle" style="width: 36px; height: 36px;">
                  <i class={product.icon || 'fa-solid fa-box text-body-secondary'}></i>
                </div>
              </td>
              <td>
                <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded overflow-hidden" style="width: 40px; height: 40px;">
                  {#if product.image}
                    <img src={product.image} alt={product.name} class="w-100 h-100 object-fit-cover" />
                  {:else}
                    <!-- Premium vector box icon representing default product package -->
                    <svg class="w-100 h-100 p-2 text-primary opacity-75" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <path d="M12 2L2 7L12 12L22 7L12 2Z" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/>
                      <path d="M2 17L12 22L22 17" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="opacity-50"/>
                      <path d="M2 12L12 17L22 12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" class="opacity-75"/>
                    </svg>
                  {/if}
                </div>
              </td>
              <td>
                <div class="vstack gap-0">
                  <button
                    type="button"
                    title="Düzenle"
                    class="btn btn-link p-0 border-0 text-decoration-none text-start fw-medium focus-ring">
                    {product.name}
                  </button>
                  <span class="text-body-secondary small">ID: #{product.id}</span>
                </div>
              </td>
              <td>
                {#if product.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}
              </td>
              <td>
                <span class="badge text-bg-primary fw-medium border-0">{product.category}</span>
              </td>
              <td class="">
                {product.price}
              </td>
              <td>
                <span class="badge text-bg-primary fw-medium border-0">{product.stock}</span>
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
  </div>
</MarketLayout>
