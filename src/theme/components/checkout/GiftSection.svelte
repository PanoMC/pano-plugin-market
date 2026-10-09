<div class="market-gift-section card">
  <div class="market-gift-section__body card-body">
    <h2 class="market-gift-section__title h5">{$_('theme.checkout.gift')}</h2>

    <div class="form-check form-switch">
      <input
        id={switchId}
        class="market-gift-section__check form-check-input"
        type="checkbox"
        role="switch"
        checked={isGift}
        aria-invalid={lineNames.length ? 'true' : undefined}
        aria-describedby={lineNames.length ? `${switchId}-error` : undefined}
        onchange={(event) => onchange({ isGift: event.currentTarget.checked })} />
      <label class="form-check-label" for={switchId}>{$_('theme.checkout.gift-switch')}</label>
    </div>

    {#if lineNames.length}
      <div class="form-text text-danger" id="{switchId}-error" role="alert">
        {$_('theme.errors.GIFT_NOT_ALLOWED')}
        {lineNames.join(', ')}
      </div>
    {/if}

    {#if isGift}
      <div class="vstack gap-3 mt-3">
        <div>
          <label class="market-gift-section__label form-label" for={recipientId}>
            {$_('theme.checkout.gift-recipient')}
            <span class="text-danger" aria-hidden="true">*</span>
          </label>
          <input
            id={recipientId}
            class={['market-gift-section__input', 'form-control', errors.recipient && 'is-invalid']}
            type="text"
            maxlength="32"
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
            required
            aria-required="true"
            aria-invalid={errors.recipient ? 'true' : undefined}
            aria-describedby={describedBy}
            value={recipient}
            oninput={(event) => onchange({ recipientUsername: event.currentTarget.value })}
            onblur={() => onblur('recipient')} />
          {#if errors.recipient}
            <div class="invalid-feedback" id="{recipientId}-error">
              {$_(fieldErrorKey('recipient', errors.recipient))}
            </div>
          {/if}
          {#if recipientUnknown && !errors.recipient}
            <div class="form-text text-warning-emphasis" id="{recipientId}-unknown" role="status">
              {$_('theme.checkout.recipient-unknown')}
            </div>
          {/if}
        </div>

        <div>
          <label class="market-gift-section__gift-message form-label" for={messageId}
            >{$_('theme.checkout.gift-message')}</label>
          <textarea
            id={messageId}
            class={['market-gift-section__input-2', 'form-control', errors.message && 'is-invalid']}
            rows="3"
            maxlength={GIFT_MESSAGE_MAX}
            aria-invalid={errors.message ? 'true' : undefined}
            aria-describedby="{messageId}-counter"
            value={message}
            oninput={(event) => onchange({ giftMessage: event.currentTarget.value })}
            onblur={() => onblur('message')}></textarea>
          <div class="form-text text-end" id="{messageId}-counter">
            {message.length} / {GIFT_MESSAGE_MAX}
          </div>
          {#if errors.message}
            <div class="invalid-feedback d-block">
              {$_(fieldErrorKey('message', errors.message))}
            </div>
          {/if}
        </div>
      </div>
    {/if}
  </div>
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { fieldErrorKey, fieldId } from '../../lib/checkoutModel.js';
  import { GIFT_MESSAGE_MAX } from '../../lib/validation.js';

  const market = plugin('market');
  const { _ } = market;

  /**
   * isGift / recipient / message: the draft values; errors: {recipient?, message?} message codes;
   * lineNames: names of the lines with the line error GIFT_NOT_ALLOWED; recipientUnknown: quote message
   * RECIPIENT_UNKNOWN (a warning, does not block). onchange(patch of the draft); onblur(field).
   */
  let {
    isGift = false,
    recipient = '',
    message = '',
    errors = {},
    lineNames = [],
    recipientUnknown = false,
    onchange = () => {},
    onblur = () => {},
  } = $props();

  const switchId = 'market-checkout-gift-switch';
  const recipientId = fieldId('gift', 'recipient');
  const messageId = fieldId('gift', 'message');

  const describedBy = $derived(
    errors.recipient
      ? `${recipientId}-error`
      : recipientUnknown
        ? `${recipientId}-unknown`
        : undefined,
  );
</script>
