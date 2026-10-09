<div class="card">
  <div class="card-body d-flex flex-wrap align-items-center justify-content-between gap-3">
    <div>
      <h6 class="mb-1">{$_('nav-section-webhooks')}</h6>
      <p class="text-body-secondary mb-0">{$_('settings.webhooks.managed-in-platform')}</p>
    </div>
    <a class="btn btn-secondary" href={`${base}${PLATFORM_WEBHOOKS_PATH}`}>
      {$_('settings.webhooks.open-platform')}
      <i class="fa-solid fa-arrow-up-right-from-square ms-2" aria-hidden="true"></i>
    </a>
  </div>
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { PLATFORM_WEBHOOKS_PATH } from '../../utils/settings.js';
</script>
