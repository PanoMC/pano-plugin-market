<MarketLayout area="customers" sections={sectionsFor('customers', user)} active="blocks">
  {#snippet right()}
    {#if can(user, 'OM')}
      <button type="button" class="btn btn-secondary" onclick={() => blockModal?.open()}>
        <i class="fa-solid fa-plus" aria-hidden="true"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.blocks.add')}</span>
      </button>
    {/if}
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    <div class="card">
      <CardHeader>
        <div slot="left">
          {$_('pages.blocks.count', { values: { count: blockCount } })}
        </div>
        <div slot="middle" style="width: 250px;">
          <SearchInput
            autofocus
            initialValue={filters.search}
            searching={$navigating !== null}
            placeholderKey="plugins.pano-plugin-market.search.blocks"
            debounceMs={300}
            onchange={(value) => go({ search: value })} />
        </div>
        <CardFilters slot="right">
          <FilterSelect
            label={$_('pages.blocks.tab.all')}
            current={currentTab}
            options={TYPE_TABS.map((tab) => ({ ...tab, label: $_(`pages.blocks.tab.${tab.key}`) }))}
            onSelect={(tab) => go({ type: tab.value })} />
          <select
            class="form-select form-select-sm ms-2 w-auto"
            aria-label={$_('pages.blocks.source')}
            value={filters.source}
            onchange={(event) => go({ source: event.currentTarget.value })}>
            <option value="">{$_('pages.blocks.source-all')}</option>
            {#each SOURCES as value (value)}
              <option {value}>{$_(`enums.block-source.${value}`)}</option>
            {/each}
          </select>
        </CardFilters>
      </CardHeader>

      {#if blocks.length === 0}
        <NoContent icon="" />
      {:else}
        <div class="table-responsive">
          <table class="table table-hover align-middle">
            <thead>
              <tr>
                <th class="align-middle text-nowrap" scope="col"></th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.type')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.value')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.reason')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.source')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.hits')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.last-hit')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.expires')}</th>
                <th class="align-middle text-nowrap" scope="col">{$_('pages.blocks.table.created')}</th>
              </tr>
            </thead>
            <tbody>
              {#each blocks as block (block.id)}
                {@const actions = rowActions(block, user)}
                {@const expiry = expiryState(block, now)}
                <tr>
                  <th class="align-middle" scope="row">
                    {#if actions.length > 0}
                      <div class="dropdown position-static">
                        <button
                          type="button"
                          class="btn btn-link"
                          data-bs-toggle="dropdown"
                          title={$_('common.actions')}
                          aria-label={$_('common.actions')}>
                          <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
                        </button>
                        <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                          {#if actions.includes('view-order')}
                            <a class="dropdown-item" href="{base}/market/orders/detail/{block.orderId}">
                              <i class="fa-solid fa-eye me-2" aria-hidden="true"></i>
                              {$_('pages.blocks.actions.view-order')}
                            </a>
                          {/if}
                          {#if actions.includes('remove')}
                            <button
                              type="button"
                              class="dropdown-item link-danger"
                              onclick={() => askRemove(block)}>
                              <i class="fa-solid fa-trash me-2" aria-hidden="true"></i>
                              {$_('pages.blocks.actions.remove')}
                            </button>
                          {/if}
                        </div>
                      </div>
                    {/if}
                  </th>
                  <td class="text-nowrap">{typeLabel(block.type)}</td>
                  <td class="font-monospace text-break">{block.value}</td>
                  <td>{block.reason || '—'}</td>
                  <td class="text-nowrap">
                    {#if block.source === 'CHARGEBACK'}
                      <span class="badge text-bg-danger">{$_('enums.block-source.CHARGEBACK')}</span>
                    {:else}
                      {sourceLabel(block.source)}
                    {/if}
                  </td>
                  <td>{block.hitCount ?? 0}</td>
                  <td class="text-nowrap">{dateText(block.lastHitAt)}</td>
                  <td class="text-nowrap">
                    {#if expiry === 'never'}
                      {$_('common.never')}
                    {:else}
                      {dateText(block.expiresAt)}
                      {#if expiry === 'expired'}
                        <span class="badge text-bg-secondary ms-1">{$_('pages.blocks.expired')}</span>
                      {/if}
                    {/if}
                  </td>
                  <td class="text-nowrap">{dateText(block.createdAt)}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>

        {#if totalPage > 1}
          <div class="card-footer">
            <Pagination
              page={currentPage}
              {totalPage}
              on:firstPageClick={() => gotoPage(1)}
              on:lastPageClick={() => gotoPage(totalPage)}
              on:pageLinkClick={(event) => gotoPage(event.detail.page)} />
          </div>
        {/if}
      {/if}
    </div>
  {/if}
</MarketLayout>

<BlockModal bind:this={blockModal} onSaved={() => go({})} />
<ConfirmModal bind:this={confirmModal} />

<script module>
  import { loadList } from '../utils/list.js';
  import { BLOCK_PARAMS } from '../utils/blocks.js';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export function load(event) {
    return loadList(event, {
      path: '/blocks',
      params: BLOCK_PARAMS,
      nodes: ['OM'],
      emptyKey: 'blocks',
      title: 'pages.blocks.title',
    });
  }
</script>

<script>
  import FilterSelect from '../components/FilterSelect.svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import {
    CardHeader,
    CardFilters,
    NoContent,
    Pagination,
    SearchInput,
  } from '@panomc/sdk/components/panel';
  import { base, navigating, page, invalidateAll } from '@panomc/sdk/svelte';
  import { _, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import BlockModal from '../components/modals/BlockModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call, marketPath } from '../utils/api.js';
  import {
    SOURCES,
    TYPE_TABS,
    activeTypeTab,
    expiryState,
    listParams,
    normalizeFilters,
    rowActions,
  } from '../utils/blocks.js';
  import { gotoList } from '../utils/list.js';
  import { currentLocale } from '../utils/locale.js';
  import { can } from '../utils/permissions.js';
  import { toastError } from '../utils/toast.js';

  let { data } = $props();

  let blockModal = $state(null);
  let confirmModal = $state(null);

  const user = $derived($page.data?.user);
  const filters = $derived(normalizeFilters(data.filters));
  const blocks = $derived(data.blocks ?? []);
  const blockCount = $derived(data.blockCount ?? data.count ?? 0);
  const totalPage = $derived(data.totalPage ?? 1);
  const currentPage = $derived(data.page ?? 1);
  const currentTab = $derived(activeTypeTab(filters.type));
  // Read once per load(): the host remounts the page whenever load() re-runs.
  const now = $derived.by(() => {
    data;
    return Date.now();
  });

  // The URL is the source of truth; a filter or search change always drops `page`.
  function go(overrides) {
    return gotoList('/market/blocks', listParams(filters, overrides));
  }

  function gotoPage(pageNum) {
    return gotoList('/market/blocks', {
      ...listParams(filters),
      ...(pageNum > 1 ? { page: pageNum } : {}),
    });
  }

  function dateText(epoch) {
    return epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—';
  }

  // Unknown enum values render as the raw value (13 section 3.5 convention).
  function enumLabel(kind, value) {
    if (!value) return '—';
    const key = `enums.${kind}.${value}`;
    const text = $_(key);
    return text === key || text === `plugins.pano-plugin-market.${key}` ? value : text;
  }
  const typeLabel = (value) => enumLabel('block-type', value);
  const sourceLabel = (value) => enumLabel('block-source', value);

  function askRemove(block) {
    confirmModal?.open({
      icon: 'fa-solid fa-trash',
      title: $_('modals.confirm-delete.block.title'),
      description: $_('modals.confirm-delete.block.description'),
      confirmLabel: $_('pages.blocks.actions.remove'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(ApiUtil.delete({ path: marketPath(`/blocks/${block.id}`) }));
        if (!result.ok) {
          toastError($_, result);
          if (result.error !== 'NOT_FOUND') return false;
          // A row that is already gone (stale action, 13 section 23): close, then refresh.
          setTimeout(() => invalidateAll(), 350);
          return;
        }
        showSuccessToast($_('pages.blocks.toast-removed'));
        // The removed row may have been the last on this page: step back one page. The modal is
        // hidden before the navigation remounts the page (13 section 1.4).
        const target = blocks.length === 1 && currentPage > 1 ? currentPage - 1 : currentPage;
        setTimeout(
          () =>
            gotoList('/market/blocks', {
              ...listParams(filters),
              ...(target > 1 ? { page: target } : {}),
            }),
          350,
        );
      },
    });
  }
</script>
