<div class="row g-3 align-items-center">
  <div class="col-6">
    <div class="form-check form-switch m-0">
      <input
        class="form-check-input"
        type="checkbox"
        role="switch"
        id="{id}-switch"
        bind:checked={unlimited} />
      <label class="form-check-label" for="{id}-switch">{label}</label>
    </div>
  </div>
  <div class="col-6">
    {#if !unlimited}
      <input
        type="text"
        inputmode="numeric"
        class="form-control"
        class:is-invalid={error}
        id="{id}-input"
        autocomplete="off"
        placeholder={placeholder}
        aria-label={placeholder}
        bind:value />
    {/if}
  </div>
  {#if error}
    <div class="col-12"><ErrorText code={error} /></div>
  {/if}
</div>

<script>
  import ErrorText from './ErrorText.svelte';

  // unlimited switch + integer input (>= 0); `label` is the switch text, `placeholder` the input's.
  let {
    id,
    label,
    placeholder,
    unlimited = $bindable(true),
    value = $bindable(''),
    error = null,
  } = $props();
</script>
