<div class="card text-center" role="status">
  <div class="card-body py-5">
    <i class={[icon, 'text-body-secondary', 'mb-3']} aria-hidden="true"></i>
    <p class="mb-0">{text}</p>
    {#if onretry}
      <button type="button" class="btn btn-primary mt-3" onclick={() => onretry()}>
        <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>{$_('theme.common.retry')}
      </button>
    {/if}
  </div>
</div>

<script>
  import { _ } from '../../../i18n.js';

  /** icon: Font Awesome classes; text: already translated; onretry: shows the Retry button. */
  let { icon = 'fa-solid fa-store fa-3x', text = '', onretry = null } = $props();
</script>
