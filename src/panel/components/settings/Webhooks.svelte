<ConfirmModal bind:this={confirm} />
<WebhookModal bind:this={webhookModal} onSaved={reload} onSecret={(value) => secretModal?.open(value)} />
<SecretCreatedModal bind:this={secretModal} />

{#if loadError}
  <LoadError error={loadError} onRetry={load} />
{:else if loading}
  <div class="text-center text-body-secondary py-5">
    <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
    <span class="visually-hidden">{$_('common.loading')}</span>
  </div>
{:else}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('settings.webhooks.count', { values: { count: webhooks.length } })}
      </div>
      <div slot="right" class="dropdown">
        <button
          type="button"
          class="btn btn-link"
          data-bs-toggle="dropdown"
          aria-expanded="false"
          title={$_('common.actions')}
          aria-label={$_('common.actions')}>
          <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
        </button>
        <div class="dropdown-menu dropdown-menu-end">
          <button type="button" class="dropdown-item" onclick={() => webhookModal?.open(null, context)}>
            <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
            {$_('settings.webhooks.create')}
          </button>
        </div>
      </div>
    </CardHeader>

    {#if webhooks.length === 0}
      <NoContent icon="" />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover align-middle">
          <thead>
            <tr>
              <th class="align-middle text-nowrap" scope="col"></th>
              <th class="align-middle text-nowrap" scope="col">{$_('common.name')}</th>
              <th class="align-middle text-nowrap" scope="col">{$_('settings.webhooks.table.url')}</th>
              <th class="align-middle text-nowrap" scope="col">{$_('settings.webhooks.table.events')}</th>
              <th class="align-middle text-nowrap" scope="col">{$_('settings.webhooks.table.format')}</th>
              <th class="align-middle text-nowrap" scope="col">{$_('settings.webhooks.table.signing')}</th>
              <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('settings.webhooks.table.last-delivery')}
              </th>
            </tr>
          </thead>
          <tbody>
            {#each webhooks as hook (hook.id)}
              {@const status = endpointStatus(hook)}
              {@const count = eventsCount(hook)}
              <tr>
                <th class="align-middle" scope="row">
                  <div class="dropdown position-static">
                    <button
                      type="button"
                      class="btn btn-link"
                      data-bs-toggle="dropdown"
                      aria-expanded="false"
                      title={$_('common.actions')}
                      aria-label={$_('common.actions')}>
                      <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
                    </button>
                    <div class="dropdown-menu dropdown-menu-start">
                      <button type="button" class="dropdown-item" onclick={() => webhookModal?.open(hook, context)}>
                        <i class="fa-solid fa-pen me-2" aria-hidden="true"></i>
                        {$_('common.edit')}
                      </button>
                      <button type="button" class="dropdown-item" disabled={busy} onclick={() => sendTest(hook)}>
                        <i class="fa-solid fa-paper-plane me-2" aria-hidden="true"></i>
                        {$_('settings.webhooks.send-test')}
                      </button>
                      <a
                        class="dropdown-item"
                        href="{base}/market/settings?section=webhook-deliveries&endpointId={hook.id}">
                        <i class="fa-solid fa-list me-2" aria-hidden="true"></i>
                        {$_('settings.webhooks.deliveries')}
                      </a>
                      <button type="button" class="dropdown-item" disabled={busy} onclick={() => toggle(hook)}>
                        <i class="fa-solid {hook.enabled ? 'fa-pause' : 'fa-play'} me-2" aria-hidden="true"></i>
                        {hook.enabled ? $_('settings.webhooks.disable') : $_('settings.webhooks.enable')}
                      </button>
                      <button type="button" class="dropdown-item text-danger" onclick={() => askDelete(hook)}>
                        <i class="fa-solid fa-trash me-2" aria-hidden="true"></i>
                        {$_('common.delete')}
                      </button>
                    </div>
                  </div>
                </th>
                <td class="text-nowrap">{hook.name}</td>
                <td class="text-nowrap">
                  <span use:tooltip={[hook.url]}>{shortUrl(hook.url)}</span>
                </td>
                <td class="text-nowrap">
                  {count === null ? $_('settings.webhooks.all-events') : count}
                </td>
                <td class="text-nowrap">
                  {#if hook.format === 'DISCORD'}
                    <i class="fa-brands fa-discord me-1" aria-hidden="true"></i>
                  {/if}
                  {$_(`enums.webhook-format.${hook.format === 'DISCORD' ? 'DISCORD' : 'JSON'}`)}
                </td>
                <td class="text-nowrap">
                  {$_(`enums.webhook-signing.${hook.signing === 'HMAC_SHA256' ? 'HMAC_SHA256' : 'NONE'}`)}
                </td>
                <td class="text-nowrap">
                  {#if status.kind === 'enabled'}
                    <span class="badge text-bg-success">{$_('settings.webhooks.enabled')}</span>
                  {:else}
                    <span class="badge text-bg-secondary">{$_('settings.webhooks.disabled')}</span>
                    {#if status.auto}
                      <span
                        class="badge text-bg-danger ms-1"
                        use:tooltip={[$_('settings.webhooks.auto-disabled-hint')]}>
                        {$_('settings.webhooks.auto-disabled')}
                      </span>
                    {/if}
                  {/if}
                </td>
                <td class="text-nowrap">
                  {#if hook.lastDeliveryAt}
                    {hook.lastStatusCode ?? ''}
                    {dateText(hook.lastDeliveryAt)}
                  {:else}
                    —
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
  </div>
{/if}

<script>
  import { onMount } from 'svelte';
  import ApiUtil from '@panomc/sdk/utils/api';
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _, showErrorToast, showSuccessToast } from '../../../i18n';
  import ConfirmModal from '../ConfirmModal.svelte';
  import LoadError from '../LoadError.svelte';
  import SecretCreatedModal from '../modals/SecretCreatedModal.svelte';
  import WebhookModal from '../modals/WebhookModal.svelte';
  import { call, marketPath } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import { toastError } from '../../utils/toast.js';
  import { endpointStatus, eventsCount, shortUrl, testOutcome } from '../../utils/webhooks.js';

  // Section `webhooks` of the settings page (13 §18.1). Loads GET /webhooks itself.
  let confirm = $state(null);
  let webhookModal = $state(null);
  let secretModal = $state(null);
  let webhooks = $state.raw([]);
  let context = $state.raw({ defaults: {}, eventNames: [] });
  let loading = $state(true);
  let loadError = $state(null);
  let busy = $state(false);

  const dateText = (epoch) => (epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—');

  async function fetchAll() {
    const result = await call(ApiUtil.get({ path: marketPath('/webhooks') }));
    if (!result.ok) return result.error;
    webhooks = result.body.webhooks ?? [];
    context = {
      defaults: result.body.defaults ?? {},
      eventNames: result.body.eventNames ?? [],
    };
    return null;
  }

  async function load() {
    loading = true;
    loadError = await fetchAll();
    loading = false;
  }

  // A refresh after a change keeps the table on screen; a failure only toasts.
  async function reload() {
    const error = await fetchAll();
    if (error) showErrorToast($_('common.error-generic'));
  }

  onMount(load);

  async function sendTest(hook) {
    if (busy) return;
    busy = true;
    try {
      const result = await call(ApiUtil.post({ path: marketPath(`/webhooks/${hook.id}/test`), body: {} }));
      if (!result.ok) {
        toastError($_, result);
        if (result.error === 'NOT_FOUND') await reload();
        return;
      }
      const outcome = testOutcome(result.body);
      if (outcome.ok)
        showSuccessToast(
          $_('settings.webhooks.toast-test-ok', { values: { status: outcome.status, ms: outcome.ms } }),
        );
      else
        showErrorToast(
          $_('settings.webhooks.toast-test-failed', { values: { status: outcome.status } }),
        );
      await reload();
    } finally {
      busy = false;
    }
  }

  async function toggle(hook) {
    if (busy) return;
    busy = true;
    try {
      const result = await call(
        ApiUtil.put({ path: marketPath(`/webhooks/${hook.id}`), body: { enabled: !hook.enabled } }),
      );
      if (!result.ok) toastError($_, result);
      await reload();
    } finally {
      busy = false;
    }
  }

  function askDelete(hook) {
    confirm?.open({
      icon: 'fa-solid fa-trash',
      variant: 'danger',
      title: $_('settings.webhooks.confirm-delete.title'),
      description: $_('settings.webhooks.confirm-delete.description', { values: { name: hook.name } }),
      confirmLabel: $_('common.delete'),
      onConfirm: async () => {
        const result = await call(ApiUtil.delete({ path: marketPath(`/webhooks/${hook.id}`) }));
        if (!result.ok && result.error !== 'NOT_FOUND') {
          toastError($_, result);
          return false;
        }
        showSuccessToast($_('settings.webhooks.toast-deleted'));
        await reload();
      },
    });
  }
</script>
