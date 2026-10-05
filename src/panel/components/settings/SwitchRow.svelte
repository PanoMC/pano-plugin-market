<SettingRow {id} {label} {hint} {error} center>
  <div class="form-check form-switch m-0">
    <input
      {id}
      class="form-check-input"
      class:is-invalid={error}
      type="checkbox"
      role="switch"
      {disabled}
      bind:checked />
  </div>
</SettingRow>

<script>
  import SettingRow from './SettingRow.svelte';

  // A switch row: `checked` is bound by the section, `error` marks the switch invalid.
  let { id, label, hint = '', error = '', disabled = false, checked = $bindable(false) } = $props();
</script>
