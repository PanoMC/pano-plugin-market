<script>
  import { ICON_CATEGORIES, ALL_ICONS } from '../data/fa-icons.js';

  let {
    value = $bindable('fa-folder'),
    color = '#0d6efd',
    label = 'İkon',
    placeholder = 'İkon ara (ör: star)',
    placement = 'bottom-start'
  } = $props();

  let open = $state(false);
  let search = $state('');
  let activeCategory = $state('popular');
  let triggerEl = $state(null);
  let panelEl = $state(null);
  let searchInput = $state(null);

  const categoryById = $derived(
    new Map(ICON_CATEGORIES.map((c) => [c.key, c]))
  );

  const filteredIcons = $derived.by(() => {
    const term = search.trim().toLowerCase();
    const source = term
      ? ALL_ICONS
      : (categoryById.get(activeCategory)?.icons ?? ALL_ICONS);

    if (!term) return source;

    return source.filter((name) =>
      name.toLowerCase().includes(term) ||
      name.replace(/^fa-/, '').replace(/-/g, ' ').includes(term)
    );
  });

  function toggle() {
    open = !open;
    if (open) {
      queueMicrotask(() => searchInput?.focus());
    }
  }

  function close() {
    open = false;
    search = '';
  }

  function selectIcon(name) {
    value = name;
    close();
  }

  function onDocumentMouseDown(e) {
    if (!open) return;
    if (panelEl?.contains(e.target)) return;
    if (triggerEl?.contains(e.target)) return;
    close();
  }

  function onKeydown(e) {
    if (e.key === 'Escape' && open) {
      close();
      triggerEl?.focus();
    }
  }

  $effect(() => {
    if (!open) return;

    document.addEventListener('mousedown', onDocumentMouseDown);
    document.addEventListener('keydown', onKeydown);

    return () => {
      document.removeEventListener('mousedown', onDocumentMouseDown);
      document.removeEventListener('keydown', onKeydown);
    };
  });
</script>

<div class="icon-picker position-relative">
  <button
    bind:this={triggerEl}
    type="button"
    class="form-control d-flex align-items-center gap-2 text-start"
    class:show={open}
    aria-haspopup="true"
    aria-expanded={open}
    onclick={toggle}>
    <span class="d-flex align-items-center justify-content-center" style="width: 24px; height: 24px;">
      <i class="fas {value} fs-5" style="color: {color};"></i>
    </span>
    <span class="flex-grow-1 text-truncate small text-body-secondary">{value}</span>
    <i class="fas fa-chevron-down small text-body-secondary"></i>
  </button>

  {#if open}
    <div
      bind:this={panelEl}
      class="icon-picker-panel card shadow border position-absolute mt-1 p-2"
      class:start-0={placement === 'bottom-start'}
      class:end-0={placement === 'bottom-end'}
      role="dialog"
      aria-label={label}>
      <div class="input-group input-group-sm mb-2">
        <span class="input-group-text bg-body-tertiary">
          <i class="fas fa-magnifying-glass"></i>
        </span>
        <input
          bind:this={searchInput}
          type="text"
          class="form-control"
          placeholder={placeholder}
          bind:value={search} />
        {#if search}
          <button type="button" class="btn btn-outline-secondary" onclick={() => (search = '')} aria-label="Temizle">
            <i class="fas fa-xmark"></i>
          </button>
        {/if}
      </div>

      {#if !search}
        <div class="d-flex flex-wrap gap-1 mb-2 category-tabs">
          {#each ICON_CATEGORIES as cat (cat.key)}
            <button
              type="button"
              class="btn btn-sm"
              class:btn-primary={activeCategory === cat.key}
              class:btn-outline-secondary={activeCategory !== cat.key}
              onclick={() => (activeCategory = cat.key)}>
              {cat.label}
            </button>
          {/each}
        </div>
      {/if}

      <div class="icon-grid overflow-auto pe-1">
        {#if filteredIcons.length === 0}
          <div class="text-center text-body-secondary small py-4">
            <i class="fas fa-circle-info me-1"></i>
            Sonuç bulunamadı.
          </div>
        {:else}
          {#each filteredIcons as name (name)}
            <button
              type="button"
              class="icon-btn btn btn-sm d-flex align-items-center justify-content-center"
              class:active={name === value}
              title={name}
              aria-label={name}
              onclick={() => selectIcon(name)}>
              <i class="fas {name} fs-5" style={name === value ? `color: ${color};` : ''}></i>
            </button>
          {/each}
        {/if}
      </div>
    </div>
  {/if}
</div>

<style>
  .icon-picker-panel {
    z-index: 1080;
    width: min(360px, 92vw);
    max-height: 380px;
  }

  .icon-grid {
    display: grid;
    grid-template-columns: repeat(auto-fill, minmax(40px, 1fr));
    gap: 4px;
    max-height: 240px;
  }

  .icon-btn {
    aspect-ratio: 1 / 1;
    border: 1px solid transparent;
    background: transparent;
    color: var(--bs-body-color);
    transition: background-color 0.15s, border-color 0.15s, color 0.15s;
  }

  .icon-btn:hover {
    background: var(--bs-body-tertiary-bg, var(--bs-tertiary-bg));
    border-color: var(--bs-border-color);
  }

  .icon-btn.active {
    background: var(--bs-primary-bg-subtle);
    border-color: var(--bs-primary);
  }

  .category-tabs .btn {
    font-size: 0.75rem;
    padding: 0.15rem 0.5rem;
  }
</style>
