<div class="form-control d-flex flex-wrap align-items-center gap-1 h-auto" class:is-invalid={flash}>
  {#each values as value (value)}
    <span class="badge text-bg-secondary d-inline-flex align-items-center gap-1">
      {value}
      <button
        class="btn-close btn-close-white"
        type="button"
        aria-label={$_('common.remove')}
        onclick={() => remove(value)}></button>
    </span>
  {/each}
  <input
    class="border-0 shadow-none bg-transparent flex-grow-1"
    style="min-width: 8rem; outline: none;"
    type="text"
    autocomplete="off"
    disabled={full}
    {placeholder}
    bind:value={text}
    {onkeydown} />
</div>

<script>
  import { _ } from '../../i18n';

  let {
    values = $bindable([]),
    validate = () => true,
    max = Infinity,
    placeholder = '',
  } = $props();

  let text = $state('');
  let flash = $state(false);
  let timer = null;

  const full = $derived(values.length >= max);

  function flashInvalid() {
    flash = true;
    clearTimeout(timer);
    timer = setTimeout(() => (flash = false), 800);
  }

  function commit() {
    const value = text.trim();
    if (value === '') return;
    if (values.includes(value)) {
      text = '';
      return;
    }
    if (full || !validate(value)) {
      flashInvalid();
      return;
    }
    values = [...values, value];
    text = '';
  }

  function onkeydown(event) {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault();
      commit();
    } else if (event.key === 'Backspace' && text === '' && values.length > 0) {
      values = values.slice(0, -1);
    }
  }

  function remove(value) {
    values = values.filter((v) => v !== value);
  }

  $effect(() => () => clearTimeout(timer));
</script>
