<script>
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput, Pagination, NoContent } from '@panomc/sdk/components/panel';
  import CreateCouponModal from '../modals/CreateCouponModal.svelte';

  let page = $state(1);
  let search = $state('');
  let filter = $state('all'); // all | active | inactive

  let coupons = $state([
    { id: 1, code: 'YAZ2024', discount: 20, usage: 45, usageLimit: 100, status: 'active', startDate: '01 Haz 2024', expiry: '31 Ağu 2024' },
    { id: 2, code: 'HOSGELDIN', discount: 50, usage: 120, usageLimit: null, status: 'active', startDate: '-', expiry: null },
    { id: 3, code: 'VIPINDIRIM', discount: 15, usage: 5, usageLimit: 10, status: 'active', startDate: '01 Eyl 2024', expiry: '15 Eyl 2024' },
    { id: 4, code: 'ESKIKUPON', discount: 30, usage: 100, usageLimit: 100, status: 'inactive', startDate: '01 Ara 2023', expiry: '01 Oca 2024' },
  ]);

  const filteredCoupons = $derived.by(() => {
    const term = search.trim().toLowerCase();
    return coupons.filter((c) => {
      if (filter === 'active' && c.status !== 'active') return false;
      if (filter === 'inactive' && c.status !== 'inactive') return false;
      if (!term) return true;
      return c.code.toLowerCase().includes(term);
    });
  });

  function onPageClick(pageNum) {
    page = pageNum;
  }
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {filteredCoupons.length} Kupon Kodu
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={search}
        placeholder="Kupon ara..."
        onchange={(val) => (search = val)} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={filter === 'all'} onclick={() => (filter = 'all')}>Tümü</CardFiltersItem>
      <CardFiltersItem button active={filter === 'active'} onclick={() => (filter = 'active')}>Aktif</CardFiltersItem>
      <CardFiltersItem button active={filter === 'inactive'} onclick={() => (filter = 'inactive')}>Pasif</CardFiltersItem>
    </CardFilters>
  </CardHeader>

  {#if filteredCoupons.length === 0}
    <NoContent />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle text-nowrap">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col">Kupon Kodu</th>
            <th scope="col">İndirim Oranı</th>
            <th scope="col">Kullanım</th>
            <th scope="col">Durum</th>
            <th scope="col">Geçerlilik Süresi</th>
          </tr>
        </thead>
        <tbody>
          {#each filteredCoupons as coupon}
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
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createCouponModal">
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
                <a href="#" class="font-monospace text-decoration-none focus-ring" title="Düzenle" data-bs-toggle="modal" data-bs-target="#createCouponModal" onclick={(e) => e.preventDefault()}>
                  {coupon.code}
                </a>
              </td>
              <td>
                <span class="font-monospace">%{coupon.discount}</span>
              </td>
              <td>
                <span class="font-monospace">
                  {#if !coupon.usageLimit}
                    {coupon.usage} / ∞
                  {:else}
                    {coupon.usage} / {coupon.usageLimit}
                  {/if}
                </span>
              </td>
              <td>
                {#if coupon.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}
              </td>
              <td>
                {#if coupon.expiry === 'Süresiz' || !coupon.expiry}
                  <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">Süresiz</span>
                {:else}
                  <div class="d-flex flex-column lh-sm">
                    <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">Bşl: {coupon.startDate}</span>
                    <span class="font-monospace" style="font-size: 0.85rem;">Bti: {coupon.expiry}</span>
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

<CreateCouponModal />
