<div class="alert alert-danger d-flex align-items-start" role="alert">
  <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
  <div>
    <b>{$_('common.load-error-title')}</b>
    <div>{$_(errorKey(error))}</div>
    {#if error !== 'NO_PERMISSION'}
      <button class="btn alert-btn mt-2" type="button" onclick={retry}>
        {$_('common.try-again')}
      </button>
    {/if}
  </div>
</div>

<script>
  import { invalidateAll } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import { errorKey } from '../utils/api.js';

  let { error, onRetry = null } = $props();

  function retry() {
    if (onRetry) onRetry();
    else invalidateAll();
  }
</script>
