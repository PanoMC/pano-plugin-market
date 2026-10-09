<div
  class="market-error-alert market-error-alert__alert alert alert-danger d-flex align-items-center gap-2"
  role="alert">
  <i class="fa-solid fa-triangle-exclamation" aria-hidden="true"></i>
  <div>{message || $_('theme.errors.NETWORK')}</div>
  {#if onretry}
    <button
      type="button"
      class="market-error-alert__action btn btn-sm btn-outline-danger ms-auto"
      onclick={() => onretry()}>
      {retryLabel || $_('theme.common.retry')}
    </button>
  {/if}
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const { _ } = plugin('market');

  /** message: already translated text (defaults to theme.errors.NETWORK); onretry: shows the Retry button. */
  let { message = '', retryLabel = '', onretry = null } = $props();
</script>
