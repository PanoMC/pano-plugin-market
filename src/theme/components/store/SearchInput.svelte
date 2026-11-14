<div class="market-search-input input-group w-auto">
  <input
    {id}
    type="text"
    class={[
      'market-search-input__input',
      'form-control',
      'focus-ring',
      small && 'form-control-sm',
      value.trim() && 'border-secondary',
    ]}
    maxlength={maxLength}
    {placeholder}
    aria-label={ariaLabel}
    {value}
    oninput={onInput} />

  {#if showSpinner && (searching || pending)}
    <span class="market-search-input__status input-group-text" aria-label={searchingLabel}>
      <span class="spinner-border spinner-border-sm text-primary" role="status"></span>
    </span>
  {/if}
</div>

<script>
  let {
    id,
    initialValue = '',
    placeholder = '',
    ariaLabel = '',
    searchingLabel = '',
    maxLength = 64,
    small = true,
    debounceMs = 300,
    searching = false,
    showSpinner = true,
    onchange,
  } = $props();

  let value = $state(initialValue);
  let pending = $state(false);

  $effect(() => {
    value = initialValue;
    pending = false;
  });

  $effect(() => {
    const current = value;
    if (!pending) return;

    const timer = setTimeout(() => {
      pending = false;
      onchange?.(current);
    }, debounceMs);

    return () => clearTimeout(timer);
  });

  function onInput(event) {
    value = event.currentTarget.value;
    pending = true;
  }
</script>
