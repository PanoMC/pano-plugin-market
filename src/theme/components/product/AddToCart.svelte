<div class="market-add-to-cart vstack gap-2">
  {#if buy.kind === 'LOGIN'}
    <a
      class="market-add-to-cart__action btn btn-primary"
      href={loginUrl(`/store/${encodeURIComponent(slug)}`)}>
      <i class="fa-solid fa-right-to-bracket me-2" aria-hidden="true"></i>{$_(
        'theme.product.sign-in-to-buy',
      )}
    </a>
  {:else if buy.kind === 'SOLD_OUT'}
    <button type="button" class="market-add-to-cart__sold-out btn btn-secondary" disabled
      >{$_('theme.store.sold-out')}</button>
  {:else}
    <div class="d-flex flex-wrap gap-2">
      {#if buy.kind === 'SUBSCRIBE'}
        <button
          type="button"
          class="market-add-to-cart__subscribe btn btn-primary"
          {disabled}
          onclick={() => onsubscribe()}>
          {#if pending}<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span
            >{:else}<i class="fa-solid fa-rotate me-2" aria-hidden="true"></i>{/if}{$_(
            'theme.product.subscribe',
          )}
        </button>
      {:else}
        <button
          type="button"
          class="market-add-to-cart__add-to-cart btn btn-primary"
          {disabled}
          onclick={() => onadd()}>
          {#if pending}<span class="spinner-border spinner-border-sm me-2" aria-hidden="true"></span
            >{:else}<i class="fa-solid fa-cart-plus me-2" aria-hidden="true"></i>{/if}{$_(
            'theme.store.add-to-cart',
          )}
        </button>
        <button
          type="button"
          class="market-add-to-cart__buy-now btn btn-outline-primary"
          {disabled}
          onclick={() => onbuy()}>
          {$_('theme.product.buy-now')}
        </button>
      {/if}
    </div>
  {/if}

  {#if buy.kind === 'BLOCKED'}
    <div class="text-danger small" role="alert">{$_(`theme.errors.${buy.reason}`)}</div>
  {:else if buy.kind !== 'LOGIN' && buy.kind !== 'SOLD_OUT' && missing}
    <div class="text-danger small">{$_('theme.errors.VARIANT_REQUIRED')}</div>
  {/if}
</div>

<script>
  import { plugin } from '@panomc/sdk/controllers';

  const market = plugin('market');
  const _ = market._;
  const { loginUrl } = market.require('host').actions;

  /**
   * Buy buttons (14 §9.2). `buy` = productModel.buyState(); `missing` = no resolved variant; `pending` while the
   * cart call runs. The page validates and calls the cart; this component only renders.
   */
  let {
    buy,
    slug,
    missing = false,
    pending = false,
    onadd = () => {},
    onbuy = () => {},
    onsubscribe = () => {},
  } = $props();

  const disabled = $derived(pending || missing || buy.kind === 'BLOCKED');
</script>
