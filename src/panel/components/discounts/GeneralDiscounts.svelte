<script>
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput, Pagination, NoContent } from '@panomc/sdk/components/panel';
  import { base, goto } from '@panomc/sdk/svelte';
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';
  import { _, showSuccessToast, showErrorToast } from '../../../i18n';

  // All view state (page/search/status) is URL-driven: the page load() reads the
  // query params and passes the resulting list + the current filter values down as
  // props. Filter pills, search and pagination navigate via goto() to update the
  // URL, which re-runs load(). `section` keeps ?section= in the URL across those
  // navigations so the active tab and back button stay correct.
  let {
    discounts = [],
    discountCount = 0,
    page = 1,
    totalPage = 1,
    search = '',
    status = 'all', // 'all' | 'ACTIVE' | 'INACTIVE'
    section = 'general',
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

  async function deleteDiscount(discount) {
    if (!window.confirm($_('discounts.general.confirm-delete', { values: { name: discount.name } }))) {
      return;
    }

    try {
      const result = await ApiUtil.delete({
        path: `/api/panel/market/discounts/${discount.id}`,
      });

      if (result.error) throw result.error;

      showSuccessToast($_('discounts.general.toast-delete-success'));
      // The deleted row may have been the last on this page; step back so the
      // reload does not request a now-out-of-range page (backend -> PAGE_NOT_FOUND).
      const targetPage = discounts.length === 1 && page > 1 ? page - 1 : page;
      await navigate({ page: targetPage });
    } catch (e) {
      console.error('[Market] Failed to delete discount', e);
      showErrorToast($_('discounts.general.toast-delete-error'));
    }
  }

  function onPageClick(pageNum) {
    navigate({ page: pageNum });
  }

  $effect(() => {
    // Whenever the discount list changes, re-initialize Bootstrap popovers.
    const unused = discounts;
    if (typeof window !== 'undefined' && window.bootstrap) {
      let popovers = [];
      const timer = setTimeout(() => {
        const popoverTriggerList = document.querySelectorAll('[data-bs-toggle="popover"]');
        popovers = [...popoverTriggerList].map(el => new window.bootstrap.Popover(el));
      }, 50);
      return () => {
        clearTimeout(timer);
        popovers.forEach(p => p.dispose());
      };
    }
  });
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {$_('discounts.general.count', { values: { count: discountCount } })}
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={search}
        searching={isSearching}
        placeholderKey="plugins.pano-plugin-market.search.discounts"
        onchange={onSearchChange} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={status === 'all'} onclick={() => onStatusFilter('all')}>{$_('common.all')}</CardFiltersItem>
      <CardFiltersItem button active={status === 'ACTIVE'} onclick={() => onStatusFilter('ACTIVE')}>{$_('common.active')}</CardFiltersItem>
      <CardFiltersItem button active={status === 'INACTIVE'} onclick={() => onStatusFilter('INACTIVE')}>{$_('common.inactive')}</CardFiltersItem>
    </CardFilters>
  </CardHeader>

  {#if discounts.length === 0}
    <NoContent />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle text-nowrap">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col">{$_('discounts.general.table.name')}</th>
            <th scope="col">{$_('discounts.general.table.value')}</th>
            <th scope="col">{$_('discounts.general.table.min-cart')}</th>
            <th scope="col">{$_('discounts.general.table.products')}</th>
            <th scope="col">{$_('discounts.general.table.usage')}</th>
            <th scope="col">{$_('common.status')}</th>
            <th scope="col">{$_('discounts.general.table.validity')}</th>
          </tr>
        </thead>
        <tbody>
          {#each discounts as discount (discount.id)}
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
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createDiscountModal" onclick={() => onEdit(discount)}>
                      <i class="fas fa-pen me-2"></i>
                      {$_('common.edit')}
                    </button>
                    <button type="button" class="dropdown-item text-danger" onclick={() => deleteDiscount(discount)}>
                      <i class="fas fa-trash me-2"></i>
                      {$_('common.delete')}
                    </button>
                  </div>
                </div>
              </th>
              <td>
                <a href="#" class="text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createDiscountModal" onclick={(e) => { e.preventDefault(); onEdit(discount); }}>
                  {discount.name}
                </a>
              </td>
              <td>
                <span class="font-monospace">
                  {#if discount.unit === 'PERCENT'}
                    %{discount.value}
                  {:else}
                    {discount.value} {currencySymbol}
                  {/if}
                </span>
              </td>
              <td>
                <span class="font-monospace">
                  {#if discount.minPaymentAmount == null}
                    -
                  {:else}
                    {discount.minPaymentAmount} {currencySymbol}
                  {/if}
                </span>
              </td>
              <td class="cursor-pointer" data-bs-toggle="modal" data-bs-target="#createDiscountModal" onclick={() => onEdit(discount)}>
                <div class="d-flex align-items-center gap-1 flex-nowrap" style="max-width: 250px;">
                  {#if !discount.products || discount.products.includes('all') || discount.products.length === 0}
                    <a href="#" class="badge text-bg-primary text-truncate text-decoration-none focus-ring" title={$_('common.edit')} onclick={(e) => e.preventDefault()}>{$_('discounts.general.all-products')}</a>
                  {:else}
                    {#each discount.products.slice(0, 2) as pName}
                      <a href="#" class="badge text-bg-primary text-truncate text-decoration-none focus-ring" style="max-width: 100px;" title={$_('common.edit')} onclick={(e) => e.preventDefault()}>{pName}</a>
                    {/each}
                    {#if discount.products.length > 2}
                      <span
                        class="badge text-bg-secondary cursor-pointer"
                        data-bs-toggle="popover"
                        data-bs-trigger="hover focus"
                        data-bs-placement="top"
                        data-bs-content={discount.products.slice(2).join(', ')}
                        title={$_('discounts.general.other-products')}
                        onclick={(e) => e.stopPropagation()}>
                        +{discount.products.length - 2}
                      </span>
                    {/if}
                  {/if}
                </div>
              </td>
              <td>
                <span class="font-monospace">
                  {#if discount.usageLimit == null}
                    {discount.usedCount ?? 0} / ∞
                  {:else}
                    {discount.usedCount ?? 0} / {discount.usageLimit}
                  {/if}
                </span>
              </td>
              <td>
                {#if discount.status === 'ACTIVE'}
                  <span class="badge text-bg-success">{$_('common.active')}</span>
                {:else}
                  <span class="badge text-bg-danger">{$_('common.inactive')}</span>
                {/if}
              </td>
              <td>
                {#if !discount.expiryDate}
                  <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">{$_('discounts.general.no-expiry')}</span>
                {:else}
                  <div class="d-flex flex-column lh-sm">
                    <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">{$_('discounts.general.start-label', { values: { date: formatDate(discount.startDate) } })}</span>
                    <span class="font-monospace" style="font-size: 0.85rem;">{$_('discounts.general.end-label', { values: { date: formatDate(discount.expiryDate) } })}</span>
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
