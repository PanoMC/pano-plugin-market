<div class="card">
  <div class="card-body vstack gap-3">
    <div class="d-flex flex-wrap justify-content-between align-items-baseline gap-2">
      <h2 class="h5 mb-0">{$_('theme.checkout.credits', { values: { name: credits.name } })}</h2>
      <div>
        <span class="text-body-secondary">{$_('theme.checkout.credits-balance')}</span>
        <span class="fw-semibold">{formatCredits(credits.balance, credits.name)}</span>
        <a class="ms-2" href="{base}/profile/credits">{$_('theme.checkout.credits-top-up')}</a>
      </div>
    </div>

    {#if alertKey}
      <div class="alert alert-warning mb-0" role="alert">
        {$_(alertKey)}
        {#if alertMax !== null}
          <span class="d-block small">
            {$_('theme.checkout.credits-max-applicable', {
              values: { amount: formatCredits(alertMax, credits.name) },
            })}
          </span>
        {/if}
      </div>
    {/if}

    {#if radio.payable}
      <div class="form-check">
        <input
          id={payId}
          class="form-check-input"
          type="radio"
          name={PAY_GROUP}
          value="credits"
          checked={payWithCredits}
          disabled={disabled || radio.insufficient}
          aria-describedby={radio.insufficient ? `${payId}-help` : undefined}
          onchange={() => onchange(selectCreditsPatch())} />
        <label class="form-check-label" for={payId}>
          {$_('theme.checkout.credits-pay', { values: { name: credits.name } })}
          <span class="badge text-bg-info ms-1">
            {formatCredits(credits.creditTotal, credits.name)}
          </span>
        </label>
        {#if radio.insufficient}
          <div class="form-text text-danger" id="{payId}-help">
            {$_('theme.errors.INSUFFICIENT_CREDITS')}
          </div>
        {/if}
      </div>
    {/if}

    {#if mixed}
      <div class="vstack gap-2">
        <div class="form-check">
          <input
            id={useId}
            class="form-check-input"
            type="checkbox"
            checked={active}
            {disabled}
            onchange={(event) =>
              onchange({
                useCredits: event.currentTarget.checked ? credits.maxApplicable : null,
              })} />
          <label class="form-check-label" for={useId}>
            {$_('theme.checkout.credits-use', { values: { name: credits.name } })}
          </label>
        </div>

        {#if active}
          <div class="input-group">
            <input
              id={amountId}
              class="form-control"
              type="number"
              inputmode="decimal"
              min="0"
              max={credits.maxApplicable}
              step="0.01"
              aria-label={$_('theme.checkout.credits-amount', { values: { name: credits.name } })}
              {disabled}
              value={amount}
              oninput={onAmountInput} />
            <span class="input-group-text">{credits.name}</span>
            <button
              type="button"
              class="btn btn-outline-secondary"
              {disabled}
              onclick={() => onchange({ useCredits: 'MAX' })}>
              {$_('theme.checkout.credits-use-max')}
            </button>
          </div>

          {#if Number(credits.applied) > 0}
            <div class="d-flex justify-content-between" aria-live="polite">
              <span>{$_('theme.checkout.credits-applied')}</span>
              <span class="fw-semibold">
                {MINUS}{formatMoney(credits.appliedValue, currency, { removeCents })}
              </span>
            </div>
          {/if}
          <div class="form-text">{$_('theme.checkout.credits-refund-note')}</div>
        {/if}
      </div>
    {/if}
  </div>
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import {
    creditsRadio,
    mixedActive,
    mixedControlsVisible,
    parseCreditAmount,
    PAY_GROUP,
    selectCreditsPatch,
  } from '../../lib/paymentModel.js';
  import { formatCredits, formatMoney } from '../../utils/format.js';

  /**
   * `credits` = quote.credits; `config` = checkout config (mixedCredit); `payWithCredits` / `useCredits` = the
   * draft's credit choice (never pre-applied: the box starts unticked); `method` = the selected payment method
   * option. `alertKey` / `alertMax`: INSUFFICIENT_CREDITS from the submit with the most that can be applied.
   * onchange(patch of the draft).
   */
  let {
    credits,
    config = {},
    payWithCredits = false,
    useCredits = null,
    method = null,
    currency = '',
    removeCents = false,
    disabled = false,
    alertKey = '',
    alertMax = null,
    onchange = () => {},
  } = $props();

  const MINUS = '\u2212';
  const payId = 'market-checkout-credits-pay';
  const useId = 'market-checkout-credits-use';
  const amountId = 'market-checkout-credits-amount';

  const radio = $derived(creditsRadio(credits));
  const mixed = $derived(mixedControlsVisible({ config, credits, payWithCredits, method }));
  const active = $derived(mixedActive({ useCredits }));
  const amount = $derived(typeof useCredits === 'number' ? useCredits : credits.maxApplicable);

  function onAmountInput(event) {
    const parsed = parseCreditAmount(event.currentTarget.value);

    if (parsed.ok) onchange({ useCredits: parsed.value });
  }
</script>
