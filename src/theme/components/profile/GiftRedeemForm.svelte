<form
  class="market-gift-redeem-form vstack gap-2"
  novalidate
  onsubmit={submit}
  aria-labelledby="market-gift-title">
  <h2 class="market-gift-redeem-form__title h5 mb-0" id="market-gift-title">
    <i class="fa-solid fa-gift me-2" aria-hidden="true"></i>{$_(
      'theme.profile.purchases.redeem-title',
    )}
  </h2>

  <label class="market-gift-redeem-form__label form-label visually-hidden" for="market-gift-code">
    {$_('theme.profile.purchases.redeem-label')}
  </label>
  <div class="input-group">
    <input
      id="market-gift-code"
      class={['market-gift-redeem-form__input', 'form-control', { 'is-invalid': !!invalidKey }]}
      type="text"
      maxlength={GIFT_CODE_MAX}
      autocomplete="off"
      autocapitalize="off"
      spellcheck="false"
      placeholder={$_('theme.profile.purchases.redeem-placeholder')}
      aria-invalid={invalidKey ? 'true' : undefined}
      aria-describedby={invalidKey ? 'market-gift-feedback' : undefined}
      disabled={busy || locked}
      bind:value={code}
      bind:this={input}
      oninput={() => (invalidKey = '')} />
    <button
      type="submit"
      class="market-gift-redeem-form__action btn btn-primary"
      disabled={busy || locked || !normalized}>
      {#if busy}
        <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
      {/if}
      {$_('theme.profile.purchases.redeem-submit')}
    </button>
    {#if invalidKey}
      <div class="invalid-feedback" id="market-gift-feedback">{$_(invalidKey)}</div>
    {/if}
  </div>

  {#if locked}
    <div class="small text-body-secondary" role="status">
      {$_('theme.profile.purchases.gift-locked', { values: { seconds: lockedLeft } })}
    </div>
  {/if}
  {#if alertKey}
    <div class="market-gift-redeem-form__alert alert alert-danger py-2 mb-0" role="alert">
      {$_(alertKey)}
    </div>
  {/if}
</form>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { goto } from '@panomc/sdk/svelte';
  import { GIFT_CODE_MAX, giftOutcome, normalizeGiftCode } from '../../lib/profileModel.js';

  const market = plugin('market');
  const { _ } = market;
  const { post } = market.require('api').actions;
  const clock = market.require('clock');

  /** Redeems a gift code (14 §12.2); on success it opens the zero-total order. */
  let code = $state('');
  let busy = $state(false);
  let invalidKey = $state('');
  let alertKey = $state('');
  let lockedUntil = $state(0);
  let input = $state();

  const normalized = $derived(normalizeGiftCode(code));
  const now = $derived(clock.state.now ?? 0);
  const lockedLeft = $derived(
    lockedUntil > 0 && now > 0 ? Math.max(0, Math.ceil((lockedUntil - now) / 1000)) : 0,
  );
  const locked = $derived(lockedUntil > 0 && (now === 0 || lockedLeft > 0));

  async function submit(event) {
    event.preventDefault();

    if (busy || locked || !normalized) return;

    busy = true;
    invalidKey = '';
    alertKey = '';

    const res = await post('/me/gifts/redeem', { body: { code: normalized } });
    const outcome = giftOutcome(res);

    if (outcome.kind === 'GOTO') {
      // keep the button busy while the order page loads
      Promise.resolve(goto(outcome.path)).catch(() => {
        busy = false;
      });

      return;
    }

    busy = false;

    if (outcome.kind === 'INVALID') {
      invalidKey = outcome.key;
      input?.focus();
    } else if (outcome.kind === 'LOCKED') {
      alertKey = outcome.key;
      lockedUntil = Date.now() + outcome.seconds * 1000;
    } else alertKey = outcome.key;
  }
</script>
