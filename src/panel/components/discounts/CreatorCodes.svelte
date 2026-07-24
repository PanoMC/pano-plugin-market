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
    creatorCodes = [],
    creatorCodeCount = 0,
    page = 1,
    totalPage = 1,
    search = '',
    status = 'all', // 'all' | 'ACTIVE' | 'INACTIVE'
    section = 'creators',
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

  // Plain decimal money -> "725 {symbol}" using the dynamic SALES-currency symbol.
  function formatMoney(value, symbol) {
    const num = Number(value ?? 0);
    return new Intl.NumberFormat('tr-TR').format(num) + ' ' + symbol;
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

  async function deleteCreatorCode(item) {
    if (!window.confirm($_('discounts.creators.confirm-delete', { values: { code: item.code } }))) {
      return;
    }

    try {
      const result = await ApiUtil.delete({
        path: `/api/panel/market/creator-codes/${item.id}`,
      });

      if (result.error) throw result.error;

      showToast($_('discounts.creators.toast-delete-success'));
      // The deleted row may have been the last on this page; step back so the
      // reload does not request a now-out-of-range page (backend -> PAGE_NOT_FOUND).
      const targetPage = creatorCodes.length === 1 && page > 1 ? page - 1 : page;
      await navigate({ page: targetPage });
    } catch (e) {
      console.error('[Market] Failed to delete creator code', e);
      showToast($_('discounts.creators.toast-delete-error'));
    }
  }

  function onPageClick(pageNum) {
    navigate({ page: pageNum });
  }
</script>

<div class="card">
  <CardHeader>
    <div slot="left">
      {$_('discounts.creators.heading-count', { values: { count: creatorCodeCount } })}
    </div>
    <div slot="middle" style="width: 250px;">
      <SearchInput
        initialValue={search}
        searching={isSearching}
        placeholderKey="plugins.pano-plugin-market.search.creator-codes"
        onchange={onSearchChange} />
    </div>
    <CardFilters slot="right">
      <CardFiltersItem button active={status === 'all'} onclick={() => onStatusFilter('all')}>{$_('common.all')}</CardFiltersItem>
      <CardFiltersItem button active={status === 'ACTIVE'} onclick={() => onStatusFilter('ACTIVE')}>{$_('common.active')}</CardFiltersItem>
      <CardFiltersItem button active={status === 'INACTIVE'} onclick={() => onStatusFilter('INACTIVE')}>{$_('common.inactive')}</CardFiltersItem>
    </CardFilters>
  </CardHeader>

  {#if creatorCodes.length === 0}
    <NoContent />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover align-middle text-nowrap">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col">{$_('discounts.creators.table.creator')}</th>
            <th scope="col">{$_('discounts.creators.table.code')}</th>
            <th scope="col">{$_('discounts.creators.table.discount-rate')}</th>
            <th scope="col">{$_('discounts.creators.table.commission')}</th>
            <th scope="col">{$_('discounts.creators.table.usage')}</th>
            <th scope="col">{$_('discounts.creators.table.earnings')}</th>
            <th scope="col">{$_('common.status')}</th>
            <th scope="col">{$_('discounts.creators.table.validity')}</th>
          </tr>
        </thead>
        <tbody>
          {#each creatorCodes as item (item.id)}
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
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal" onclick={() => onEdit(item)}>
                      <i class="fas fa-pen me-2"></i>
                      {$_('common.edit')}
                    </button>
                    <button type="button" class="dropdown-item text-danger" onclick={() => deleteCreatorCode(item)}>
                      <i class="fas fa-trash me-2"></i>
                      {$_('common.delete')}
                    </button>
                  </div>
                </div>
              </th>
              <td>
                <a href="#" class="text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal" onclick={(e) => { e.preventDefault(); onEdit(item); }}>
                  {item.creator}
                </a>
              </td>
              <td>
                <a href="#" class="font-monospace text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal" onclick={(e) => { e.preventDefault(); onEdit(item); }}>
                  {item.code}
                </a>
              </td>
              <td>
                <span class="font-monospace">
                  {#if item.unit === 'PERCENT'}
                    %{item.discount}
                  {:else}
                    {item.discount} {currencySymbol}
                  {/if}
                </span>
              </td>
              <td>
                <span class="font-monospace text-success">%{item.commissionPercent}</span>
              </td>
              <td>
                <span class="font-monospace">
                  {#if item.redeemLimit == null}
                    {item.usedCount ?? 0} / ∞
                  {:else}
                    {item.usedCount ?? 0} / {item.redeemLimit}
                  {/if}
                </span>
              </td>
              <td>
                <span class="font-monospace">{formatMoney(item.earnings, currencySymbol)}</span>
              </td>
              <td>
                {#if item.status === 'ACTIVE'}
                  <span class="badge text-bg-success">{$_('common.active')}</span>
                {:else}
                  <span class="badge text-bg-danger">{$_('common.inactive')}</span>
                {/if}
              </td>
              <td>
                {#if !item.expiryDate}
                  <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">{$_('discounts.creators.no-expiry')}</span>
                {:else}
                  <div class="d-flex flex-column lh-sm">
                    <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">{$_('discounts.creators.start-date', { values: { date: formatDate(item.startDate) } })}</span>
                    <span class="font-monospace" style="font-size: 0.85rem;">{$_('discounts.creators.end-date', { values: { date: formatDate(item.expiryDate) } })}</span>
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
