<div class="card mb-3">
  <div class="card-body d-flex flex-wrap align-items-center justify-content-between gap-3">
    <div>
      <h6 class="mb-1">{$_('settings.mail.templates-link.title')}</h6>
      <p class="text-body-secondary mb-0">{$_('settings.mail.templates-link.body')}</p>
    </div>
    <a class="btn btn-secondary" href={`${base}${PLATFORM_MAIL_TEMPLATES_PATH}`}>
      {$_('settings.mail.templates-link.open')}
      <i class="fa-solid fa-arrow-up-right-from-square ms-2" aria-hidden="true"></i>
    </a>
  </div>
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { PLATFORM_MAIL_TEMPLATES_PATH } from '../../utils/settings.js';
</script>
