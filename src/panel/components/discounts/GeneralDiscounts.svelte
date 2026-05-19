<script>
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput, Pagination, NoContent } from '@panomc/sdk/components/panel';
  import { onMount } from 'svelte';

  let page = $state(1);
  let search = $state('');
  let filter = $state('all'); // all | active | inactive

  let discounts = $state([
    { id: 1, name: 'Yaz İndirimi', type: 'automatic', value: 15, unit: '%', minPayment: 150, status: 'active', products: ['VIP Üyelik (Aylık)', '1000 Kredi', 'Kasa Anahtarı x10', 'Özel Kanat'], startDate: '01 Haz 2026', expiry: '31 Ağu 2026', limit: 500, usedCount: 142 },
    { id: 2, name: 'Hafta Sonu Fırsatı', type: 'automatic', value: 10, unit: '%', minPayment: '-', status: 'active', products: ['VIP Üyelik (Aylık)', '1000 Kredi'], startDate: '-', expiry: 'Süresiz', limit: 'Limitsiz', usedCount: 89 },
    { id: 3, name: 'Yeni Yıl Kampanyası', type: 'automatic', value: 50, unit: '₺', minPayment: 500, status: 'inactive', products: ['all'], startDate: '15 Ara 2025', expiry: '01 Oca 2026', limit: 1000, usedCount: 1000 }
  ]);

  const filteredDiscounts = $derived.by(() => {
    const term = search.trim().toLowerCase();
    return discounts.filter((d) => {
      if (filter === 'active' && d.status !== 'active') return false;
      if (filter === 'inactive' && d.status !== 'inactive') return false;
      if (!term) return true;
      return d.name.toLowerCase().includes(term);
    });
  });

  function onPageClick(pageNum) {
    page = pageNum;
  }

  $effect(() => {
    // Whenever filteredDiscounts changes, re-initialize Bootstrap popovers!
    const unused = filteredDiscounts;
    if (typeof window !== 'undefined' && window.bootstrap) {
      const timer = setTimeout(() => {
        const popoverTriggerList = document.querySelectorAll('[data-bs-toggle="popover"]');
        const popovers = [...popoverTriggerList].map(el => new window.bootstrap.Popover(el));
        return () => {
          popovers.forEach(p => p.dispose());
        };
      }, 50);
      return () => clearTimeout(timer);
    }
  });
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {filteredDiscounts.length} İndirim
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={search}
        placeholder="İndirim ara..."
        onchange={(val) => (search = val)} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={filter === 'all'} onclick={() => (filter = 'all')}>Tümü</CardFiltersItem>
      <CardFiltersItem button active={filter === 'active'} onclick={() => (filter = 'active')}>Aktif</CardFiltersItem>
      <CardFiltersItem button active={filter === 'inactive'} onclick={() => (filter = 'inactive')}>Pasif</CardFiltersItem>
    </CardFilters>
  </CardHeader>

  {#if filteredDiscounts.length === 0}
    <NoContent />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle text-nowrap">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col">İndirim Adı</th>
            <th scope="col">Değer</th>
            <th scope="col">Min Sepet Tutarı</th>
            <th scope="col">Ürünler</th>
            <th scope="col">Kullanım</th>
            <th scope="col">Durum</th>
            <th scope="col">Geçerlilik Süresi</th>
          </tr>
        </thead>
        <tbody>
          {#each filteredDiscounts as discount}
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
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createDiscountModal">
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
                <a href="#" class="text-decoration-none focus-ring" title="Düzenle" data-bs-toggle="modal" data-bs-target="#createDiscountModal" onclick={(e) => e.preventDefault()}>
                  {discount.name}
                </a>
              </td>
              <td>
                <span class="font-monospace">
                  {#if discount.unit === '%'}
                    %{discount.value}
                  {:else}
                    {discount.value} {discount.unit}
                  {/if}
                </span>
              </td>
              <td>
                <span class="font-monospace">
                  {#if discount.minPayment === '-' || !discount.minPayment}
                    -
                  {:else}
                    {discount.minPayment} ₺
                  {/if}
                </span>
              </td>
              <td class="cursor-pointer" data-bs-toggle="modal" data-bs-target="#createDiscountModal">
                <div class="d-flex align-items-center gap-1 flex-nowrap" style="max-width: 250px;">
                  {#if discount.products.includes('all') || discount.products.length === 0}
                    <a href="#" class="badge text-bg-primary text-truncate text-decoration-none focus-ring" title="Düzenle" onclick={(e) => e.preventDefault()}>Tüm Ürünler</a>
                  {:else}
                    {#each discount.products.slice(0, 2) as pName}
                      <a href="#" class="badge text-bg-primary text-truncate text-decoration-none focus-ring" style="max-width: 100px;" title="Düzenle" onclick={(e) => e.preventDefault()}>{pName}</a>
                    {/each}
                    {#if discount.products.length > 2}
                      <span
                        class="badge text-bg-secondary cursor-pointer"
                        data-bs-toggle="popover"
                        data-bs-trigger="hover focus"
                        data-bs-placement="top"
                        data-bs-content={discount.products.slice(2).join(', ')}
                        title="Diğer Ürünler"
                        onclick={(e) => e.stopPropagation()}>
                        +{discount.products.length - 2}
                      </span>
                    {/if}
                  {/if}
                </div>
              </td>
              <td>
                <span class="font-monospace">
                  {#if discount.limit === 'Limitsiz'}
                    {discount.usedCount} / ∞
                  {:else}
                    {discount.usedCount} / {discount.limit}
                  {/if}
                </span>
              </td>
              <td>
                {#if discount.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}
              </td>
              <td>
                {#if discount.expiry === 'Süresiz'}
                  <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">Süresiz</span>
                {:else}
                  <div class="d-flex flex-column lh-sm">
                    <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">Bşl: {discount.startDate}</span>
                    <span class="font-monospace" style="font-size: 0.85rem;">Bti: {discount.expiry}</span>
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
