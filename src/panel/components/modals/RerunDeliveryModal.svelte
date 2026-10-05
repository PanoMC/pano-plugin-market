<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.rerun.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body vstack gap-3">
          <div class="vstack gap-1">
            {#each RERUN_SCOPES as value (value)}
              <div class="form-check">
                <input
                  class="form-check-input"
                  type="radio"
                  name="rerun-scope"
                  id="rerun-scope-{value}"
                  {value}
                  bind:group={scope} />
                <label class="form-check-label" for="rerun-scope-{value}">
                  {$_(`modals.rerun.scope.${value}`)}
                </label>
              </div>
            {/each}
          </div>

          {#if scope === 'items'}
            <div
              class="vstack gap-1 border rounded p-2 overflow-auto"
              class:border-danger={invalid.selection}
              style="max-height: 220px;">
              {#each items as item (item.id)}
                <div class="form-check" class:ms-4={item.kind === 'BUNDLE_CHILD'}>
                  <input
                    class="form-check-input"
                    type="checkbox"
                    id="rerun-item-{item.id}"
                    value={item.id}
                    bind:group={itemIds} />
                  <label class="form-check-label" for="rerun-item-{item.id}">
                    {itemName(item)}
                  </label>
                </div>
              {/each}
            </div>
          {:else if scope === 'deliveries'}
            <div
              class="vstack gap-1 border rounded p-2 overflow-auto"
              class:border-danger={invalid.selection}
              style="max-height: 220px;">
              {#each deliveries as delivery (delivery.id)}
                <div class="form-check">
                  <input
                    class="form-check-input"
                    type="checkbox"
                    id="rerun-delivery-{delivery.id}"
                    value={delivery.id}
                    disabled={!rerunSelectable(delivery, canPay)}
                    bind:group={deliveryIds} />
                  <label class="form-check-label" for="rerun-delivery-{delivery.id}">
                    #{delivery.id}
                    {delivery.productName ?? ''}
                    <span class="text-body-secondary">
                      · {$_(`enums.action-type.${delivery.actionType}`)}
                      · {$_(`enums.phase.${delivery.phase}`)}
                    </span>
                    <StatusBadge kind="delivery" value={delivery.status} />
                  </label>
                </div>
              {/each}
            </div>
          {/if}

          <select class="form-select" aria-label={$_('modals.rerun.phase')} bind:value={phase}>
            {#each RERUN_PHASES as value (value)}
              <option {value}>{$_(`enums.phase.${value}`)}</option>
            {/each}
          </select>

          {#if grantsAgain}
            <div class="alert alert-danger d-flex align-items-start mb-0" role="alert">
              <i class="fa-solid fa-circle-exclamation me-3 mt-1" aria-hidden="true"></i>
              <div>
                <b>{$_('modals.rerun.grant-again-title')}</b>
                <div>{$_('modals.rerun.grant-again')}</div>
              </div>
            </div>
          {/if}
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_('modals.rerun.cta')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';
  import { itemName } from '../orders/filters.js';
  import StatusBadge from '../StatusBadge.svelte';
  import {
    RERUN_DELIVERY_STATUSES,
    RERUN_PHASES,
    RERUN_SCOPES,
    rerunGrantsAgain,
    rerunRequest,
    rerunSelectable,
  } from '../order-detail/requests.js';
  import { hideModal, showModal, submitModal } from '../order-detail/send.js';
  import { can } from '../../utils/permissions.js';

  // detail: GET /orders/:id (order, items[], deliveries[]); user: the layout user.
  let { detail = null, user = null, onDone = async () => {}, onStale = async () => {} } = $props();

  let modalElement = $state(null);
  let scope = $state('all');
  let itemIds = $state([]);
  let deliveryIds = $state([]);
  let phase = $state('GRANT');
  let saving = $state(false);
  let invalid = $state({});

  const canPay = $derived(can(user, 'PAY'));
  // The bundle line itself carries no actions; its children do.
  const items = $derived((detail?.items ?? []).filter((i) => i.kind !== 'BUNDLE'));
  const deliveries = $derived(
    (detail?.deliveries ?? []).filter((d) => RERUN_DELIVERY_STATUSES.includes(d.status)),
  );
  const grantsAgain = $derived(
    canPay && rerunGrantsAgain({ scope, itemIds, deliveryIds }, detail?.deliveries),
  );

  /** `open()` = everything; `open({ itemId })` preselects one item (Items card). */
  export function open({ itemId = null } = {}) {
    scope = itemId === null ? 'all' : 'items';
    itemIds = itemId === null ? [] : [itemId];
    deliveryIds = [];
    phase = 'GRANT';
    invalid = {};
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    if (saving || !detail?.order) return;
    const built = rerunRequest(detail.order.id, { scope, itemIds, deliveryIds, phase });
    invalid = built.error ?? {};
    if (built.error) return;
    saving = true;
    try {
      await submitModal({
        request: built.request,
        $_: $_,
        hide: () => hideModal(modalElement),
        onDone: (body) => onDone(body),
        onStale,
      });
    } finally {
      saving = false;
    }
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
