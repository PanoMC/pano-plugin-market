<li class="list-group-item px-0">
  <div class="row g-2 align-items-start">
    <div class="col-2">
      <div class="ratio ratio-1x1 bg-body-tertiary rounded overflow-hidden">
        {#if row.imageFileName}
          <img
            src="{base}/api/market/products/image/{row.imageFileName}?thumbnail=true"
            alt=""
            class="object-fit-cover rounded" />
        {:else}
          <div class="d-flex align-items-center justify-content-center">
            <i class="fa-solid fa-box text-body-secondary" aria-hidden="true"></i>
          </div>
        {/if}
      </div>
    </div>

    <div class="col-10">
      <div class="d-flex justify-content-between align-items-start gap-2">
        <div class="flex-grow-1">
          {#if row.slug}
            <a
              class="fw-semibold text-decoration-none d-block text-break"
              href="{base}/store/{row.slug}"
              onclick={onnavigate}>{row.name}</a>
          {:else}
            <span class="fw-semibold d-block text-break">{row.name}</span>
          {/if}
          {#if row.variantName}
            <div class="small text-body-secondary">{row.variantName}</div>
          {/if}
          {#each pairs as pair (pair.label)}
            <div class="small text-body-secondary text-break">{pair.label}: {pair.value}</div>
          {/each}
          {#if row.targetServerId}
            <div class="small text-body-secondary">
              {$_('theme.cart.server', { id: row.targetServerId })}
            </div>
          {/if}
        </div>
        <button
          type="button"
          class="btn btn-sm btn-link text-danger p-0"
          aria-label={$_('theme.cart.remove-item', { name: row.name })}
          onclick={() => cart.remove(row.key)}>
          <i class="fa-solid fa-trash" aria-hidden="true"></i>
        </button>
      </div>

      <div class="small mt-1">
        {#if isDiscounted(row)}
          <s class="text-body-secondary me-1">{money(row.listUnitPrice)}</s>
        {/if}
        <span>{money(row.unitPrice)}</span>
        {#if showCredit(row, creditsEnabled)}
          <span class="badge text-bg-info ms-1"
            >{formatCredits(row.creditUnitPrice, creditName)}</span>
        {/if}
      </div>

      <div class="d-flex justify-content-between align-items-center mt-2">
        {#if row.maxQuantity === 1}
          <span class="small text-body-secondary"
            >{$_('theme.cart.quantity-short', { count: row.quantity })}</span>
        {:else}
          <div class="btn-group btn-group-sm" role="group" aria-label={$_('theme.cart.quantity')}>
            <button
              type="button"
              class="btn btn-outline-secondary"
              aria-label={$_('theme.cart.decrease')}
              onclick={() => cart.setQuantity(row.key, row.quantity - 1)}>
              <i class="fa-solid fa-minus" aria-hidden="true"></i>
            </button>
            <span class="btn btn-outline-secondary disabled" aria-live="polite"
              >{row.quantity}</span>
            <button
              type="button"
              class="btn btn-outline-secondary"
              aria-label={$_('theme.cart.increase')}
              disabled={row.maxQuantity !== null && row.quantity >= row.maxQuantity}
              onclick={() => cart.setQuantity(row.key, row.quantity + 1)}>
              <i class="fa-solid fa-plus" aria-hidden="true"></i>
            </button>
          </div>
        {/if}
        <span class="fw-semibold">{money(row.lineTotal)}</span>
      </div>

      {#each row.errors as code (code)}
        <div class="small text-danger mt-1" role="alert">{$_(errorKey(code))}</div>
      {/each}
    </div>
  </div>
</li>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import { cart } from '../../stores/cart.js';
  import { formatCredits } from '../../utils/format.js';
  import { errorKey, fieldPairs, isDiscounted, showCredit } from './cartView.js';

  /**
   * row: a display row of cartView (quoteRows / metaRows); money: formats an amount in the cart currency;
   * onnavigate: called when the product link is followed (the offcanvas closes).
   */
  let { row, money, creditsEnabled = false, creditName = '', onnavigate = () => {} } = $props();

  const pairs = $derived(
    fieldPairs(row.fieldValues, {}, { true: $_('theme.common.yes'), false: $_('theme.common.no') }),
  );
</script>
