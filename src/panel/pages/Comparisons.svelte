<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput, NoContent } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';
  import CreateComparisonModal from '../components/modals/CreateComparisonModal.svelte';

  let page = $state(1);
  let search = $state('');
  let filter = $state('all');

  const comparisons = [
    { id: 1, name: 'VIP Paketleri Karşılaştırması', products: ['VIP', 'VIP+', 'MVP', 'MVP+'], status: 'active', updatedAt: '2024-05-16 14:20' },
    { id: 2, name: 'Kredi Paketleri Karşılaştırması', products: ['100 Kredi', '500 Kredi', '1000 Kredi'], status: 'active', updatedAt: '2024-05-15 09:10' },
    { id: 3, name: 'Kasa Anahtarları', products: ['Normal Kasa', 'Epik Kasa', 'Efsanevi Kasa'], status: 'inactive', updatedAt: '2024-05-10 18:30' }
  ];

  function onPageClick(pageNum) {
    page = pageNum;
  }
</script>

<MarketLayout>
  {#snippet right()}
    <button type="button" class="btn btn-secondary border-0" data-bs-toggle="modal" data-bs-target="#createComparisonModal">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">Yeni Karşılaştırma</span>
    </button>
  {/snippet}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {comparisons.length} Karşılaştırma
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={search}
          placeholder="Karşılaştırma ara..."
          onchange={(val) => (search = val)} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={filter === 'all'} onclick={() => filter = 'all'}>Tümü</CardFiltersItem>
        <CardFiltersItem button active={filter === 'active'} onclick={() => filter = 'active'}>Aktif</CardFiltersItem>
        <CardFiltersItem button active={filter === 'inactive'} onclick={() => filter = 'inactive'}>Pasif</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if comparisons.length === 0}
      <NoContent />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th scope="col" style="width: 60px;"></th>
              <th scope="col" class="text-nowrap">Karşılaştırma Adı</th>
              <th scope="col" class="text-nowrap">Ürünler</th>
              <th scope="col" class="text-nowrap">Son Güncelleme</th>
              <th scope="col" class="text-nowrap text-center">Durum</th>
            </tr>
          </thead>
          <tbody>
            {#each comparisons as comp}
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
                      <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createComparisonModal">
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
                  <button
                    type="button"
                    title="Düzenle"
                    data-bs-toggle="modal" 
                    data-bs-target="#createComparisonModal"
                    class="btn btn-link p-0 border-0 text-decoration-none text-start fw-medium focus-ring">
                    {comp.name}
                  </button>
                </td>
                <td>
                  <div class="d-flex flex-wrap gap-1">
                    {#each comp.products as product}
                      <span class="badge text-bg-primary">{product}</span>
                    {/each}
                  </div>
                </td>
                <td class="text-body-secondary small">
                  {comp.updatedAt}
                </td>
                <td class="text-center">
                  {#if comp.status === 'active'}
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
            totalPage={1}
            on:firstPageClick={() => onPageClick(1)}
            on:lastPageClick={() => onPageClick(1)}
            on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
      </div>
    {/if}
  </div>
</MarketLayout>

<CreateComparisonModal />
