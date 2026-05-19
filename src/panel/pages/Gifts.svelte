<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, NoContent, SearchInput, Pagination } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';
  import CreateGiftModal from '../components/modals/CreateGiftModal.svelte';

  let page = $state(1);
  let gifts = $state([
    { id: 1, code: 'YENIYIL24', type: 'credit', credit: '1000', status: 'active', startDate: '01 Ara 2024', expiry: '31 Ara 2024' },
    { id: 2, code: 'VIPHEDIYE', type: 'product', product: 'VIP Üyelik (Aylık)', status: 'inactive', startDate: '-', expiry: 'Süresiz' },
    { id: 3, code: 'TELAFIOYUN', type: 'product', product: 'Kasa Anahtarı x10', status: 'active', startDate: '-', expiry: 'Süresiz' },
    { id: 4, code: 'HOSGELDIN', type: 'credit', credit: '500', status: 'active', startDate: '01 Oca 2024', expiry: '01 Haz 2024' },
    { id: 5, code: 'SANSLIKUTU', type: 'random', details: 'Rastgele Hediye (3 Ürün Seçili)', status: 'active', startDate: '18 May 2026', expiry: 'Süresiz' }
  ]);
  let search = $state('');

  function onPageClick(pageNum) {
    page = pageNum;
  }
</script>

<MarketLayout>
  {#snippet right()}
    <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createGiftModal">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">Hediye Oluştur</span>
    </button>
  {/snippet}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {gifts.length} Hediye
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={search}
          placeholder="Hediye ara..."
          onchange={(val) => (search = val)} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={true}>Tümü</CardFiltersItem>
        <CardFiltersItem button>Aktif</CardFiltersItem>
        <CardFiltersItem button>Pasif</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if gifts.length === 0}
      <NoContent />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th scope="col" style="width: 50px;"></th>
              <th scope="col">Hediye Kodu</th>
              <th scope="col">Ürünler</th>
              <th scope="col">Durum</th>
              <th scope="col">Geçerlilik Süresi</th>
            </tr>
          </thead>
          <tbody>
            {#each gifts as gift}
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
                      <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createGiftModal">
                        <i class="fas fa-pen me-2"></i>
                        Düzenle
                      </button>
                      <button type="button" class="dropdown-item text-danger">
                        <i class="fas fa-trash me-2"></i>
                        Sil
                      </button>
                    </div>
                  </div>
                </th>
                <td>
                  <a href="#" class="font-monospace text-decoration-none focus-ring" title="Düzenle" data-bs-toggle="modal" data-bs-target="#createGiftModal" onclick={(e) => e.preventDefault()}>
                    {gift.code}
                  </a>
                </td>
                <td>
                  <a href="#" class="text-decoration-none focus-ring d-inline-block" title="Düzenle" data-bs-toggle="modal" data-bs-target="#createGiftModal" onclick={(e) => e.preventDefault()}>
                    {#if gift.type === 'credit'}
                      <span class="badge bg-primary px-2.5 py-1.5 fw-medium cursor-pointer"><i class="fas fa-coins me-1"></i>{gift.credit} Kredi</span>
                    {:else if gift.type === 'random'}
                      <span class="badge bg-primary px-2.5 py-1.5 fw-medium cursor-pointer"><i class="fas fa-shuffle me-1"></i>{gift.details}</span>
                    {:else}
                      <span class="badge bg-primary px-2.5 py-1.5 fw-medium cursor-pointer"><i class="fas fa-box-open me-1"></i>{gift.product}</span>
                    {/if}
                  </a>
                </td>
                <td>
                  {#if gift.status === 'active'}
                    <span class="badge text-bg-success">Aktif</span>
                  {:else}
                    <span class="badge text-bg-danger">Pasif</span>
                  {/if}
                </td>
                <td>
                  {#if gift.expiry === 'Süresiz' || !gift.expiry}
                    <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">Süresiz</span>
                  {:else}
                    <div class="d-flex flex-column lh-sm">
                      <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">Bşl: {gift.startDate}</span>
                      <span class="font-monospace" style="font-size: 0.85rem;">Bti: {gift.expiry}</span>
                    </div>
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
    {/if}
  </div>
</MarketLayout>

<CreateGiftModal />
