<div class="vstack gap-2">
  <div class="form-check form-switch m-0">
    <input
      class="form-check-input"
      type="checkbox"
      role="switch"
      id="{id}-switch"
      bind:checked={unlimited} />
    <label class="form-check-label" for="{id}-switch">{label}</label>
  </div>
  {#if !unlimited}
    <div class="row g-3">
      <div class="col-md-6">
        <div class="form-floating">
          <input
            type="date"
            class="form-control"
            id="{id}-start"
            placeholder={$_('modals.discount-form.start-date')}
            bind:value={startDate} />
          <label for="{id}-start">{$_('modals.discount-form.start-date')}</label>
        </div>
      </div>
      <div class="col-md-6">
        <div class="form-floating">
          <input
            type="date"
            class="form-control"
            class:is-invalid={error}
            id="{id}-end"
            placeholder={$_('modals.discount-form.end-date')}
            bind:value={expiryDate} />
          <label for="{id}-end">{$_('modals.discount-form.end-date')}</label>
        </div>
      </div>
      {#if error}
        <div class="col-12"><ErrorText code={error} /></div>
      {/if}
    </div>
  {/if}
</div>

<script>
  import { _ } from '../../../i18n';
  import ErrorText from './ErrorText.svelte';

  let {
    id,
    label,
    unlimited = $bindable(true),
    startDate = $bindable(''),
    expiryDate = $bindable(''),
    error = null,
  } = $props();
</script>
