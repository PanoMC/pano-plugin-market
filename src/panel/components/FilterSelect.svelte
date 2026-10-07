<!-- A filter with more than three choices: a row of tabs does not fit the card header, so it is one select. -->
<select
  class="form-select form-select-sm w-auto"
  aria-label={label}
  value={current ?? ''}
  onchange={(event) => {
    const option = options.find((item) => item.key === event.currentTarget.value);
    if (option) onSelect(option);
  }}>
  {#if current === null || current === undefined}
    <option value="" hidden>{label}</option>
  {/if}
  {#each options as option (option.key)}
    <option value={option.key}>{option.label}</option>
  {/each}
</select>

<script>
  // options: [{ key, label, ... }]; current: key of the chosen option (null = none of them);
  // onSelect: receives the chosen option object.
  let { options, current = null, label, onSelect } = $props();
</script>
