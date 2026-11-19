<!-- The trigger is the filter icon only; its name (and the chosen item) is the native title, and the
     menu opens with the filter's name as its header. -->
<div class="dropdown {extraClass}">
  <button
    type="button"
    class="btn btn-link text-decoration-none"
    data-bs-toggle="dropdown"
    data-bs-popper-config={POPPER_FIXED}
    aria-expanded="false"
    aria-haspopup="true"
    aria-label={name}
    title={name}>
    <i class="fa-solid {icon}" aria-hidden="true"></i>
  </button>
  <div class="dropdown-menu dropdown-menu-end" role="menu" aria-label={label}>
    <div class="dropdown-header text-capitalize" role="presentation">{label}</div>
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

  // iconOnly is kept for callers that still pass it: every filter is icon-only now.
  let {
    options = [],
    current = null,
    label,
    onSelect,
    icon = 'fa-filter',
    iconOnly: _iconOnly = true,
    class: extraClass = '',
  } = $props();

  const selected = $derived(
    options.find((option) => option.key === current) ?? options[0] ?? null,
  );
  const name = $derived(selected ? `${label}: ${selected.label}` : label);
</script>
