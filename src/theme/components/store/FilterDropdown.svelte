<div class="market-filter-dropdown dropdown">
  <button
    type="button"
    class="market-filter-dropdown__trigger btn btn-link text-decoration-none"
    data-bs-toggle="dropdown"
    data-bs-popper-config={POPPER_FIXED}
    aria-expanded="false"
    aria-haspopup="true"
    aria-label={label}
    title={selected ? `${label}: ${selected.label}` : label}>
    <i class="fa-solid {icon}" aria-hidden="true"></i>
  </button>
  <div
    class="market-filter-dropdown__menu dropdown-menu dropdown-menu-end"
    role="menu"
    aria-label={label}>
    {#each options as option (option.key)}
      <button
        type="button"
        class="market-filter-dropdown__option dropdown-item d-flex justify-content-between align-items-center"
        class:active={option.key === current}
        role="menuitemradio"
        aria-checked={option.key === current}
        onclick={() => onSelect?.(option)}>
        <span class="market-filter-dropdown__label text-capitalize">{option.label}</span>
        {#if option.key === current}
          <i class="fa-solid fa-check ms-3" aria-hidden="true"></i>
        {/if}
      </button>
    {/each}
  </div>
</div>

<script module>
  export const view = { contract: 2 };
</script>

<script>
  const POPPER_FIXED = '{"strategy":"fixed"}';

  let { options = [], current = null, label, onSelect, icon = 'fa-filter' } = $props();

  const selected = $derived(options.find((option) => option.key === current) ?? options[0] ?? null);
</script>
