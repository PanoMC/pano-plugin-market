<div class="alert alert-danger d-flex align-items-center gap-2" role="alert">
  <i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>
  <div>{message || $_('theme.errors.NETWORK')}</div>
  {#if onretry}
    <button type="button" class="btn btn-sm btn-outline-danger ms-auto" onclick={() => onretry()}>
      {retryLabel || $_('theme.common.retry')}
    </button>
  {/if}
</div>

<script>
  import { _ } from '../../../i18n.js';

  /** message: already translated text (defaults to theme.errors.NETWORK); onretry: shows the Retry button. */
  let { message = '', retryLabel = '', onretry = null } = $props();
</script>
