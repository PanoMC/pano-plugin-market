<div class="dropdown {extraClass}">
  <button
    type="button"
    class="btn btn-link text-decoration-none"
    data-bs-toggle="dropdown"
    data-bs-popper-config={POPPER_FIXED}
    aria-expanded="false"
    aria-haspopup="true"
    aria-label={iconOnly && selected ? `${label}: ${selected.label}` : label}
    title={selected ? `${label}: ${selected.label}` : label}>
    <i class="fa-solid {icon}" class:me-2={!iconOnly} aria-hidden="true"></i>
    {#if !iconOnly}
      <span>{selected?.label ?? label}</span>
    {/if}
  </button>
  <div class="dropdown-menu dropdown-menu-end" role="menu" aria-label={label}>
    <div class="dropdown-header" role="presentation">{label}</div>
    {#each options as option (option.key)}
      <button
        type="button"
        class="dropdown-item d-flex justify-content-between align-items-center text-capitalize"
        class:active={option.key === current}
        role="menuitemradio"
        aria-checked={option.key === current}
        onclick={() => onSelect?.(option)}>
        <span>{option.label}</span>
        {#if option.key === current}
          <i class="fa-solid fa-check ms-3" aria-hidden="true"></i>
        {/if}
      </button>
    {/each}
  </div>
</div>

<script>
  const POPPER_FIXED = '{"strategy":"fixed"}';

  let {
    options = [],
    current = null,
    label,
    onSelect,
    icon = 'fa-filter',
    iconOnly = false,
    class: extraClass = '',
  } = $props();

  const selected = $derived(
    options.find((option) => option.key === current) ?? options[0] ?? null,
  );
</script>
