<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable modal-lg">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">{$_('modals.shipping-address.title')}</h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit}>
        <div class="modal-body">
          <div class="row g-3">
            {#each TEXT_FIELDS as field (field)}
              <div class={field === 'line1' || field === 'line2' ? 'col-12' : 'col-12 col-md-6'}>
                <input
                  class="form-control"
                  class:is-invalid={shown[field]}
                  type={field === 'email' ? 'email' : field === 'phone' ? 'tel' : 'text'}
                  placeholder={$_(`modals.shipping-address.${field}`)}
                  aria-label={$_(`modals.shipping-address.${field}`)}
                  bind:value={form[field]} />
              </div>
              {#if field === 'email'}
                <div class="col-12 col-md-6">
                  <select
                    class="form-select"
                    class:is-invalid={shown.country}
                    aria-label={$_('modals.shipping-address.country')}
                    bind:value={form.country}>
                    <option value="">{$_('modals.shipping-address.country')}</option>
                    {#each countries as country (country.code)}
                      <option value={country.code}>{country.name}</option>
                    {/each}
                  </select>
                </div>
              {/if}
            {/each}
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {$_('common.save')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { _, showErrorToast } from '../../../i18n';
  import { countryOptions } from '../product/countries.js';
  import { isStaleError } from '../order-detail/actions.js';
  import { hideModal, showModal } from '../order-detail/send.js';
  import { call } from '../../utils/api.js';
  import { currentLocale } from '../../utils/locale.js';
  import {
    ADDRESS_FIELDS,
    addressFieldErrors,
    addressRequest,
    failureText,
    initialAddress,
  } from '../../utils/shipments.js';

  // detail: GET /orders/:id (order.shippingAddress prefills the form). onDone(toastKey) refreshes the
  // order and toasts; onStale() refreshes after ORDER_NOT_SHIPPABLE and the other stale codes.
  let { detail = null, onDone = async () => {}, onStale = async () => {} } = $props();

  // `country` has its own select, placed after `email`.
  const TEXT_FIELDS = ADDRESS_FIELDS.filter((f) => f !== 'country');

  let modalElement = $state(null);
  let form = $state(initialAddress(null));
  let saving = $state(false);
  let invalid = $state({});
  let serverInvalid = $state({});

  const shown = $derived({ ...serverInvalid, ...invalid });
  const countries = $derived(countryOptions(currentLocale()));

  export function open() {
    form = initialAddress(detail?.order?.shippingAddress);
    invalid = {};
    serverInvalid = {};
    saving = false;
    showModal(modalElement);
  }

  async function submit(event) {
    event.preventDefault();
    const orderId = detail?.order?.id;
    if (saving || !orderId) return;
    serverInvalid = {};
    const built = addressRequest(orderId, form);
    invalid = built.error ?? {};
    if (built.error) return;
    saving = true;
    try {
      const result = await call(
        ApiUtil.put({ path: built.request.path, body: built.request.body }),
      );
      if (result.ok) {
        hideModal(modalElement);
        await onDone('modals.shipping-address.toast');
        return;
      }
      showErrorToast(failureText($_, result.error, result.body));
      if (result.error === 'SHIPPING_ADDRESS_REQUIRED') {
        serverInvalid = addressFieldErrors(result.body);
        return;
      }
      if (isStaleError(result.error)) {
        hideModal(modalElement);
        await onStale();
      }
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
