<div class="row mb-3">
  <div class="col-md-6">
    <label class="col-form-label pb-0 d-block" for={id}>{label}</label>
    {#if hint}
      <div class="form-text mt-0">{hint}</div>
    {/if}
  </div>
  <div class="col-md-6" class:d-flex={center} class:align-items-center={center}>
    <div class="w-100">
      {@render children?.()}
      {#if error}
        <div class="invalid-feedback d-block">{error}</div>
      {/if}
    </div>
  </div>
</div>

<script>
  // One labelled row of a settings card: label (+ optional helper line) left, control right,
  // the validation message under the control as text. `id` is the control's id (setting-<key>).
  let { id, label, hint = '', error = '', center = false, children } = $props();
</script>
