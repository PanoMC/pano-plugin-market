<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-lg modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.payment-event.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body">
        {#if event}
          <dl class="row mb-0">
            <dt class="col-sm-4">{$_('common.status')}</dt>
            <dd class="col-sm-8">
              <StatusBadge kind="event" value={event.status} />
              {#if isUnverified(event)}
                <span class="badge text-bg-danger ms-1">{$_('pages.payment-events.unverified')}</span>
              {/if}
            </dd>
            {#each rows as [key, value] (key)}
              <dt class="col-sm-4">{$_(`modals.payment-event.rows.${key}`)}</dt>
              <dd class="col-sm-8 text-break">{value === null || value === '' ? '—' : value}</dd>
            {/each}
            <dt class="col-sm-4">{$_('modals.payment-event.date')}</dt>
            <dd class="col-sm-8">{dateText(event.createdAt)}</dd>
          </dl>

          {#if showBodies && headersText}
            <h6 class="mt-4">{$_('modals.payment-event.headers')}</h6>
            <pre class="mb-0">{headersText}</pre>
          {/if}
          {#if showBodies && bodyText}
            <h6 class="mt-4">{$_('modals.payment-event.body')}</h6>
            <pre class="mb-0">{bodyText}</pre>
          {/if}
        {/if}
      </div>
    </div>
  </div>
</div>

<script>
  import { page } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { currentLocale } from '../../utils/locale.js';
  import { canSeeBodies, detailRows, isUnverified, prettyText } from '../../utils/payment-events.js';
  import { hideModal, showModal } from '../order-detail/send.js';
  import StatusBadge from '../StatusBadge.svelte';

  let modalElement = $state(null);
  let event = $state.raw(null);

  const user = $derived($page.data?.user);
  const rows = $derived(event ? detailRows(event) : []);
  // Raw bodies / headers are only returned (and shown) with SET; both render as text in a <pre>.
  const showBodies = $derived(canSeeBodies(user));
  const bodyText = $derived(event ? prettyText(event.body) : '');
  const headersText = $derived(event ? prettyText(event.headers) : '');

  const dateText = (epoch) => (epoch ? new Date(Number(epoch)).toLocaleString(currentLocale()) : '—');

  export function open(row) {
    event = row;
    showModal(modalElement);
  }

  export function hide() {
    hideModal(modalElement);
  }

  // Cleanup is returned from the effect (no top-level onDestroy).
  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
