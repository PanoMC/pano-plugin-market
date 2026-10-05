<div class="vstack gap-2">
  {#each rows as row, index (index)}
    <div class="input-group">
      <input
        class="form-control"
        class:is-invalid={keyInvalid(row, index)}
        type="text"
        autocomplete="off"
        placeholder={keyPlaceholder}
        value={row.key}
        oninput={(e) => setField(index, 'key', e.currentTarget.value)} />
      <input
        class="form-control"
        type="text"
        autocomplete="off"
        placeholder={valuePlaceholder}
        value={row.value}
        oninput={(e) => setField(index, 'value', e.currentTarget.value)} />
      <button
        class="btn btn-outline-secondary"
        type="button"
        aria-label={$_('common.remove')}
        onclick={() => remove(index)}>
        <i class="fa-solid fa-xmark"></i>
      </button>
    </div>
  {/each}
  <div>
    <button
      class="btn btn-link btn-sm px-0"
      type="button"
      disabled={rows.length >= max}
      onclick={add}>
      <i class="fa-solid fa-plus me-1"></i>{$_('components.key-value-list.add')}
    </button>
  </div>
</div>

<script>
  import { _ } from '../../i18n';

  let {
    rows = $bindable([]),
    max = Infinity,
    keyPattern = null,
    keyPlaceholder = '',
    valuePlaceholder = '',
  } = $props();

  const pattern = $derived(
    keyPattern instanceof RegExp ? keyPattern : keyPattern ? new RegExp(keyPattern) : null,
  );

  function keyInvalid(row, index) {
    if (row.key === '') return false;
    if (pattern && !pattern.test(row.key)) return true;
    return rows.some((other, i) => i !== index && other.key === row.key);
  }

  function setField(index, field, value) {
    rows = rows.map((row, i) => (i === index ? { ...row, [field]: value } : row));
  }

  function add() {
    if (rows.length < max) rows = [...rows, { key: '', value: '' }];
  }

  function remove(index) {
    rows = rows.filter((_row, i) => i !== index);
  }
</script>
