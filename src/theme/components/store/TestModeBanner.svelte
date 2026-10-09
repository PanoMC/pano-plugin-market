{#if settings?.testMode}
  <div
    class="market-test-mode-banner market-test-mode-banner__alert alert alert-warning d-flex align-items-center gap-2"
    role="status">
    <i class="fa-solid fa-flask" aria-hidden="true"></i>
    <div>{$_('theme.store.test-mode')}</div>
  </div>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const _ = market._;

  let { settings = {} } = $props();
</script>
