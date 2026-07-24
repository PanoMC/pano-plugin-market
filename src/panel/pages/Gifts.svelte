<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams },
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.gifts.title');

    const pageNum = parseInt(searchParams.get('page')) || 1;
    const search = searchParams.get('search');
    const statusParam = searchParams.get('status');

    const fetchPage = (p) =>
      ApiUtil.get({
        path:
          '/api/panel/market/gifts' +
          buildQueryParams({
            page: p === 1 ? null : p,
            search,
            status: statusParam,
          }),
        request: event,
      });

    let effectivePage = pageNum;
    let body = await fetchPage(pageNum);

    // A stale ?page= (bookmark / back-button after deletes) points past the last
    // page; fall back to page 1 with the same filters instead of faking an empty store.
    if (body?.error === 'PAGE_NOT_FOUND' && pageNum > 1) {
      effectivePage = 1;
      body = await fetchPage(1);
    }

    if (!body || body.error) {
      return {
        data: {
          gifts: [],
          giftCount: 0,
          totalPage: 1,
          page: 1,
          error: body?.error || 'NETWORK_ERROR',
        },
      };
    }

    body.page = effectivePage;
    return { data: body };
  }
</script>

<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, NoContent, SearchInput, Pagination } from '@panomc/sdk/components/panel';
  import { showToast } from '@panomc/sdk/toasts';
  import { base, goto, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import CreateGiftModal from '../components/modals/CreateGiftModal.svelte';

  let { data } = $props();

  // Data comes straight from load(); the panel host remounts this view
  // ({#key data}) whenever load() re-runs, so we render load()'s result directly.
  let gifts = $derived(data.gifts || []);
  let giftCount = $derived(data.giftCount ?? (data.gifts?.length ?? 0));
  let totalPage = $derived(data.totalPage ?? 1);
  let currentPage = $derived(data.page || 1);
  let loadError = $derived(data.error || null);

  // All list state (page / search / status) lives in the URL query params.
  let search = $derived($page.url.searchParams.get('search') || '');
  let statusFilter = $derived($page.url.searchParams.get('status') || 'all'); // 'all' | 'ACTIVE' | 'INACTIVE'

  let isSearching = $state(false);

  let isEditModal = $state(false);
  let selectedGift = $state(null);

  function openCreateModal() {
    isEditModal = false;
    selectedGift = null;
  }

  function openEditModal(gift) {
    isEditModal = true;
    selectedGift = gift;
  }

  // Epoch millis -> Turkish short date (e.g. "01 Ara 2024"); null when unset.
  function formatGiftDate(epoch) {
    if (!epoch) return null;
    return new Date(epoch).toLocaleDateString('tr-TR', {
      day: '2-digit',
      month: 'short',
      year: 'numeric',
    });
  }

  // Merge current URL state with overrides into a gifts query string.
  // page 1 / status all / empty search are omitted (buildQueryParams drops falsy).
  function buildQuery({ page: pageNum = currentPage, search: searchVal = search, status = statusFilter } = {}) {
    return buildQueryParams({
      page: pageNum && Number(pageNum) > 1 ? pageNum : null,
      search: searchVal || null,
      status: status && status !== 'all' ? status : null,
    });
  }

  // Navigate to the gifts route with the merged query; invalidateAll re-runs load().
  function navigate(overrides = {}) {
    return goto(`${base}/market/gifts${buildQuery(overrides)}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true,
    });
  }

  // href for a status pill: preserves the current search and resets to page 1.
  function statusHref(status) {
    return `/market/gifts${buildQueryParams({
      search: search || null,
      status: status === 'all' ? null : status,
    })}`;
  }

  function refreshData() {
    return navigate();
  }

  async function onSearchChange(value) {
    isSearching = true;
    try {
      await navigate({ page: 1, search: value });
    } finally {
      isSearching = false;
    }
  }

  async function deleteGift(gift) {
    if (!window.confirm($_('pages.gifts.confirm-delete', { values: { code: gift.code } }))) {
      return;
    }

    try {
      const result = await ApiUtil.delete({
        path: `/api/panel/market/gifts/${gift.id}`,
      });

      if (result.error) throw result.error;

      showToast($_('pages.gifts.toast-delete-success'));
      // The deleted row may have been the last on this page; step back so the
      // refetch does not request a now-out-of-range page (backend -> PAGE_NOT_FOUND).
      const targetPage = gifts.length === 1 && currentPage > 1 ? currentPage - 1 : currentPage;
      await navigate({ page: targetPage });
    } catch (e) {
      console.error('[Market] Failed to delete gift', e);
      showToast($_('pages.gifts.toast-delete-error'));
    }
  }

  function onPageClick(pageNum) {
    return navigate({ page: pageNum });
  }
</script>

<MarketLayout>
  {#snippet right()}
    <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createGiftModal" onclick={openCreateModal}>
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">{$_('pages.gifts.create-gift')}</span>
    </button>
  {/snippet}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.gifts.gift-count', { values: { count: giftCount } })}
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={search}
          searching={isSearching}
          placeholderKey="plugins.pano-plugin-market.search.gifts"
          onchange={onSearchChange} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem href={statusHref('all')} active={statusFilter === 'all'}>{$_('common.all')}</CardFiltersItem>
        <CardFiltersItem href={statusHref('ACTIVE')} active={statusFilter === 'ACTIVE'}>{$_('common.active')}</CardFiltersItem>
        <CardFiltersItem href={statusHref('INACTIVE')} active={statusFilter === 'INACTIVE'}>{$_('common.inactive')}</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if loadError}
      <div class="text-center text-body-secondary py-5">
        <i class="fas fa-triangle-exclamation mb-2 fs-3"></i>
        <div>{$_('pages.gifts.load-error')}</div>
      </div>
    {:else if gifts.length === 0}
      <NoContent />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th scope="col" style="width: 50px;"></th>
              <th scope="col">{$_('pages.gifts.table.code')}</th>
              <th scope="col">{$_('pages.gifts.table.products')}</th>
              <th scope="col">{$_('common.status')}</th>
              <th scope="col">{$_('pages.gifts.table.validity')}</th>
            </tr>
          </thead>
          <tbody>
            {#each gifts as gift (gift.id)}
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
                      <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createGiftModal" onclick={() => openEditModal(gift)}>
                        <i class="fas fa-pen me-2"></i>
                        {$_('common.edit')}
                      </button>
                      <button type="button" class="dropdown-item text-danger" onclick={() => deleteGift(gift)}>
                        <i class="fas fa-trash me-2"></i>
                        {$_('common.delete')}
                      </button>
                    </div>
                  </div>
                </th>
                <td>
                  <a href="#" class="font-monospace text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createGiftModal" onclick={(e) => { e.preventDefault(); openEditModal(gift); }}>
                    {gift.code}
                  </a>
                </td>
                <td>
                  <a href="#" class="text-decoration-none focus-ring d-inline-block" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createGiftModal" onclick={(e) => { e.preventDefault(); openEditModal(gift); }}>
                    {#if gift.type === 'CREDIT'}
                      <span class="badge bg-primary px-2.5 py-1.5 fw-medium cursor-pointer"><i class="fas fa-coins me-1"></i>{$_('pages.gifts.credit-amount', { values: { amount: gift.creditAmount } })}</span>
                    {:else if gift.type === 'RANDOM'}
                      <span class="badge bg-primary px-2.5 py-1.5 fw-medium cursor-pointer"><i class="fas fa-shuffle me-1"></i>{$_('pages.gifts.random-gift', { values: { count: gift.productNames?.length ?? 0 } })}</span>
                    {:else}
                      <span class="badge bg-primary px-2.5 py-1.5 fw-medium cursor-pointer"><i class="fas fa-box-open me-1"></i>{gift.productName || '-'}</span>
                    {/if}
                  </a>
                </td>
                <td>
                  {#if gift.status === 'ACTIVE'}
                    <span class="badge text-bg-success">{$_('common.active')}</span>
                  {:else}
                    <span class="badge text-bg-danger">{$_('common.inactive')}</span>
                  {/if}
                </td>
                <td>
                  {#if !gift.expiryDate}
                    <span class="text-body-secondary font-monospace" style="font-size: 0.85rem;">{$_('pages.gifts.no-expiry')}</span>
                  {:else}
                    <div class="d-flex flex-column lh-sm">
                      <span class="text-body-secondary font-monospace" style="font-size: 0.75rem;">{$_('pages.gifts.start-date-label', { values: { date: formatGiftDate(gift.startDate) || '-' } })}</span>
                      <span class="font-monospace" style="font-size: 0.85rem;">{$_('pages.gifts.end-date-label', { values: { date: formatGiftDate(gift.expiryDate) } })}</span>
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
            page={currentPage}
            {totalPage}
            on:firstPageClick={() => onPageClick(1)}
            on:lastPageClick={() => onPageClick(totalPage)}
            on:pageLinkClick={(event) => onPageClick(event.detail.page)} />
      </div>
    {/if}
  </div>
</MarketLayout>

<CreateGiftModal isEdit={isEditModal} gift={selectedGift} onSaved={refreshData} />
