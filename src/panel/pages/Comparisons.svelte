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

    pageTitle.set('plugins.pano-plugin-market.pages.comparisons.title');

    const pageNum = Number(searchParams.get('page')) || 1;
    const search = searchParams.get('search');
    const status = searchParams.get('status');

    const fetchPage = (p) =>
      ApiUtil.get({
        path:
          '/api/panel/market/comparisons' +
          buildQueryParams({
            page: p === 1 ? null : p,
            search,
            status,
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
          comparisons: [],
          comparisonCount: 0,
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
  import { CardHeader, CardFilters, CardFiltersItem, Pagination, SearchInput, NoContent } from '@panomc/sdk/components/panel';
  import { base, page, goto } from '@panomc/sdk/svelte';
  import { _, showSuccessToast, showErrorToast } from '../../i18n';

  import ConfirmModal from '../components/ConfirmModal.svelte';
  import { currentLocale } from '../utils/locale.js';

  let { data } = $props();
  let confirmModal = $state(null);

  const FILTER_STATUS = {
    all: null,
    active: 'ACTIVE',
    inactive: 'INACTIVE'
  };

  // Data comes straight from load(); the panel host remounts this view
  // ({#key data}) whenever load() re-runs, so we render load()'s result directly.
  let comparisons = $derived(data.comparisons || []);
  let comparisonCount = $derived(data.comparisonCount || 0);
  let totalPage = $derived(data.totalPage || 1);
  let currentPage = $derived(data.page || 1);
  let loadError = $derived(data.error || null);

  // All list state (page / search / status) lives in the URL query params.
  let currentSearch = $derived($page.url.searchParams.get('search') || '');
  let currentStatus = $derived($page.url.searchParams.get('status'));
  let filter = $derived(
    currentStatus === 'ACTIVE' ? 'active' : currentStatus === 'INACTIVE' ? 'inactive' : 'all'
  );

  let buttonsLoading = $state(false);
  let searching = $state(false);

  // Merge current URL state with overrides into a comparisons query string.
  // page 1 / empty status / empty search are omitted (buildQueryParams drops falsy).
  function buildQuery({ page: pageNum = currentPage, search = currentSearch, status = currentStatus } = {}) {
    return buildQueryParams({
      page: pageNum && Number(pageNum) > 1 ? pageNum : null,
      search: search || null,
      status: status || null,
    });
  }

  // Navigate to the comparisons route with the merged query; invalidateAll re-runs load().
  function navigate(overrides = {}) {
    return goto(`${base}/market/comparisons${buildQuery(overrides)}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true,
    });
  }

  // href for a filter pill: preserves the current search and resets to page 1.
  function filterHref(newFilter) {
    return `/market/comparisons${buildQueryParams({
      search: currentSearch || null,
      status: FILTER_STATUS[newFilter],
    })}`;
  }

  async function refreshData() {
    await navigate();
  }

  function onPageClick(pageNum) {
    return navigate({ page: pageNum });
  }

  async function onSearchChange(val) {
    searching = true;
    try {
      await navigate({ page: 1, search: val });
    } finally {
      searching = false;
    }
  }

  function editComparison(id) {
    goto(`${base}/market/comparisons/create-comparison?id=${id}`);
  }

  async function cloneComparison(id) {
    if (buttonsLoading) return;
    buttonsLoading = true;
    try {
      const res = await ApiUtil.post({ path: `/api/panel/market/comparisons/${id}/clone` });
      if (res?.error) {
        showErrorToast($_('pages.comparisons.toast-clone-error'));
      } else {
        showSuccessToast($_('pages.comparisons.toast-clone-success'));
        await refreshData();
      }
    } catch (e) {
      console.error('[Market] Failed to clone comparison', e);
      showErrorToast($_('pages.comparisons.toast-clone-error'));
    } finally {
      buttonsLoading = false;
    }
  }

  async function deleteComparison(comp) {
    if (buttonsLoading) return;
    confirmModal?.open({
      icon: 'fa-solid fa-trash',
      variant: 'danger',
      title: $_('pages.comparisons.delete-title'),
      description: $_('pages.comparisons.confirm-delete', { values: { name: comp.name } }),
      confirmLabel: $_('common.delete'),
      onConfirm: () => performDelete(comp),
    });
  }

  async function performDelete(comp) {
    if (buttonsLoading) return;
    buttonsLoading = true;
    try {
      const res = await ApiUtil.delete({ path: `/api/panel/market/comparisons/${comp.id}` });
      if (res?.error) {
        showErrorToast($_('pages.comparisons.toast-delete-error'));
        // Stale row (already deleted elsewhere): refresh the list (13 section 23).
        if (res.error === 'NOT_FOUND') await navigate();
      } else {
        showSuccessToast($_('pages.comparisons.toast-delete-success'));
        // The deleted row may have been the last on this page; step back a page.
        const targetPage =
          comparisons.length === 1 && currentPage > 1 ? currentPage - 1 : currentPage;
        await navigate({ page: targetPage });
      }
    } catch (e) {
      console.error('[Market] Failed to delete comparison', e);
      showErrorToast($_('pages.comparisons.toast-delete-error'));
    } finally {
      buttonsLoading = false;
    }
  }

  function formatDate(value) {
    if (!value) return '-';
    const d = new Date(Number(value));
    if (isNaN(d.getTime())) return '-';
    return d.toLocaleString(currentLocale(), {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit'
    });
  }
</script>

<MarketLayout>
  {#snippet right()}
    <a href="{base}/market/comparisons/create-comparison" class="btn btn-secondary d-flex align-items-center gap-2">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none">{$_('pages.comparisons.create-comparison')}</span>
    </a>
  {/snippet}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.comparisons.count', { values: { count: comparisonCount } })}
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          initialValue={currentSearch}
          {searching}
          placeholderKey="plugins.pano-plugin-market.search.comparisons"
          onchange={onSearchChange} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem href={filterHref('all')} active={filter === 'all'}>{$_('common.all')}</CardFiltersItem>
        <CardFiltersItem href={filterHref('active')} active={filter === 'active'}>{$_('common.active')}</CardFiltersItem>
        <CardFiltersItem href={filterHref('inactive')} active={filter === 'inactive'}>{$_('common.inactive')}</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if loadError}
      <div class="text-center text-body-secondary py-5">
        <i class="fas fa-triangle-exclamation mb-2 fs-3"></i>
        <div>{$_('pages.comparisons.load-failed')}</div>
      </div>
    {:else if comparisons.length === 0}
      <NoContent />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th scope="col" style="width: 60px;"></th>
              <th scope="col" class="text-nowrap">{$_('pages.comparisons.table.name')}</th>
              <th scope="col" class="text-nowrap">{$_('pages.comparisons.table.products')}</th>
              <th scope="col" class="text-nowrap">{$_('pages.comparisons.table.last-updated')}</th>
              <th scope="col" class="text-nowrap text-center">{$_('common.status')}</th>
            </tr>
          </thead>
          <tbody>
            {#each comparisons as comp (comp.id)}
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
                      <button type="button" class="dropdown-item" onclick={() => editComparison(comp.id)}>
                        <i class="fas fa-pen me-2"></i>
                        {$_('common.edit')}
                      </button>
                      <button type="button" class="dropdown-item" onclick={() => cloneComparison(comp.id)}>
                        <i class="fas fa-clone me-2"></i>
                        {$_('common.clone')}
                      </button>
                      <button type="button" class="dropdown-item text-danger" onclick={() => deleteComparison(comp)}>
                        <i class="fas fa-trash me-2"></i>
                        {$_('common.delete')}
                      </button>
                    </div>
                  </div>
                </th>
                <td>
                  <button
                    type="button"
                    title={$_('common.edit')}
                    class="btn btn-link p-0 border-0 text-decoration-none text-start fw-medium focus-ring"
                    onclick={() => editComparison(comp.id)}>
                    {comp.name}
                  </button>
                </td>
                <td>
                  <div class="d-flex flex-wrap gap-1">
                    {#each comp.products as product}
                      <span class="badge text-bg-primary">{product}</span>
                    {/each}
                  </div>
                </td>
                <td class="text-body-secondary small">
                  {formatDate(comp.updatedAt)}
                </td>
                <td class="text-center">
                  {#if comp.status === 'ACTIVE'}
                    <span class="badge text-bg-success">{$_('common.active')}</span>
                  {:else}
                    <span class="badge text-bg-danger">{$_('common.inactive')}</span>
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

<ConfirmModal bind:this={confirmModal} />
