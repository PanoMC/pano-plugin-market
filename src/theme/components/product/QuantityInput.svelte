<div class="market-quantity-input">
  <label class="market-quantity-input__label form-label" for="mp-quantity"
    >{$_('theme.product.quantity')}</label>
  <div class="input-group w-auto">
    <button
      type="button"
      class="market-quantity-input__action btn btn-outline-secondary"
      aria-label={$_('theme.product.quantity-decrease')}
      disabled={value <= 1}
      onclick={() => onchange(clampQuantity(value - 1, max))}>
      <i class="fa-solid fa-minus" aria-hidden="true"></i>
    </button>
    <input
      id="mp-quantity"
      type="number"
      class="market-quantity-input__input form-control text-center"
      min="1"
      {max}
      step="1"
      {value}
      oninput={(event) => {
        const n = Math.floor(Number(event.currentTarget.value));
        if (Number.isFinite(n) && n >= 1) onchange(Math.min(n, max));
      }}
      onblur={(event) => {
        const next = clampQuantity(event.currentTarget.value, max);
        event.currentTarget.value = String(next);
        onchange(next);
      }} />
    <button
      type="button"
      class="market-quantity-input__quantity-increase btn btn-outline-secondary"
      aria-label={$_('theme.product.quantity-increase')}
      disabled={value >= max}
      onclick={() => onchange(clampQuantity(value + 1, max))}>
      <i class="fa-solid fa-plus" aria-hidden="true"></i>
    </button>
  </div>
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { clampQuantity } from './productModel.js';

  const market = plugin('market');
  const _ = market._;

  /** Quantity of a one-time product: the page decides visibility and passes `max` (productModel.quantityMax). */
  let { value = 1, max = 99, onchange = () => {} } = $props();
</script>
