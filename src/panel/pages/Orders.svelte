<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';

  let page = $state(1);
  let search = $state('');
  let isSearching = $state(false);
  let statusFilter = $state('all'); // all | success | danger

  // Mock data for design
  const latestSales = [
    { id: 1050, player: 'Kemal', products: ['100 Kredi'], price: '10.00 ₺', date: 'Bugün, 16:10', payment: 'Kredi Kartı: Tebex', status: 'success' },
    { id: 1049, player: 'Ahmet', products: ['VIP+ (Limitsiz)', 'Giriş Mesajı', 'Özel Kanat', 'Efekt Paketi'], price: '350.00 ₺', date: 'Bugün, 15:55', payment: 'EFT: Havale', status: 'success' },
    { id: 1048, player: 'Mehmet', products: ['Kasa Anahtarı x10'], price: '45.00 ₺', date: 'Bugün, 15:30', payment: 'Mobil Ödeme', status: 'danger' },
    { id: 1047, player: 'Okan', products: ['500 Kredi'], price: '50.00 ₺', date: 'Bugün, 14:50', payment: 'Kredi Kartı: Shopier', status: 'success' },
    { id: 1046, player: 'Can', products: ['VIP (Aylık)'], price: '45.00 ₺', date: 'Bugün, 14:35', payment: 'Kredi Kartı: Stripe', status: 'success' },
    { id: 1045, player: 'Selim', products: ['VIP+ (Aylık)'], price: '75.00 ₺', date: 'Bugün, 14:20', payment: 'EFT: Havale', status: 'success' },
    { id: 1044, player: 'Cihan', products: ['1000 Kredi', 'Ek Renk'], price: '110.00 ₺', date: 'Bugün, 12:45', payment: 'Kredi Kartı: Tebex', status: 'success' },
    { id: 1043, player: 'Eren', products: ['Kasa Anahtarı x5'], price: '25.00 ₺', date: 'Dün, 23:10', payment: 'Mobil Ödeme', status: 'success' },
    { id: 1042, player: 'Yavuz', products: ['VIP (Limitsiz)'], price: '250.00 ₺', date: 'Dün, 18:30', payment: 'Kredi Kartı: Shopier', status: 'success' },
    { id: 1041, player: 'Mert', products: ['İsim Değiştirme'], price: '15.00 ₺', date: '2 gün önce', payment: 'EFT: Havale', status: 'danger' }
  ];

  const filteredSales = $derived.by(() => {
    const term = search.trim().toLowerCase();
    return latestSales.filter((sale) => {
      if (statusFilter !== 'all' && sale.status !== statusFilter) return false;
      if (!term) return true;
      return (
        sale.player.toLowerCase().includes(term) ||
        sale.id.toString().includes(term) ||
        sale.payment.toLowerCase().includes(term) ||
        sale.products.some(p => p.toLowerCase().includes(term))
      );
    });
  });

  function onPageClick(pageNum) {
    page = pageNum;
  }

  function copyToClipboard(text) {
    navigator.clipboard.writeText(text);
  }
</script>

<MarketLayout>
  <div class="card animate__animated animate__fadeIn">
    <CardHeader>
      <div slot="left">
        {filteredSales.length} Sipariş
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={search}
          searching={isSearching}
          debounceMs={300}
          onchange={(e) => {
            search = e;
          }} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button onclick={() => statusFilter = 'all'} active={statusFilter === 'all'}>
          Tümü
        </CardFiltersItem>
        <CardFiltersItem button onclick={() => statusFilter = 'success'} active={statusFilter === 'success'}>
          Başarılı
        </CardFiltersItem>
        <CardFiltersItem button onclick={() => statusFilter = 'danger'} active={statusFilter === 'danger'}>
          İade
        </CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if filteredSales.length === 0}
      <div class="text-center text-body-secondary py-5">
        <i class="fas fa-circle-info mb-2 fs-3"></i>
        <div>Aradığınız kriterlere uygun sipariş bulunamadı.</div>
      </div>
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle mb-0">
          <thead>
            <tr>
              <th class="ps-3" style="width: 120px;">Sipariş ID</th>
              <th>Oyuncu</th>
              <th>Ürün</th>
              <th>Fiyat</th>
              <th>Ödeme Yöntemi</th>
              <th>Durum</th>
              <th class="pe-3">Tarih</th>
            </tr>
          </thead>
          <tbody>
            {#each filteredSales as sale (sale.id)}
              <tr>
                <td class="ps-3">
                  <button 
                    type="button"
                    class="btn btn-link p-0 text-decoration-none font-monospace user-select-all cursor-pointer focus-ring rounded border-0" 
                    title="Kopyala"
                    onclick={() => copyToClipboard(sale.id)}>
                    #{sale.id}
                  </button>
                </td>
                <td>
                  <a href="/players/{sale.player}" class="text-decoration-none d-flex align-items-center focus-ring rounded" title="Görüntüle">
                    <img src="https://minotar.net/avatar/{sale.player}/24" class="rounded-circle me-2" style="width: 24px; height: 24px;" alt={sale.player} />
                    <span>{sale.player}</span>
                  </a>
                </td>
                <td>
                  <div class="d-flex flex-wrap gap-1 align-items-center">
                    {#if sale.products.length > 0}
                      <span class="badge text-bg-primary focus-ring rounded" title="Görüntüle">
                        {sale.products[0]}
                      </span>
                      {#if sale.products.length > 1}
                        <span 
                          class="badge text-bg-secondary cursor-help rounded-pill" 
                          data-bs-toggle="popover"
                          data-bs-trigger="hover focus"
                          data-bs-placement="top"
                          data-bs-html="true"
                          data-bs-content={sale.products.slice(1).map(p => `<span class='badge text-bg-primary me-1'>${p}</span>`).join('')}>
                          +{sale.products.length - 1}
                        </span>
                      {/if}
                    {/if}
                  </div>
                </td>
                <td>
                  <span class="badge text-bg-secondary focus-ring rounded" title="Düzenle">
                    {sale.price}
                  </span>
                </td>
                <td>
                  {sale.payment}
                </td>
                <td>
                  {#if sale.status === 'success'}
                    <span class="badge text-bg-success">Başarılı</span>
                  {:else if sale.status === 'danger'}
                    <span class="badge text-bg-danger">İade</span>
                  {/if}
                </td>
                <td class="pe-3">{sale.date}</td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>

      <div class="card-footer">
        <Pagination
          {page}
          totalPage={10}
          on:firstPageClick={() => onPageClick(1)}
          on:lastPageClick={() => onPageClick(10)}
          on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
      </div>
    {/if}
  </div>
</MarketLayout>
