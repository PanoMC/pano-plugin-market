<section class="market-top-up-card vstack gap-3" aria-labelledby="market-topup-title">
  <h2 class="market-top-up-card__title h5 mb-0" id="market-topup-title">
    <i class="fa-solid fa-circle-plus me-2" aria-hidden="true"></i>{$_(
      'theme.profile.credits.topup-title',
    )}
  </h2>

  {#if packs.length}
    <div class="vstack gap-2">
      <h3 class="market-top-up-card__packs-title h6 mb-0">
        {$_('theme.profile.credits.packs-title')}
      </h3>
      <div class="row g-3">
        {#each packs as product (product.id)}
          <div class="col-6 col-md-4">
            <ProductCard {product} {settings} />
          </div>
        {/each}
      </div>
    </div>
  {/if}

  {#if topUp?.freeAmount}
    <form class="card" novalidate onsubmit={submit} aria-labelledby="market-topup-free-title">
      <div class="market-top-up-card__body card-body vstack gap-2">
        <h3 class="market-top-up-card__topup-free-title h6 mb-0" id="market-topup-free-title">
          {$_('theme.profile.credits.topup-free-title')}
        </h3>
        <label class="market-top-up-card__label form-label mb-0" for="market-topup-amount">
          {$_('theme.profile.credits.topup-amount-label', { values: { creditName } })}
        </label>
        <div class="input-group">
          <input
            id="market-topup-amount"
            class={['market-top-up-card__input', 'form-control', { 'is-invalid': !!errorKey }]}
            type="number"
            inputmode="decimal"
            min={topUp.min}
            max={Number.isFinite(topUp.max) ? topUp.max : undefined}
            step="0.01"
            autocomplete="off"
            aria-invalid={errorKey ? 'true' : undefined}
            aria-describedby={errorKey ? 'market-topup-feedback' : 'market-topup-cost'}
            bind:value={text}
            bind:this={input}
            oninput={() => (touched = true)} />
          <button type="submit" class="market-top-up-card__action btn btn-primary">
            {$_('theme.profile.credits.topup-submit')}
          </button>
          {#if errorKey}
            <div class="invalid-feedback" id="market-topup-feedback">
              {$_(errorKey, { values: { min: minText, max: maxText } })}
            </div>
          {/if}
        </div>
        <div class="small text-body-secondary" id="market-topup-cost" aria-live="polite">
          {#if check.ok && costCents > 0}
            {$_('theme.profile.credits.topup-cost', {
              values: { cost: formatPrice(costCents / 100, costSettings) },
            })}
          {/if}
        </div>
      </div>
    </form>
  {/if}
</section>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { goto } from '@panomc/sdk/svelte';
  import {
    topUpCostCents,
    topUpErrorKey,
    topUpHref,
    validateTopUp,
  } from '../../lib/profileModel.js';
  import ProductCard from '../store/ProductCard.svelte';

  const market = plugin('market');
  const { _ } = market;
  const { formatCredits, formatPrice } = market.require('format').actions;

  /**
   * Credit top-up (14 §12.3): credit packs as product cards and, with `topUp.freeAmount`, a free-amount form whose
   * limits come from checkout/config (`topUp` = readTopUp(config)). The server validates again at checkout.
   */
  let { topUp = null, packs = [], settings = {}, creditName = '' } = $props();

  let text = $state('');
  let touched = $state(false);
  let submitted = $state(false);
  let input = $state();

  // `bind:value` on a number input gives a number (or null while the field is empty or invalid)
  const raw = $derived(text === null || text === undefined ? '' : String(text));
  const check = $derived(validateTopUp(raw, topUp));
  const errorKey = $derived(
    !check.ok && (submitted || (touched && raw !== '')) ? topUpErrorKey(check.reason) : '',
  );
  const costCents = $derived(check.ok ? topUpCostCents(check.cents, topUp?.creditValue ?? 0) : 0);
  const costSettings = $derived({
    ...settings,
    currency: topUp?.currency || settings?.currency,
  });
  const minText = $derived(formatCredits(topUp?.min ?? 0, creditName));
  const maxText = $derived(Number.isFinite(topUp?.max) ? formatCredits(topUp.max, creditName) : '');

  function submit(event) {
    event.preventDefault();
    submitted = true;

    if (!check.ok) {
      input?.focus();
      return;
    }

    Promise.resolve(goto(topUpHref(check.cents))).catch(() => {
      // navigation failed: the form stays as it is
    });
  }
</script>
