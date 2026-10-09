<div class="market-store-state-card card text-center" role="status">
  <div class="market-store-state-card__body card-body py-5">
    <i class={[icon, 'text-body-secondary', 'mb-3']} aria-hidden="true"></i>
    <p class="mb-0">{text}</p>
    {#if onretry}
      <button
        type="button"
        class="market-store-state-card__action btn btn-primary mt-3"
        onclick={() => onretry()}>
        <i class="fa-solid fa-rotate-right me-2" aria-hidden="true"></i>{$_('theme.common.retry')}
      </button>
    {/if}
  </div>
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const _ = market._;

  /** icon: Font Awesome classes; text: already translated; onretry: shows the Retry button. */
  let { icon = 'fa-solid fa-store fa-3x', text = '', onretry = null } = $props();
</script>
