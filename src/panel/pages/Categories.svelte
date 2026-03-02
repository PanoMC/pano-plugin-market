<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';
  import tooltip from '@panomc/sdk/utils/tooltip';

  let page = $state(1);

  const categories = [
    { id: 1, name: 'VIP Üyelikler', productsCount: 5, status: 'active', color: '#0dcaf0', image: null },
    { id: 2, name: 'Kredi Paketleri', productsCount: 12, status: 'active', color: '#0d6efd', image: null },
    { id: 3, name: 'Kasa Anahtarları', productsCount: 8, status: 'active', color: '#ffc107', image: null },
    { id: 4, name: 'Özel Eşyalar', productsCount: 15, status: 'inactive', color: '#6c757d', image: null },
    { id: 5, name: 'Kozmetik Ürünler', productsCount: 20, status: 'active', color: '#d63384', image: null }
  ];

  function onPageClick(pageNum) {
    page = pageNum;
  }
</script>

{#snippet right()}
  <a href="/market/categories/create-category" class="btn btn-secondary border-0">
    <i class="fa-solid fa-plus"></i>
    <span class="d-lg-inline d-none ms-2">Kategori Ekle</span>
  </a>
{/snippet}

<MarketLayout {right}>
  <div class="card">
    <CardHeader>
      <div slot="left">
        Tüm Kategoriler
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={true}>Tümü</CardFiltersItem>
        <CardFiltersItem button>Aktif</CardFiltersItem>
        <CardFiltersItem button>Pasif</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    <div class="table-responsive">
      <table class="table table-hover">
        <thead>
          <tr>
            <th scope="col" class="align-middle" style="width: 60px;"></th>
            <th scope="col" class="align-middle text-nowrap">Küçük Resim</th>
            <th scope="col" class="align-middle text-nowrap">İsim</th>
            <th scope="col" class="align-middle text-nowrap">Ürün Sayısı</th>
            <th scope="col" class="align-middle text-nowrap">Durum</th>
          </tr>
        </thead>
        <tbody>
          {#each categories as category}
            <tr>
              <th scope="row" class="align-middle">
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
                      <i class="fas fa-pen me-2"></i>
                      Düzenle
                    </button>
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-trash me-2 text-danger"></i>
                      Sil
                    </button>
                  </div>
                </div>
              </th>
              <td class="align-middle">
                <div class="d-flex align-items-center justify-content-start">
                  {#if category.image}
                    <img src={category.image} alt={category.name} class="img-thumbnail" style="width: 40px; height: 40px;" />
                  {:else}
                    <img src="/assets/images/category.png" alt={category.name} class="img-thumbnail" style="width: 40px; height: 40px;" />
                  {/if}
                </div>
              </td>
              <td class="align-middle">
                <a
                  href="#"
                  use:tooltip={['Düzenle']}
                  class="rounded focus-ring text-decoration-none d-block text-truncate fw-medium">
                  {category.name}
                </a>
              </td>
              <td class="align-middle text-nowrap">
                {category.productsCount} Ürün
              </td>
              <td class="align-middle text-nowrap">
                {#if category.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}
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
