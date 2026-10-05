<div class="input-group">
  <input
    {id}
    class="form-control"
    class:is-invalid={invalid || malformed}
    type="text"
    inputmode="decimal"
    autocomplete="off"
    {placeholder}
    {disabled}
    value={text}
    {oninput} />
  {#if currency}
    <span class="input-group-text">{currency}</span>
  {/if}
</div>

<script>
  import { parseMoney } from '../utils/format.js';

  let {
    value = $bindable(null),
    currency = '',
    exponent = 2,
    invalid = false,
    placeholder = '',
    disabled = false,
    id = undefined,
  } = $props();

  // The typed text is kept locally so "12," or "" survive typing; the bound value is the parsed Number.
  let text = $state(
    value === null || value === undefined || Number.isNaN(value) ? '' : String(value),
  );
  let malformed = $state(false);

  // A value changed from outside (reset, loaded record) replaces the text, unless it is what we wrote.
  $effect(() => {
    const current = value;
    if (current === null || current === undefined) {
      if (parseMoney(text, exponent) !== null) {
        text = '';
        malformed = false;
      }
    } else if (!Number.isNaN(current) && parseMoney(text, exponent) !== current) {
      text = String(current);
      malformed = false;
    }
  });

  function oninput(event) {
    text = event.currentTarget.value;
    const parsed = parseMoney(text, exponent);
    malformed = Number.isNaN(parsed);
    value = parsed;
  }
</script>
