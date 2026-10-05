{#if code}
  <div class="invalid-feedback d-block">{$_(`modals.discount-form.error.${code}`)}</div>
{/if}

<script>
  import { _ } from '../../../i18n';

  let { code = null } = $props();
</script>
