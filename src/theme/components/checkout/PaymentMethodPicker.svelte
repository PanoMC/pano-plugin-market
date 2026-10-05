<div class="card">
  <div class="card-body vstack gap-3">
    <h2 class="h5 mb-0">{$_('theme.checkout.payment')}</h2>

    {#if alertKey}
      <div class="alert alert-warning mb-0" role="alert">
        {$_(alertKey)}
        {#if alertDetail}
          <span class="d-block small">{alertDetail}</span>
        {/if}
      </div>
    {/if}

    {#if view.mode === 'NONE_NEEDED'}
      <p class="mb-0 text-body-secondary">{$_('theme.checkout.no-payment-needed')}</p>
    {:else if view.mode === 'NO_METHOD'}
      <div class="alert alert-warning mb-0" role="alert">
        {$_('theme.checkout.no-payment-method')}
      </div>
    {:else}
      <div class="list-group" role="radiogroup" aria-label={$_('theme.checkout.payment')}>
        {#each view.methods as method (method.id)}
          {@const unavailable = method.available === false}
          {@const logo = logoSource(method.logoUrl)}
          {@const color = safeColor(method.color)}
          <label class={['list-group-item', 'd-flex', 'gap-3', unavailable && 'opacity-50']}>
            <input
              class="form-check-input flex-shrink-0 mt-1"
              type="radio"
              name={PAY_GROUP}
              value={method.id}
              checked={!payWithCredits && selectedId === method.id}
              disabled={unavailable || disabled}
              onchange={() => onselect(method.id)} />
            <span class="flex-shrink-0 text-center">
              {#if logo}
                <img
                  src={logo.relative ? `${base}${logo.url}` : logo.url}
                  height="24"
                  alt=""
                  class="object-fit-contain" />
              {:else}
                <i
                  class={[safeIcon(method.icon), 'fa-fw']}
                  style={color ? `color: ${color}` : undefined}
                  aria-hidden="true"></i>
              {/if}
            </span>
            <span class="flex-grow-1">
              <span class="fw-bold d-block">{method.label}</span>
              {#if method.description}
                <small class="d-block text-body-secondary">{method.description}</small>
              {/if}
              {#if method.hint}
                <small class="d-block text-body-secondary">{method.hint}</small>
              {/if}
              {#if unavailable}
                <small class="d-block text-danger"
                  >{$_(unavailableKey(method.unavailableReason))}</small>
              {/if}
            </span>
            {#if Number(method.feeAmount) > 0}
              <span class="badge text-bg-secondary align-self-start flex-shrink-0">
                +{formatMoney(method.feeAmount, currency, { removeCents })}
              </span>
            {/if}
          </label>
        {/each}
      </div>

      {#if method}
        {#if external}
          <div class="alert alert-info mb-0" role="status">
            {$_('theme.errors.EXTERNAL_PRICING')}
          </div>
        {/if}
        {#if notices.length > 0}
          <ul class="list-unstyled small mb-0">
            {#each notices as notice (notice.url)}
              <li>
                <a href={notice.url} target="_blank" rel="noopener noreferrer"
                  >{notice.label}<i
                    class="fa-solid fa-arrow-up-right-from-square ms-1"
                    aria-hidden="true"></i
                  ></a>
              </li>
            {/each}
          </ul>
        {/if}
      {/if}
    {/if}
  </div>
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import {
    isExternalPricing,
    logoSource,
    noticeLinks,
    PAY_GROUP,
    pickerView,
    safeColor,
    safeIcon,
    selectedMethod,
    unavailableKey,
  } from '../../lib/paymentModel.js';
  import { formatMoney } from '../../utils/format.js';

  /**
   * The payment methods of the quote, in server order (`quote`), one radio group with the credits radio
   * (name market-pay). `selectedId` = draft.paymentMethodId; `payWithCredits` = the credits radio is chosen (no
   * method is shown selected). `alertKey` / `alertDetail`: an alert above the list (PAYMENT_METHOD_UNAVAILABLE
   * from the submit). onselect(id). The kind of payment UI (redirect, form, iframe...) is unknown until the
   * payment is started, so the picker is provider-agnostic.
   */
  let {
    quote = null,
    selectedId = null,
    payWithCredits = false,
    currency = '',
    removeCents = false,
    disabled = false,
    alertKey = '',
    alertDetail = '',
    onselect = () => {},
  } = $props();

  const view = $derived(pickerView(quote));
  const method = $derived(payWithCredits ? null : selectedMethod(quote, selectedId));
  const external = $derived(isExternalPricing(method));
  const notices = $derived(noticeLinks(method));
</script>
