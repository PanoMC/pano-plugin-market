<div class="row g-4">
  <aside class="col-lg-3">
    <div class="sticky-lg-top" style="top: 1rem;">
      <CategorySidebar
        categories={categories}
        selected={selectedCategory}
        totalCount={products.length}
        onselect={onCategorySelect} />
    </div>
  </aside>

  <div class="col-lg-9 vstack gap-4">
    <!-- Toolbar: search + cart -->
    <div class="d-flex flex-wrap gap-2 align-items-center">
      <div class="position-relative flex-grow-1" style="max-width: 360px;">
        <i class="fa-solid fa-magnifying-glass position-absolute top-50 translate-middle-y ms-3 text-body-secondary"></i>
        <input
          type="search"
          class="form-control rounded-pill ps-5"
          placeholder={$_('theme.store.search-placeholder')}
          bind:value={search} />
      </div>
      <button
        type="button"
        class="btn btn-primary position-relative ms-auto"
        data-bs-toggle="offcanvas"
        data-bs-target="#marketCartOffcanvas"
        aria-label={$_('theme.store.cart.title')}>
        <i class="fa-solid fa-cart-shopping"></i>
        <span class="d-none d-sm-inline ms-2">{$_('theme.store.cart.title')}</span>
        {#if cartCount > 0}
          <span class="position-absolute top-0 start-100 translate-middle badge rounded-pill text-bg-danger">
            {cartCount}
          </span>
        {/if}
      </button>
    </div>

    {#if showSections && showFeatured && featuredProducts.length}
      <section>
        <h2 class="h5 mb-3">
          <i class="fa-solid fa-star text-warning me-2"></i>{$_('theme.store.featured')}
        </h2>
        <div class="row g-3">
          {#each featuredProducts as product (product.id)}
            <div class="col-6 col-md-4 col-xl-3">
              <ProductCard {product} {settings} onselect={openDetail} />
            </div>
          {/each}
        </div>
      </section>
    {/if}

    {#if showSections && showBestsellers && bestsellerProducts.length}
      <section>
        <h2 class="h5 mb-3">
          <i class="fa-solid fa-fire text-danger me-2"></i>{$_('theme.store.bestsellers')}
        </h2>
        <div class="row g-3">
          {#each bestsellerProducts as product (product.id)}
            <div class="col-6 col-md-4 col-xl-3">
              <ProductCard {product} {settings} onselect={openDetail} />
            </div>
          {/each}
        </div>
      </section>
    {/if}

    <section>
      <div class="d-flex align-items-center justify-content-between mb-3">
        <h2 class="h5 mb-0">
          {selectedCategory != null
            ? categoryNameMap[selectedCategory] || $_('theme.store.all-products')
            : $_('theme.store.all-products')}
        </h2>
        <span class="text-body-secondary small">
          {$_('theme.store.products-count', { values: { count: filteredProducts.length } })}
        </span>
      </div>

      {#if filteredProducts.length}
        <div class="row g-3">
          {#each filteredProducts as product (product.id)}
            <div class="col-6 col-md-4 col-xl-3">
              <ProductCard {product} {settings} onselect={openDetail} />
            </div>
          {/each}
        </div>
      {:else}
        <NoContent icon="fa-solid fa-box-open fa-3x" text={$_('theme.store.empty')} />
      {/if}
    </section>

    {#if showSections && resolvedComparisons.length}
      <section class="vstack gap-3">
        <h2 class="h5 mb-0">
          <i class="fa-solid fa-table-list me-2"></i>{$_('theme.store.comparisons')}
        </h2>
        {#each resolvedComparisons as comparison (comparison.id)}
          <ComparisonTable {comparison} {productMap} {settings} />
        {/each}
      </section>
    {/if}
  </div>
</div>

<ProductDetailModal
  product={selectedProduct}
  {settings}
  categoryName={selectedProduct ? categoryNameMap[selectedProduct.categoryId] || null : null}
  onclose={() => (selectedProduct = null)} />

<CartOffcanvas {productMap} {settings} />

<script module>
  import ApiUtil from '@panomc/sdk/utils/api';

  export async function load(event) {
    const fallback = {
      data: {
        settings: {},
        categories: [],
        products: [],
        bestsellers: [],
        comparisons: [],
        initialCategory: null,
      },
      pageTitle: { title: 'plugins.pano-plugin-market.theme.store.title' },
    };

    try {
      const res = await ApiUtil.get({ path: '/api/market/store', request: event });

      const settings = res.settings || {};
      const categories = res.categories || [];

      // A `?category` deep link is only honored when it resolves to a known
      // category id — otherwise the grid would silently render empty.
      const categoryParam = event.url.searchParams.get('category');
      const parsedCategory =
        categoryParam != null && categoryParam.trim() !== '' ? Number(categoryParam) : null;
      const initialCategory =
        parsedCategory != null &&
        Number.isFinite(parsedCategory) &&
        hasCategoryId(categories, parsedCategory)
          ? parsedCategory
          : null;

      // The host theme passes pageTitle title/subtitle through $_() as message ids,
      // so admin-configured literals must go through a fixed key + interpolation values.
      const storeName = typeof settings.storeName === 'string' ? settings.storeName.trim() : '';
      const storeDescription =
        typeof settings.storeDescription === 'string' ? settings.storeDescription.trim() : '';

      const pageTitle = storeName
        ? {
            title: 'plugins.pano-plugin-market.theme.store.title-with-name',
            titleValues: { storeName },
          }
        : { title: 'plugins.pano-plugin-market.theme.store.title' };

      if (storeDescription) {
        pageTitle.subtitle = 'plugins.pano-plugin-market.theme.store.subtitle';
        pageTitle.subtitleValues = { storeDescription };
      }

      return {
        data: {
          settings,
          categories,
          products: res.products || [],
          bestsellers: res.bestsellers || [],
          comparisons: res.comparisons || [],
          initialCategory,
        },
        pageTitle,
      };
    } catch (e) {
      console.error('[Market] Failed to load store page data', e);
      return fallback;
    }
  }

  function hasCategoryId(nodes, id) {
    for (const node of nodes) {
      if (node.id === id) return true;
      if (node.children && node.children.length && hasCategoryId(node.children, id)) return true;
    }
    return false;
  }
</script>

<script>
  import { getContext, untrack } from 'svelte';
  import { browser } from '@panomc/sdk/svelte';
  import { NoContent } from '@panomc/sdk/components/theme';
  import { _ } from '../../i18n';
  import { cart } from '../stores/cart.js';
  import { bindSession } from '../stores/session.js';
  import CategorySidebar from '../components/CategorySidebar.svelte';
  import ProductCard from '../components/ProductCard.svelte';
  import ProductDetailModal from '../components/ProductDetailModal.svelte';
  import ComparisonTable from '../components/ComparisonTable.svelte';
  import CartOffcanvas from '../components/CartOffcanvas.svelte';

  let { data } = $props();

  bindSession(getContext('session'));

  let settings = $derived(data.settings);
  let categories = $derived(data.categories);
  let products = $derived(data.products);
  let bestsellers = $derived(data.bestsellers);
  let comparisons = $derived(data.comparisons);

  // Plain (non-reactive) bookkeeping for the deep-link re-sync below.
  let lastInitialCategory = untrack(() => data.initialCategory ?? null);
  let selectedCategory = $state(lastInitialCategory);
  let search = $state('');
  let selectedProduct = $state(null);

  // Re-sync when a client-side navigation delivers a new ?category deep link
  // while this component instance is reused (load() re-parses it every time).
  $effect.pre(() => {
    const next = data.initialCategory ?? null;

    if (next !== lastInitialCategory) {
      lastInitialCategory = next;
      selectedCategory = next;
    }
  });

  let showFeatured = $derived(settings.showFeaturedProducts);
  let showBestsellers = $derived(settings.showBestsellers);

  // Product lookup by id.
  let productMap = $derived(
    products.reduce((acc, p) => {
      acc[p.id] = p;
      return acc;
    }, {})
  );

  // Flattened id -> name map for category labels (grid heading + modal).
  let categoryNameMap = $derived(flattenNames(categories, {}));

  function flattenNames(nodes, acc) {
    for (const node of nodes) {
      acc[node.id] = node.name;
      if (node.children && node.children.length) flattenNames(node.children, acc);
    }
    return acc;
  }

  let featuredProducts = $derived(products.filter((p) => p.featured));

  let bestsellerProducts = $derived(bestsellers.map((id) => productMap[id]).filter(Boolean));

  // Featured / bestseller sections only surface on the default view.
  let showSections = $derived(selectedCategory == null && !search.trim());

  let filteredProducts = $derived(
    products.filter((p) => {
      const matchesCategory = selectedCategory == null || p.categoryId === selectedCategory;
      const term = search.trim().toLowerCase();
      const matchesSearch = !term || (p.name || '').toLowerCase().includes(term);
      return matchesCategory && matchesSearch;
    })
  );

  let resolvedComparisons = $derived(
    comparisons.filter((c) => (c.productIds || []).some((id) => id != null && productMap[id]))
  );

  // Lines of products that are gone stay in the cart and are reported by the quote.
  let cartCount = $derived($cart.count);

  function onCategorySelect(detail) {
    selectedCategory = detail.id;
  }

  function openDetail(product) {
    selectedProduct = product;
  }

  // Reflect the selected category in the URL for deep-linking, without a reload
  // (filtering is entirely client-side).
  $effect(() => {
    if (browser) syncUrl(selectedCategory);
  });

  function syncUrl(categoryId) {
    try {
      const url = new URL(window.location.href);
      if (categoryId == null) {
        url.searchParams.delete('category');
      } else {
        url.searchParams.set('category', String(categoryId));
      }
      window.history.replaceState(window.history.state, '', url);
    } catch (e) {
      // no-op
    }
  }
</script>
