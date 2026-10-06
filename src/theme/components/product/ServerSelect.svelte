{#if choices.length === 1}
  <div class="small">
    <i class="fa-solid fa-server me-1 text-body-secondary" aria-hidden="true"></i>{$_(
      'theme.product.server-fixed',
      { values: { server: choices[0].name } },
    )}
  </div>
{:else if choices.length > 1}
  <div>
    <label class="form-label" for={ID_SERVER}>{$_('theme.product.server-label')}</label>
    <select
      id={ID_SERVER}
      class={['form-select', error && 'is-invalid']}
      required
      value={value == null ? '' : String(value)}
      aria-invalid={error ? 'true' : undefined}
      onchange={(event) =>
        onchange(event.currentTarget.value === '' ? null : Number(event.currentTarget.value))}>
      <option value="" disabled>{$_('theme.product.server-placeholder')}</option>
      {#each choices as choice (choice.id)}
        <option value={String(choice.id)}>{choice.name}</option>
      {/each}
    </select>
    {#if error}
      <div class="invalid-feedback">{$_(`theme.errors.${error}`)}</div>
    {/if}
  </div>
{/if}

<script>
  import { _ } from '../../../i18n.js';
  import { ID_SERVER } from './productModel.js';

  /** Buyer-chosen server: one choice is shown as text (the page sends it), several need a pick. */
  let { choices = [], value = null, error = null, onchange = () => {} } = $props();
</script>
