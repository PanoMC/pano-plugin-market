<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, NoContent, SearchInput, Pagination } from '@panomc/sdk/components/panel';
  import { _ } from '../../i18n';
  import CreateCouponModal from '../components/modals/CreateCouponModal.svelte';

  let page = $state(1);
  let coupons = $state([
    { id: 1, code: 'YAZ2024', discount: 20, usage: 45, usageLimit: 100, status: 'active', expiry: '31 Ağu 2024' },
    { id: 2, code: 'HOSGELDIN', discount: 50, usage: 120, usageLimit: null, status: 'active', expiry: null },
    { id: 3, code: 'VIPINDIRIM', discount: 15, usage: 5, usageLimit: 10, status: 'active', expiry: '15 Eyl 2024' },
    { id: 4, code: 'ESKIKUPON', discount: 30, usage: 100, usageLimit: 100, status: 'inactive', expiry: '01 Oca 2024' },
  ]);
  let search = $state('');

  function onPageClick(pageNum) {
    page = pageNum;
  }
</script>

<MarketLayout>
  {#snippet right()}
    <button type="button" class="btn btn-secondary border-0" data-bs-toggle="modal" data-bs-target="#createCouponModal">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">Kupon Oluştur</span>
    </button>
  {/snippet}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {coupons.length} Kupon
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={search}
          placeholder="Kupon ara..."
          onchange={(val) => (search = val)} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={true}>Tümü</CardFiltersItem>
        <CardFiltersItem button>Aktif</CardFiltersItem>
        <CardFiltersItem button>Pasif</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if coupons.length === 0}
      <NoContent />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th scope="col" style="width: 50px;"></th>
              <th scope="col">Kupon Kodu</th>
              <th scope="col">İndirim</th>
              <th scope="col">Kullanım</th>
              <th scope="col">Durum</th>
              <th scope="col">Son Kullanma</th>
            </tr>
          </thead>
          <tbody>
            {#each coupons as coupon}
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
                  <span class="font-monospace">{coupon.code}</span>
                </td>
                <td>
                  <span class="fw-medium">%{coupon.discount}</span>
                </td>
                <td>
                  {coupon.usage} / {coupon.usageLimit ? coupon.usageLimit : 'Sınırsız'}
                </td>
                <td>
                  {#if coupon.status === 'active'}
                    <span class="badge text-bg-success">Aktif</span>
                  {:else}
                    <span class="badge text-bg-danger">Pasif</span>
                  {/if}
                </td>
                <td>
                  {coupon.expiry ? coupon.expiry : 'Sınırsız'}
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

<CreateCouponModal />
