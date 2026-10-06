{#if data.state === 'DISABLED'}
  <StoreStateCard icon="fa-solid fa-store-slash fa-3x" text={$_('theme.store.closed')} />
{:else if data.state === 'ERROR'}
  <StoreStateCard
    icon="fa-solid fa-triangle-exclamation fa-3x"
    text={$_('theme.store.load-error')}
    onretry={() => location.reload()} />
{:else}
  <div class="row g-4">
    <aside class="col-lg-3">
      <div class="sticky-lg-top">
        <CategoryTree
          {categories}
          selected={filter.category}
          {totalCount}
          onselect={onCategorySelect} />
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
          <h2 class="h5 mb-3">
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
          <h2 class="h5 mb-3">
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
          <h2 class="h5 mb-0" id="marketGridHeading" tabindex="-1" bind:this={headingElement}>
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
                  <button type="button" class="btn btn-outline-secondary" onclick={clearFilters}>
                    {$_('theme.store.clear-filters')}
                  </button>
                </div>
              {/if}
            {/if}
          </div>

          <div class="mt-4">
            <Pager page={filter.page} totalPage={grid.totalPage} onpage={onPage} />
          </div>
        {/if}
      </section>

      {#if showSections && settings.showComparisons !== false && resolvedComparisons.length}
        <section class="vstack gap-3">
          <h2 class="h5 mb-0">
            <i class="fa-solid fa-table-list me-2" aria-hidden="true"></i>{$_(
              'theme.store.comparisons',
            )}
          </h2>
          {#each resolvedComparisons as comparison (comparison.id)}
            <ComparisonTable {comparison} {productMap} {settings} />
          {/each}
        </section>
      {/if}
    </div>
  </div>
{/if}

<script module>
  import { redirect } from '@panomc/sdk/svelte';
  import { hasFilter, resolveStoreLoad, validateFilter } from '../lib/storeLoad.js';
  import { listQuery, parseCurrency, parseFilter, withoutPageParam } from '../lib/storeFilter.js';
  import { setSettings } from '../stores/storeSettings.js';
  import { call } from '../utils/api.js';
  import { has } from '../utils/host.js';

  const STORE_PATH = '/api/market/store';
  const LIST_PATH = '/api/market/store/products';
  const WIDGETS_PATH = '/api/market/widgets';

  export async function load(event) {
    const url = event.url;
    const filter = parseFilter(url.searchParams);
    const currency = parseCurrency(url.searchParams);
    const fetchList = (f) => call('GET', LIST_PATH, { event, query: listQuery(f, currency) });

    const [store, widgets, firstList] = await Promise.all([
      call('GET', STORE_PATH, { event, query: { currency } }),
      call('GET', WIDGETS_PATH, { event, query: { include: 'recentBuyers,topSupporters,goals' } }),
      hasFilter(filter) ? fetchList(filter) : null,
    ]);

    let list = firstList;

    // an unknown ?category is dropped; the list then has to be asked again without it
    if (store.ok) {
      const valid = validateFilter(filter, store.categories || []);
      if (valid !== filter) list = hasFilter(valid) ? await fetchList(valid) : null;
    }

    const result = resolveStoreLoad({
      store,
      list,
      widgets,
      filter,
      origin: url.origin,
      withMeta: has('page-meta'),
    });

    if (result.redirect) throw redirect(302, withoutPageParam(`${url.pathname}${url.search}`));

    if (result.data.state === 'READY') setSettings(result.data.settings);

    return result;
  }
</script>

<script>
  import { getContext, onMount, untrack } from 'svelte';
  import { get } from 'svelte/store';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { _ } from '../../i18n';
  import ComparisonTable from '../components/store/ComparisonTable.svelte';
  import CategoryTree from '../components/store/CategoryTree.svelte';
  import Pager from '../components/store/Pager.svelte';
  import ProductCard from '../components/store/ProductCard.svelte';
  import StoreStateCard from '../components/store/StoreStateCard.svelte';
  import StoreToolbar from '../components/store/StoreToolbar.svelte';
  import TestModeBanner from '../components/store/TestModeBanner.svelte';
  import ErrorAlert from '../components/common/ErrorAlert.svelte';
  import { REFETCH_DELAY_MS } from '../lib/countdown.js';
  import { expiredSaleEnds } from '../lib/sale.js';
  import {
    DEFAULT_FILTER,
    SEARCH_DEBOUNCE_MS,
    createSequencer,
    flattenCategories,
    isDefaultFilter,
    normalizeSearch,
    storeSearch,
    withChange,
  } from '../lib/storeFilter.js';
  import { cart } from '../stores/cart.js';
  import { now } from '../stores/clock.js';
  import {
    adoptUrlCurrency,
    effectiveCurrency,
    initCurrency,
    needsCurrencyRefetch,
    preferred,
    setPreferred,
  } from '../stores/currency.js';
  import { bindSession, hostSession } from '../stores/session.js';

  let { data } = $props();

  bindSession(hostSession(getContext));

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);
  const emptyGrid = { state: 'READY', products: [], productCount: 0, totalPage: 1 };

  let settings = $state(init.settings ?? {});
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
    effectiveCurrency(settings, urlCurrency, $preferred) ||
      settings.displayCurrency ||
      settings.currency ||
      '',
  );

  const currentCurrency = () => effectiveCurrency(settings, urlCurrency, get(preferred));

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

  function readGrid(res) {
    return {
      state: 'READY',
      products: res.products || [],
      productCount: res.productCount ?? (res.products || []).length,
      totalPage: res.totalPage ?? 1,
    };
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

    grid = res.ok ? readGrid(res) : { ...grid, state: 'ERROR' };
    if (res.ok) writeUrl();
    if (res.ok && scroll) headingElement?.scrollIntoView?.({ block: 'start' });
  }

  /** Everything the currency changes: settings, trees, cards and the current grid. */
  async function reloadAll(currency) {
    const mine = seq.beginReload();
    const fetchGrid = !isDefaultFilter(filter);

    grid = { ...grid, state: 'LOADING' };

    const [store, list] = await Promise.all([
      call('GET', '/api/market/store', { query: { currency } }),
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
    totalCount = store.productCount ?? totalCount;
    firstPage = readGrid(store);
    if (gridCurrent) grid = list ? readGrid(list) : { ...firstPage };
    // the filter went back to the default meanwhile (no request pending then): show the refreshed first page
    else if (isDefaultFilter(filter)) grid = { ...firstPage };
    setSettings(settings);
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
    setPreferred(code);
    urlCurrency = null;
    writeUrl();
    cart.setCurrency(code);
    reloadAll(code);
  }

  onMount(() => {
    if (data.state !== 'READY') return undefined;

    setSettings(settings);
    initCurrency();

    const fromUrl = parseCurrency(new URLSearchParams(window.location.search));
    adoptUrlCurrency(settings, fromUrl);
    urlCurrency = effectiveCurrency(settings, fromUrl, null) ?? null;

    // SSR rendered the default currency; one visible price update to the remembered one (14 §4.5)
    if (!urlCurrency && needsCurrencyRefetch(settings, get(preferred)))
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

    const ends = expiredSaleEnds([...featured, ...bestsellers, ...grid.products], $now).filter(
      (end) => !handledSaleEnds[end],
    );
    if (!ends.length) return;

    ends.forEach((end) => (handledSaleEnds[end] = true));
    clearTimeout(saleTimer);
    saleTimer = setTimeout(() => reloadAll(currentCurrency()), REFETCH_DELAY_MS);
  });
</script>
