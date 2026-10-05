{#if settings?.testMode}
  <div class="alert alert-warning d-flex align-items-center gap-2" role="status">
    <i class="fa-solid fa-flask" aria-hidden="true"></i>
    <div>{$_('theme.store.test-mode')}</div>
  </div>
{/if}

<script>
  import { _ } from '../../../i18n.js';

  let { settings = {} } = $props();
</script>
