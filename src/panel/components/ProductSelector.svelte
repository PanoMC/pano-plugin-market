<script>
  let {
    products = [],
    selected = $bindable(),
    multiple = false,
    maxHeight = '200px',
    placeholder = 'Ürün ara...'
  } = $props();

  let search = $state('');

  let filteredProducts = $derived(
    products.filter(p => p.name.toLowerCase().includes(search.toLowerCase()))
  );

  const radioName = 'prod-select-' + Math.random().toString(36).substring(2, 9);

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
    {placeholder} 
    bind:value={search} />
  
  <div 
    class="list-group list-group-flush border rounded overflow-y-auto mb-0" 
    style="max-height: {maxHeight};">
    {#each filteredProducts as product}
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
        <i class="fas fa-circle-info me-1"></i> Sonuç bulunamadı.
      </div>
    {/each}
  </div>
</div>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
</style>
