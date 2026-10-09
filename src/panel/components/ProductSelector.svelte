<script>
  import { onMount } from 'svelte';
  import { api } from '@panomc/sdk/plugin-api';
  import { _ } from '../../i18n';

  let {
    products = null,
    selected = $bindable(),
    multiple = false,
    maxHeight = '200px',
    placeholder = null
  } = $props();

  let search = $state('');
  let fetchedProducts = $state([]);
  let loading = $state(false);

  // Self-fetches the product list; consumers may still pass a non-empty `products`
  // array to override (backward compatibility).
  let displayProducts = $derived(
    Array.isArray(products) && products.length > 0 ? products : fetchedProducts
  );

  let filteredProducts = $derived(
    displayProducts.filter(p => p.name.toLowerCase().includes(search.toLowerCase()))
  );

  const radioName = 'prod-select-' + Math.random().toString(36).substring(2, 9);

  async function loadProducts() {
    loading = true;
    try {
      const res = await api.panel.get({ path: '/products/simple' });
      fetchedProducts = res?.items || [];
    } catch (e) {
      console.error('[Market] Failed to load products', e);
      fetchedProducts = [];
    } finally {
      loading = false;
    }
  }

  onMount(() => {
    if (Array.isArray(products) && products.length > 0) return;
    loadProducts();
  });

  function toggleProduct(id) {
    if (multiple) {
      if (!Array.isArray(selected)) {
        selected = [];
      }
      if (selected.includes(id)) {
        selected = selected.filter(x => x !== id);
      } else {
        selected = [...selected, id];
      }
    } else {
      selected = id;
    }
  }
</script>

<div class="product-selector w-100">
  <input
    type="text"
    class="form-control form-control-sm mb-2"
    placeholder={placeholder ?? $_('components.product-selector.search-placeholder')}
    bind:value={search} />

  <div
    class="list-group list-group-flush border rounded overflow-y-auto mb-0"
    style="max-height: {maxHeight};">
    {#if loading}
      <div class="text-center text-body-secondary py-3">
        <span class="spinner-border spinner-border-sm text-secondary me-1" role="status"></span> {$_('common.loading')}
      </div>
    {:else}
      {#each filteredProducts as product (product.id)}
        <label class="list-group-item d-flex align-items-center gap-3 py-2 cursor-pointer list-group-item-action">
          {#if multiple}
            <input
              class="form-check-input flex-shrink-0 mt-0 cursor-pointer"
              type="checkbox"
              checked={Array.isArray(selected) && selected.includes(product.id)}
              onchange={() => toggleProduct(product.id)} />
          {:else}
            <input
              class="form-check-input flex-shrink-0 mt-0 cursor-pointer"
              type="radio"
              name={radioName}
              value={product.id}
              checked={selected === product.id}
              onchange={() => toggleProduct(product.id)} />
          {/if}
          <span class="fw-medium text-truncate">{product.name}</span>
        </label>
      {:else}
        <div class="text-center text-body-secondary py-3">
          <i class="fas fa-circle-info me-1"></i> {$_('components.product-selector.empty')}
        </div>
      {/each}
    {/if}
  </div>
</div>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
</style>
