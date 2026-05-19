<script>
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput, Pagination, NoContent } from '@panomc/sdk/components/panel';

  let page = $state(1);
  let search = $state('');
  let filter = $state('all'); // all | active | inactive

  let creatorCodes = $state([
    { id: 1, creator: 'Yusuf Aktaş (Reynmen)', code: 'REYNMEN', discount: 10, commission: 5, usage: 145, usageLimit: null, earnings: '725 ₺', status: 'active', startDate: '01 Haz 2024', expiry: '31 Ağu 2024' },
    { id: 2, creator: 'Enes Batur', code: 'ENESBATUR', discount: 10, commission: 8, usage: 310, usageLimit: null, earnings: '2,480 ₺', status: 'active', startDate: '-', expiry: null },
    { id: 3, creator: 'Baturay Anar', code: 'BATURAY', discount: 15, commission: 5, usage: 88, usageLimit: null, earnings: '660 ₺', status: 'inactive', startDate: '01 Ara 2023', expiry: '01 Oca 2024' }
  ]);

  const filteredCreatorCodes = $derived.by(() => {
    const term = search.trim().toLowerCase();
    return creatorCodes.filter((c) => {
      if (filter === 'active' && c.status !== 'active') return false;
      if (filter === 'inactive' && c.status !== 'inactive') return false;
      if (!term) return true;
      return (
        c.creator.toLowerCase().includes(term) ||
        c.code.toLowerCase().includes(term)
      );
    });
  });

  function onPageClick(pageNum) {
    page = pageNum;
  }
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {filteredCreatorCodes.length} Referans Kodu
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={search}
        placeholder="Oyuncu veya kod ara..."
        onchange={(val) => (search = val)} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={filter === 'all'} onclick={() => (filter = 'all')}>Tümü</CardFiltersItem>
      <CardFiltersItem button active={filter === 'active'} onclick={() => (filter = 'active')}>Aktif</CardFiltersItem>
      <CardFiltersItem button active={filter === 'inactive'} onclick={() => (filter = 'inactive')}>Pasif</CardFiltersItem>
    </CardFilters>
  </CardHeader>

  {#if filteredCreatorCodes.length === 0}
    <NoContent />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle text-nowrap">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col">Referans Oyuncu</th>
            <th scope="col">Kupon Kodu</th>
            <th scope="col">İndirim Oranı</th>
            <th scope="col">Komisyon</th>
            <th scope="col">Kullanım</th>
            <th scope="col">Toplam Kazanç</th>
            <th scope="col">Durum</th>
            <th scope="col">Geçerlilik Süresi</th>
          </tr>
        </thead>
        <tbody>
          {#each filteredCreatorCodes as item}
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
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal">
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
                <a href="#" class="text-decoration-none focus-ring" title="Düzenle" data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal" onclick={(e) => e.preventDefault()}>
                  {item.creator}
                </a>
              </td>
              <td>
                <a href="#" class="font-monospace text-decoration-none focus-ring" title="Düzenle" data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal" onclick={(e) => e.preventDefault()}>
                  {item.code}
                </a>
              </td>
              <td>
                <span class="font-monospace">%{item.discount}</span>
              </td>
              <td>
                <span class="font-monospace text-success">%{item.commission}</span>
              </td>
              <td>
                <span class="font-monospace">
                  {#if !item.usageLimit}
                    {item.usage} / ∞
                  {:else}
                    {item.usage} / {item.usageLimit}
                  {/if}
                </span>
              </td>
              <td>
                <span class="font-monospace">{item.earnings}</span>
              </td>
              <td>
                {#if item.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}
              </td>
              <td>
                {#if item.expiry === 'Süresiz' || !item.expiry}
                  <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">Süresiz</span>
                {:else}
                  <div class="d-flex flex-column lh-sm">
                    <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">Bşl: {item.startDate}</span>
                    <span class="font-monospace" style="font-size: 0.85rem;">Bti: {item.expiry}</span>
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
          totalPage={1}
          on:firstPageClick={() => onPageClick(1)}
          on:lastPageClick={() => onPageClick(1)} />
    </div>
  {/if}
</div>
