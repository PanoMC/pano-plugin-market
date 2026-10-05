<div data-preview={field.type}>
  {#if field.type === 'CHECKBOX'}
    <div class="form-check">
      <input
        class="form-check-input"
        type="checkbox"
        id="{uid}-preview"
        checked={field.defaultValue === 'true'}
        disabled />
      <label class="form-check-label" for="{uid}-preview">{text}</label>
    </div>
  {:else}
    <label class="form-label" for="{uid}-preview">{text}</label>
    {#if field.type === 'SELECT'}
      <select class="form-select" id="{uid}-preview" disabled>
        {#each field.options ?? [] as option (option.value)}
          <option value={option.value} selected={option.value === field.defaultValue}>
            {option.label}
          </option>
        {/each}
      </select>
    {:else}
      <input
        class="form-control"
        id="{uid}-preview"
        type={INPUT_TYPE[field.type] ?? 'text'}
        placeholder={field.placeholder || ''}
        value={field.defaultValue ?? ''}
        disabled />
    {/if}
  {/if}
  {#if field.helpText}
    <div class="form-text">{field.helpText}</div>
  {/if}
</div>

<script>
  // Disabled preview of one custom field as the buyer sees it (13 §8.5): TEXT / USERNAME /
  // DISCORD_ID are text inputs, EMAIL an e-mail input, NUMBER a number input, SELECT a select,
  // CHECKBOX a form-check. Values come from the admin's own draft and are rendered as text.
  let { field } = $props();

  const uid = $props.id();
  const INPUT_TYPE = { EMAIL: 'email', NUMBER: 'number' };
  const text = $derived(`${field.label || field.fieldKey || ''}${field.required ? ' *' : ''}`);
</script>
