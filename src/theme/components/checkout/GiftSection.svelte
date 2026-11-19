<script module>
  export const view = { contract: 2 };
</script>

<div class="market-gift-section">
  {#if showSwitch || (showDetails && isGift)}
    <div class="market-gift-section__body vstack gap-3">
      {#if showSwitch}
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
          <label class="form-check-label" for={switchId}>
            {$_('theme.checkout.gift-switch')}
          </label>
        </div>

        {#if lineNames.length}
          <div class="form-text text-danger" id="{switchId}-error" role="alert">
            {$_('theme.errors.GIFT_NOT_ALLOWED')}
            {lineNames.join(', ')}
          </div>
        {/if}
      {/if}

      {#if showDetails && isGift}
        <div class="market-gift-section__fields vstack gap-3">
          <!-- One joined block: the recipient on top, the message below, a single line between them. -->
          <div class="market-gift-section__input-group vstack">
            <div class="form-floating">
              <input
                id={recipientId}
                class={[
                  'market-gift-section__input',
                  'form-control',
                  'rounded-bottom-0',
                  !errors.recipient && 'border-bottom-0',
                  errors.recipient && 'is-invalid',
                ]}
                type="text"
                maxlength="32"
                autocomplete="off"
                autocapitalize="off"
                spellcheck="false"
                placeholder=" "
                required
                aria-required="true"
                aria-invalid={errors.recipient ? 'true' : undefined}
                aria-describedby={describedBy}
                value={recipient}
                oninput={(event) => onchange({ recipientUsername: event.currentTarget.value })}
                onblur={() => onblur('recipient')} />
              <label class="market-gift-section__label text-capitalize" for={recipientId}>
                {$_('theme.checkout.gift-recipient')}
                <span class="text-danger" aria-hidden="true">*</span>
              </label>
            </div>
            <div class="form-floating">
              <textarea
                id={messageId}
                class={[
                  'market-gift-section__input-2',
                  'form-control',
                  'rounded-top-0',
                  errors.message && 'is-invalid',
                ]}
                rows="3"
                maxlength={GIFT_MESSAGE_MAX}
                placeholder=" "
                aria-invalid={errors.message ? 'true' : undefined}
                aria-describedby="{messageId}-counter"
                value={message}
                oninput={(event) => onchange({ giftMessage: event.currentTarget.value })}
                onblur={() => onblur('message')}></textarea>
              <label class="market-gift-section__gift-message text-capitalize" for={messageId}>
                {$_('theme.checkout.gift-message')}
              </label>
            </div>
          </div>
          {#if errors.recipient}
            <div class="invalid-feedback d-block" id="{recipientId}-error">
              {$_(fieldErrorKey('recipient', errors.recipient))}
            </div>
          {/if}
          {#if recipientUnknown && !errors.recipient}
            <div class="form-text text-warning-emphasis" id="{recipientId}-unknown" role="status">
              {$_('theme.checkout.recipient-unknown')}
            </div>
          {/if}
          <div class="form-text text-end" id="{messageId}-counter">
            {message.length} / {GIFT_MESSAGE_MAX}
          </div>
          {#if errors.message}
            <div class="invalid-feedback d-block">
              {$_(fieldErrorKey('message', errors.message))}
            </div>
          {/if}
        </div>
      {/if}
    </div>
  {/if}
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
    showSwitch = true,
    showDetails = true,
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

<style>
  .market-gift-section__input-group > .form-floating:focus-within {
    position: relative;
    z-index: 2;
  }

  .market-gift-section__input-group > .form-floating + .form-floating {
    margin-left: 0 !important;
    margin-top: calc(var(--pano-border-width) * -1);
  }

  .market-gift-section__input-group > .form-floating:first-child > .form-control {
    border-bottom-left-radius: 0;
    border-bottom-right-radius: 0;
  }

  .market-gift-section__input-group > .form-floating + .form-floating > .form-control {
    border-top-left-radius: 0;
    border-top-right-radius: 0;
  }
</style>
