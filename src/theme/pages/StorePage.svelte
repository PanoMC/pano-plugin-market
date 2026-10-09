{#if data.state === 'DISABLED'}
  <StoreStateCard icon="fa-solid fa-store-slash fa-3x" text={$_('theme.store.closed')} />
{:else if data.state === 'ERROR'}
  <StoreStateCard
    icon="fa-solid fa-triangle-exclamation fa-3x"
    text={$_('theme.store.load-error')}
    onretry={() => location.reload()} />
{:else}
  <div class="market-store-page row g-4">
    <aside class="col-lg-3">
      <div class="sticky-lg-top">
        <CategoryTree
          {categories}
          selected={filter.category}
          {totalCount}
          onselect={onCategorySelect} />

        <!-- 14 §8.2: the modules sit under the categories on large screens and below the grid on small ones (rendered twice on purpose) -->
        <div class="d-none d-lg-block mt-3">
          <StoreModules {settings} {widgets} />
        </div>
      </div>
    </aside>

    <div class="col-lg-9 vstack gap-4">
      <StoreToolbar
        {settings}
        search={searchText}
        sort={filter.sort}
        currency={currentCurrencyCode}
        onsearch={onSearchInput}
        onsort={onSortChange}
        oncurrency={onCurrencyChange} />

      <TestModeBanner {settings} />

      {#if showSections && settings.showFeaturedProducts && featured.length}
        <section>
          <h2 class="market-store-page__title h5 mb-3">
            <i class="fa-solid fa-star text-warning me-2" aria-hidden="true"></i>{$_(
              'theme.store.featured',
            )}
          </h2>
          <div class="row g-3">
            {#each featured as product (product.id)}
              <div class="col-6 col-md-4 col-xl-3">
                <ProductCard {product} {settings} />
              </div>
            {/each}
          </div>
        </section>
      {/if}

      {#if showSections && settings.showBestsellers && bestsellers.length}
        <section>
          <h2 class="market-store-page__bestsellers h5 mb-3">
            <i class="fa-solid fa-fire text-danger me-2" aria-hidden="true"></i>{$_(
              'theme.store.bestsellers',
            )}
          </h2>
          <div class="row g-3">
            {#each bestsellers as product (product.id)}
              <div class="col-6 col-md-4 col-xl-3">
                <ProductCard {product} {settings} />
              </div>
            {/each}
          </div>
        </section>
      {/if}

      <section>
        <div class="d-flex align-items-center justify-content-between mb-3">
          <h2
            class="market-store-page__all-products h5 mb-0"
            id="marketGridHeading"
            tabindex="-1"
            bind:this={headingElement}>
            {filter.category != null
              ? categoryNames[filter.category] || $_('theme.store.all-products')
              : $_('theme.store.all-products')}
          </h2>
          <span class="text-body-secondary small">
            {$_('theme.store.products-count', { values: { count: grid.productCount } })}
          </span>
        </div>

        {#if grid.state === 'ERROR'}
          <ErrorAlert message={$_('theme.store.grid-error')} onretry={retry} />
        {:else}
          <div aria-busy={loading ? 'true' : 'false'}>
            {#if grid.products.length}
              <div class={['row', 'g-3', loading && 'opacity-50']}>
                {#each grid.products as product (product.id)}
                  <div class="col-6 col-md-4 col-xl-3">
                    <ProductCard {product} {settings} />
                  </div>
                {/each}
              </div>
            {:else if !loading}
              <NoContent
                icon="fa-solid fa-box-open fa-3x"
                text={filtered ? $_('theme.store.empty-search') : $_('theme.store.empty')} />
              {#if filtered}
                <div class="text-center mt-3">
                  <button
                    type="button"
                    class="market-store-page__action btn btn-outline-secondary"
                    onclick={clearFilters}>
                    {$_('theme.store.clear-filters')}
                  </button>
                </div>
              {/if}
            {/if}
          </div>

          <div class="mt-4">
            <Pager page={filter.page} totalPages={grid.totalPages} onpage={onPage} />
          </div>
        {/if}
      </section>

      {#if showSections && settings.showComparisons !== false && resolvedComparisons.length}
        <section class="vstack gap-3">
          <h2 class="market-store-page__comparisons h5 mb-0">
            <i class="fa-solid fa-table-list me-2" aria-hidden="true"></i>{$_(
              'theme.store.comparisons',
            )}
          </h2>
          {#each resolvedComparisons as comparison (comparison.id)}
            <ComparisonTable {comparison} {productMap} {settings} />
          {/each}
        </section>
      {/if}

      <div class="d-lg-none">
        <StoreModules {settings} {widgets} />
      </div>
    </div>
  </div>
{/if}

<script module>
  // page metadata (doc 01 section 2): the build registers this view as a page, no register.js entry. The data of the page
  // comes from the `market/store` controller (`controller` below, doc 02 section 4); this function only turns its redirect
  // into a SvelteKit redirect and hands the settings it fetched to `market/settings`.
  export const view = { path: '/store', controller: 'store' };

  import { plugin } from '@panomc/sdk/controllers';
  import { error, redirect } from '@panomc/sdk/svelte';

  export async function load(event) {
    const market = plugin('market');
    const result = await market.load('store', {
      // a server load is made for its request; the browser has one host for the whole page
      event: typeof window === 'undefined' ? event : undefined,
      params: { ...event.params, url: event.url },
    });

    if (!result) throw error(503, 'market/store is not available');

    if (result.redirect) throw redirect(result.redirect.status, result.redirect.location);

    if (result.data.state === 'READY' && typeof window !== 'undefined')
      market.use('settings')?.actions.set(result.data.settings);

    return result;
  }
</script>

<script>
  import { onMount, untrack } from 'svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import ComparisonTable from '../components/store/ComparisonTable.svelte';
  import CategoryTree from '../components/store/CategoryTree.svelte';
  import Pager from '../components/store/Pager.svelte';
  import ProductCard from '../components/store/ProductCard.svelte';
  import StoreStateCard from '../components/store/StoreStateCard.svelte';
  import StoreToolbar from '../components/store/StoreToolbar.svelte';
  import TestModeBanner from '../components/store/TestModeBanner.svelte';
  import StoreModules from '../components/widgets/StoreModules.svelte';
  import ErrorAlert from '../components/common/ErrorAlert.svelte';
  import { REFETCH_DELAY_MS } from '../lib/countdown.js';
  import { expiredSaleEnds } from '../lib/sale.js';
  import { gridOf } from '../lib/storeLoad.js';
  import {
    DEFAULT_FILTER,
    SEARCH_DEBOUNCE_MS,
    createSequencer,
    flattenCategories,
    isDefaultFilter,
    listQuery,
    normalizeSearch,
    parseCurrency,
    storeSearch,
    withChange,
  } from '../lib/storeFilter.js';

  const market = plugin('market');
  const _ = market._;
  const clock = market.require('clock');
  const currencies = market.require('currency');
  const settingsStore = market.require('settings');
  const { call } = market.require('api').actions;
  // resolved where it is used, so a server render never builds a cart
  const cartActions = () => market.require('cart').actions;

  const LIST_PATH = '/store/products';

  let { data } = $props();

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);
  const emptyGrid = { state: 'READY', products: [], productCount: 0, totalPages: 1 };

  let settings = $state(init.settings ?? {});
  // the store modules (goal, top supporters, recent buyers): one answer of the load, shown by the flags of the settings
  let widgets = $state(init.widgets ?? {});
  let categories = $state(init.categories ?? []);
  let featured = $state(init.featured ?? []);
  let bestsellers = $state(init.bestsellers ?? []);
  let comparisons = $state(init.comparisons ?? []);
  let comparisonProducts = $state(init.comparisonProducts ?? []);
  let totalCount = $state(init.totalCount ?? 0);
  let firstPage = $state(init.firstPage ?? emptyGrid);
  let grid = $state(init.grid ?? emptyGrid);
  let filter = $state(init.filter ?? { ...DEFAULT_FILTER });
  let searchText = $state(init.filter?.search ?? '');
  let urlCurrency = $state(null);
  let headingElement = $state();

  const seq = createSequencer();
  let searchTimer;
  let saleTimer;
  const handledSaleEnds = {};

  const categoryNames = $derived(
    Object.fromEntries(flattenCategories(categories).map((row) => [row.id, row.name])),
  );
  const loading = $derived(grid.state === 'LOADING');
  const filtered = $derived(filter.category != null || !!filter.search);
  // featured, bestsellers and comparisons only surface on the default view
  const showSections = $derived(isDefaultFilter(filter));
  const productMap = $derived(
    Object.fromEntries(
      [...comparisonProducts, ...featured, ...bestsellers, ...grid.products].map((p) => [p.id, p]),
    ),
  );
  const resolvedComparisons = $derived(
    comparisons.filter((c) => (c.productIds || []).some((id) => id != null && productMap[id])),
  );
  const currentCurrencyCode = $derived(
    currencies.actions.effective(settings, urlCurrency, currencies.state.preferred) ||
      settings.displayCurrency ||
      settings.currency ||
      '',
  );

  const currentCurrency = () =>
    currencies.actions.effective(settings, urlCurrency, currencies.state.preferred);

  function writeUrl() {
    try {
      const search = storeSearch(filter, urlCurrency);

      window.history.replaceState(
        window.history.state,
        '',
        `${window.location.pathname}${search ? `?${search}` : ''}${window.location.hash}`,
      );
    } catch (e) {
      // no-op
    }
  }

  /** Applies a changed filter: the first page of load is reused for the default filter, else one request. */
  async function applyFilter(next, { scroll = false } = {}) {
    filter = next;
    const mine = seq.beginGrid();

    if (isDefaultFilter(next)) {
      grid = { ...firstPage, state: 'READY' };
      writeUrl();
      return;
    }

    grid = { ...grid, state: 'LOADING' };

    let res = await call('GET', LIST_PATH, { query: listQuery(next, currentCurrency()) });
    if (!seq.isGridLatest(mine)) return;

    if (!res.ok && res.code === 'PAGE_NOT_FOUND' && next.page !== 1) {
      filter = next = { ...next, page: 1 };
      res = await call('GET', LIST_PATH, { query: listQuery(next, currentCurrency()) });
      if (!seq.isGridLatest(mine)) return;
    }

    grid = res.ok ? gridOf(res) : { ...grid, state: 'ERROR' };
    if (res.ok) writeUrl();
    if (res.ok && scroll) headingElement?.scrollIntoView?.({ block: 'start' });
  }

  /** Everything the currency changes: settings, trees, cards and the current grid. */
  async function reloadAll(currency) {
    const mine = seq.beginReload();
    const fetchGrid = !isDefaultFilter(filter);

    grid = { ...grid, state: 'LOADING' };

    const [store, list] = await Promise.all([
      call('GET', '/store', { query: { currency } }),
      fetchGrid ? call('GET', LIST_PATH, { query: listQuery(filter, currency) }) : null,
    ]);
    // a newer reload covers this one; a filter change only supersedes the grid part (the store part still applies)
    if (!seq.isStoreLatest(mine.store)) return;
    const gridCurrent = seq.isGridLatest(mine.grid);

    if (!store.ok || (list && !list.ok)) {
      if (gridCurrent) grid = { ...grid, state: 'ERROR' };
      return;
    }

    settings = store.settings || settings;
    categories = store.categories || [];
    featured = store.featured || [];
    bestsellers = store.bestsellers || [];
    comparisons = store.comparisons || [];
    comparisonProducts = store.comparisonProducts || [];
    firstPage = gridOf(store);
    totalCount = firstPage.productCount;
    if (gridCurrent) grid = list ? gridOf(list) : { ...firstPage };
    // the filter went back to the default meanwhile (no request pending then): show the refreshed first page
    else if (isDefaultFilter(filter)) grid = { ...firstPage };
    settingsStore.actions.set(settings);
  }

  function retry() {
    reloadAll(currentCurrency());
  }

  function onCategorySelect(id) {
    if (id === filter.category) return;
    applyFilter(withChange(filter, { category: id }));
  }

  function onSortChange(sort) {
    applyFilter(withChange(filter, { sort }));
  }

  function onSearchInput(text) {
    searchText = text;
    clearTimeout(searchTimer);
    searchTimer = setTimeout(() => {
      const search = normalizeSearch(text);
      if (search !== filter.search) applyFilter(withChange(filter, { search }));
    }, SEARCH_DEBOUNCE_MS);
  }

  function onPage(page) {
    applyFilter(withChange(filter, { page }), { scroll: true });
  }

  function clearFilters() {
    clearTimeout(searchTimer);
    searchText = '';
    applyFilter({ ...DEFAULT_FILTER });
  }

  function onCurrencyChange(code) {
    currencies.actions.setPreferred(code);
    urlCurrency = null;
    writeUrl();
    cartActions().setCurrency(code);
    reloadAll(code);
  }

  onMount(() => {
    if (data.state !== 'READY') return undefined;

    // 14 §7.3: the cart initialises at once on a market page. The session hook only runs at the first bind and on a login, so a visitor who
    // signs in on another page and then goes to the store would otherwise keep a stale badge and an unmerged browser cart (TH-13).
    cartActions().autoInit();

    settingsStore.actions.set(settings);
    currencies.actions.init();

    const fromUrl = parseCurrency(new URLSearchParams(window.location.search));
    currencies.actions.adoptUrl(settings, fromUrl);
    urlCurrency = currencies.actions.effective(settings, fromUrl, null) ?? null;

    // SSR rendered the default currency; one visible price update to the remembered one (14 §4.5)
    if (!urlCurrency && currencies.actions.needsRefetch(settings, currencies.state.preferred))
      reloadAll(currentCurrency());

    return () => {
      clearTimeout(searchTimer);
      clearTimeout(saleTimer);
      seq.invalidate(); // a response arriving after unmount is dropped
    };
  });

  // A sale that ran out is hidden locally (the cards do that); the public response is cached for 30 s,
  // so ONE data refetch follows 35 s after each end time.
  $effect(() => {
    if (data.state !== 'READY') return;

    const ends = expiredSaleEnds(
      [...featured, ...bestsellers, ...grid.products],
      clock.state.now,
    ).filter((end) => !handledSaleEnds[end]);
    if (!ends.length) return;

    ends.forEach((end) => (handledSaleEnds[end] = true));
    clearTimeout(saleTimer);
    saleTimer = setTimeout(() => reloadAll(currentCurrency()), REFETCH_DELAY_MS);
  });
</script>
