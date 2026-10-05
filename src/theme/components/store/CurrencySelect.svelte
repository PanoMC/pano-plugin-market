<label class="visually-hidden" for={id}>{$_('theme.store.currency-label')}</label>
<select {id} class="form-select form-select-sm w-auto" value={selected} onchange={change}>
  {#each codes as code (code)}
    <option value={code}>{code}</option>
  {/each}
</select>

<script>
  import { _ } from '../../../i18n.js';

  /** currencies: ISO codes or { code } objects; selected: the code in use; onchange(code). */
  let { currencies = [], selected = '', id = 'marketCurrency', onchange } = $props();

  const codes = $derived(
    currencies.map((entry) => (typeof entry === 'string' ? entry : entry?.code)).filter(Boolean),
  );

  function change(event) {
    onchange?.(event.currentTarget.value);
  }
</script>
