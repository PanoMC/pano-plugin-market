<script>
  import { ICON_CATEGORIES, ALL_ICONS } from '../data/fa-icons.js';
  import { _ } from '../../i18n';

  let {
    value = $bindable('fa-folder'),
    color = '#0d6efd',
    label = $_('components.icon-picker.label'),
    placeholder = $_('components.icon-picker.placeholder'),
    placement = 'top-start'
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
    <i class="fas fa-chevron-up text-body-secondary"></i>
  </button>

  {#if open}
    <div
      bind:this={panelEl}
      class="icon-picker-panel card border position-absolute overflow-hidden"
      class:mt-1={placement.startsWith('bottom')}
      class:mb-1={placement.startsWith('top')}
      class:top-100={placement.startsWith('bottom')}
      class:bottom-100={placement.startsWith('top')}
      class:start-0={placement.endsWith('start')}
      class:end-0={placement.endsWith('end')}
      role="dialog"
      aria-label={label}>
      
      <div class="card-header bg-transparent p-2 vstack gap-2">
        <div class="input-group input-group-sm">
          <input
            bind:this={searchInput}
            type="text"
            class="form-control"
            placeholder={placeholder}
            bind:value={search} />
          {#if search}
            <button type="button" class="btn btn-secondary" onclick={() => (search = '')} aria-label={$_('components.icon-picker.clear')}>
              <i class="fas fa-xmark"></i>
            </button>
          {/if}
        </div>

        {#if !search}
          <div class="d-flex flex-wrap gap-1 category-tabs">
            {#each ICON_CATEGORIES as cat (cat.key)}
              <button
                type="button"
                class="btn btn-sm text-decoration-none"
                class:btn-primary={activeCategory === cat.key}
                class:text-white={activeCategory === cat.key}
                class:btn-link={activeCategory !== cat.key}
                onclick={() => (activeCategory = cat.key)}>
                {$_(cat.label)}
              </button>
            {/each}
          </div>
        {/if}
      </div>

      <div class="card-body p-2 pt-0">
        <div class="icon-grid overflow-auto pe-1 pt-2">
          {#if filteredIcons.length === 0}
            <div class="text-center text-body-secondary small py-4">
              <i class="fas fa-circle-info me-1"></i>
              {$_('components.icon-picker.no-results')}
            </div>
          {:else}
            {#each filteredIcons as name (name)}
              <button
                type="button"
                class="icon-btn btn btn-sm btn-link border-0 text-decoration-none text-body d-flex align-items-center justify-content-center"
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
    background: transparent !important;
    transition: transform 0.15s, color 0.15s;
  }

  .icon-btn:hover {
    transform: scale(1.15);
  }

  .category-tabs .btn {
    font-size: 0.75rem;
    padding: 0.25rem 0.5rem;
  }
</style>
