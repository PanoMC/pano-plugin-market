<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.webhook-delivery.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body">
        {#if loading}
          <div class="text-center text-body-secondary py-4">
            <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
            <span class="visually-hidden">{$_('common.loading')}</span>
          </div>
        {:else if delivery}
          <dl class="row mb-0">
            <dt class="col-sm-4">{$_('common.status')}</dt>
            <dd class="col-sm-8"><StatusBadge kind="webhook" value={delivery.status} /></dd>
            <dt class="col-sm-4">{$_('modals.webhook-delivery.event')}</dt>
            <dd class="col-sm-8 text-break">{delivery.event ?? '—'}</dd>
            <dt class="col-sm-4">{$_('modals.webhook-delivery.attempts')}</dt>
            <dd class="col-sm-8">{delivery.attempts ?? 0}</dd>
            <dt class="col-sm-4">{$_('modals.webhook-delivery.http')}</dt>
            <dd class="col-sm-8">{delivery.lastStatusCode ?? '—'}</dd>
            <dt class="col-sm-4">{$_('modals.webhook-delivery.duration')}</dt>
            <dd class="col-sm-8">
              {delivery.durationMs === null || delivery.durationMs === undefined
                ? '—'
                : $_('modals.webhook-delivery.ms', { values: { ms: delivery.durationMs } })}
            </dd>
            <dt class="col-sm-4">{$_('modals.webhook-delivery.error')}</dt>
            <dd class="col-sm-8 text-break">{delivery.lastError || '—'}</dd>
            <dt class="col-sm-4">{$_('modals.webhook-delivery.created')}</dt>
            <dd class="col-sm-8">{dateText(delivery.createdAt)}</dd>
            <dt class="col-sm-4">{$_('modals.webhook-delivery.delivered')}</dt>
            <dd class="col-sm-8">{dateText(delivery.deliveredAt)}</dd>
          </dl>

          {#if bodyText}
            <h6 class="mt-4">{$_('modals.webhook-delivery.body')}</h6>
            <pre class="mb-0">{bodyText}</pre>
          {/if}
          {#if responseText}
            <h6 class="mt-4">{$_('modals.webhook-delivery.response')}</h6>
            <pre class="mb-0">{responseText}</pre>
          {/if}
        {/if}
      </div>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _ } from '../../../i18n';
  import { call, marketPath } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import { toastError } from '../../utils/toast.js';
  import { prettyText } from '../../utils/webhooks.js';
  import { hideModal, showModal } from '../order-detail/send.js';
  import StatusBadge from '../StatusBadge.svelte';

  // One delivery with its stored body and the last response; both render as text in a <pre>.
  let modalElement = $state(null);
  let delivery = $state.raw(null);
  let loading = $state(false);

  const bodyText = $derived(delivery ? prettyText(delivery.body) : '');
  const responseText = $derived(delivery ? prettyText(delivery.lastResponse) : '');
  const dateText = (epoch) => (epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—');

  let tag = 0;

  export async function open(row) {
    const mine = ++tag;
    delivery = null;
    loading = true;
    showModal(modalElement);
    const result = await call(ApiUtil.get({ path: marketPath(`/webhook-deliveries/${row.id}`) }));
    if (mine !== tag) return;
    loading = false;
    if (!result.ok) {
      toastError($_, result);
      hideModal(modalElement);
      return;
    }
    delivery = result.body.delivery ?? result.body;
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
