<script>
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput, Pagination, NoContent } from '@panomc/sdk/components/panel';
  import { showToast } from '@panomc/sdk/toasts';
  import { base, goto } from '@panomc/sdk/svelte';
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';

  // All view state (page/search/status) is URL-driven: the page load() reads the
  // query params and passes the resulting list + the current filter values down as
  // props. Filter pills, search and pagination navigate via goto() to update the
  // URL, which re-runs load(). `section` keeps ?section= in the URL across those
  // navigations so the active tab and back button stay correct.
  let {
    coupons = [],
    couponCount = 0,
    page = 1,
    totalPage = 1,
    search = '',
    status = 'all', // 'all' | 'ACTIVE' | 'INACTIVE'
    section = 'coupons',
    currencySymbol = '', // dynamic SALES-currency symbol from GET /settings
    onEdit = () => {},
  } = $props();

  // Reflects the in-flight goto() so SearchInput keeps showing its spinner.
  let isSearching = $state(false);

  // Epoch millis -> Turkish short date (e.g. "01 Haz 2026"); '-' when unset.
  function formatDate(epoch) {
    if (!epoch) return '-';
    return new Date(epoch).toLocaleDateString('tr-TR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  }

  // Navigate to the discounts list URL for the given page/search/status, keeping
  // the current section. Omits page 1, empty search and the 'all' status so the URL
  // stays clean (announcement/FAQ idiom). load() re-runs and refetches.
  function navigate({ page: p = page, search: s = search, status: st = status } = {}) {
    const queryParams = buildQueryParams({
      section,
      page: p && p !== 1 ? p : null,
      search: s || null,
      status: st && st !== 'all' ? st : null,
    });
    isSearching = true;
    return goto(`${base}/market/discounts${queryParams}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true,
    });
  }

  function onSearchChange(value) {
    navigate({ search: value, page: 1 });
  }

  function onStatusFilter(value) {
    navigate({ status: value, page: 1 });
  }

  async function deleteCoupon(coupon) {
    if (!window.confirm($_('discounts.coupons.confirm-delete', { values: { code: coupon.code } }))) {
      return;
    }

    try {
      const result = await ApiUtil.delete({
        path: `/api/panel/market/coupons/${coupon.id}`,
      });

      if (result.error) throw result.error;

      showToast($_('discounts.coupons.toast-delete-success'));
      // The deleted row may have been the last on this page; step back so the
      // reload does not request a now-out-of-range page (backend -> PAGE_NOT_FOUND).
      const targetPage = coupons.length === 1 && page > 1 ? page - 1 : page;
      await navigate({ page: targetPage });
    } catch (e) {
      console.error('[Market] Failed to delete coupon', e);
      showToast($_('discounts.coupons.toast-delete-error'));
    }
  }

  function onPageClick(pageNum) {
    navigate({ page: pageNum });
  }
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {$_('discounts.coupons.count-heading', { values: { count: couponCount } })}
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={search}
        searching={isSearching}
        placeholderKey="plugins.pano-plugin-market.search.coupons"
        onchange={onSearchChange} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={status === 'all'} onclick={() => onStatusFilter('all')}>{$_('common.all')}</CardFiltersItem>
      <CardFiltersItem button active={status === 'ACTIVE'} onclick={() => onStatusFilter('ACTIVE')}>{$_('common.active')}</CardFiltersItem>
      <CardFiltersItem button active={status === 'INACTIVE'} onclick={() => onStatusFilter('INACTIVE')}>{$_('common.inactive')}</CardFiltersItem>
    </CardFilters>
  </CardHeader>

  {#if coupons.length === 0}
    <NoContent />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle text-nowrap">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col">{$_('discounts.coupons.table.code')}</th>
            <th scope="col">{$_('discounts.coupons.table.discount-rate')}</th>
            <th scope="col">{$_('discounts.coupons.table.usage')}</th>
            <th scope="col">{$_('common.status')}</th>
            <th scope="col">{$_('discounts.coupons.table.validity')}</th>
          </tr>
        </thead>
        <tbody>
          {#each coupons as coupon (coupon.id)}
            <tr>
              <th scope="row">
                <div class="dropdown position-static">
                  <button
                    type="button"
                    class="btn btn-link"
                    data-bs-toggle="dropdown"
                    title={$_('common.actions')}
                    aria-label={$_('common.actions')}>
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createCouponModal" onclick={() => onEdit(coupon)}>
                      <i class="fas fa-pen me-2"></i>
                      {$_('common.edit')}
                    </button>
                    <button type="button" class="dropdown-item text-danger" onclick={() => deleteCoupon(coupon)}>
                      <i class="fas fa-trash me-2"></i>
                      {$_('common.delete')}
                    </button>
                  </div>
                </div>
              </th>
              <td>
                <a href="#" class="font-monospace text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createCouponModal" onclick={(e) => { e.preventDefault(); onEdit(coupon); }}>
                  {coupon.code}
                </a>
              </td>
              <td>
                <span class="font-monospace">
                  {#if coupon.unit === 'PERCENT'}
                    %{coupon.discount}
                  {:else}
                    {coupon.discount} {currencySymbol}
                  {/if}
                </span>
              </td>
              <td>
                <span class="font-monospace">
                  {#if coupon.redeemLimit == null}
                    {coupon.usedCount ?? 0} / ∞
                  {:else}
                    {coupon.usedCount ?? 0} / {coupon.redeemLimit}
                  {/if}
                </span>
              </td>
              <td>
                {#if coupon.status === 'ACTIVE'}
                  <span class="badge text-bg-success">{$_('common.active')}</span>
                {:else}
                  <span class="badge text-bg-danger">{$_('common.inactive')}</span>
                {/if}
              </td>
              <td>
                {#if !coupon.expiryDate}
                  <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">{$_('discounts.coupons.validity-none')}</span>
                {:else}
                  <div class="d-flex flex-column lh-sm">
                    <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">{$_('discounts.coupons.validity-start', { values: { date: formatDate(coupon.startDate) } })}</span>
                    <span class="font-monospace" style="font-size: 0.85rem;">{$_('discounts.coupons.validity-end', { values: { date: formatDate(coupon.expiryDate) } })}</span>
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
          {totalPage}
          on:firstPageClick={() => onPageClick(1)}
          on:lastPageClick={() => onPageClick(totalPage)}
          on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
    </div>
  {/if}
</div>
