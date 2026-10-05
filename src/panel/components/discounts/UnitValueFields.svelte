<div class="row g-3">
  <div class="col-8">
    <div class="form-floating">
      <input
        type="text"
        inputmode="decimal"
        class="form-control"
        class:is-invalid={error}
        id="{id}-value"
        autocomplete="off"
        placeholder={label}
        bind:value />
      <label for="{id}-value">{label}</label>
    </div>
  </div>
  <div class="col-4">
    <div class="form-floating">
      <select class="form-select" id="{id}-unit" bind:value={unit}>
        <option value="PERCENT">{$_('modals.discount-form.unit-percent')}</option>
        <option value="FIXED">{currency || $_('modals.discount-form.unit-fixed')}</option>
      </select>
      <label for="{id}-unit">{$_('modals.discount-form.unit')}</label>
    </div>
  </div>
  {#if error}
    <div class="col-12"><ErrorText code={error} /></div>
  {/if}
</div>

<script>
  import { _ } from '../../../i18n';
  import ErrorText from './ErrorText.svelte';

  // value text + PERCENT / FIXED enum; the FIXED option shows the store currency code.
  let {
    id,
    label,
    value = $bindable(''),
    unit = $bindable('PERCENT'),
    currency = '',
    error = null,
  } = $props();
</script>
