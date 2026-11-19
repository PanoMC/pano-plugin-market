{#snippet body()}
  <div>{$_(`pages.order-detail.cards.${billing ? 'billing' : 'shipping-address'}`)}</div>
  <address class="mb-0">
    {#each lines as line, index (index)}
      <div>{line}</div>
    {/each}
  </address>
  {#if extra.length > 0}
    <dl class="row mb-0">
      {#each extra as entry (entry.key)}
        <dt class="col-5 text-body-secondary fw-normal">
          {$_(`pages.order-detail.address.${entry.key}`)}
        </dt>
        <dd class="col-7 mb-0">{entry.value}</dd>
      {/each}
    </dl>
  {/if}
{/snippet}

{#if address && lines.length + extra.length > 0}
  {#if embedded}
    <!-- a section of the Customer card: a rule above it instead of a card of its own -->
    <div class="vstack gap-3 border-top pt-3">
      {@render body()}
    </div>
  {:else}
    <div class="card">
      <div class="card-body vstack gap-3">
        {@render body()}
      </div>
    </div>
  {/if}
{/if}

<script>
  import { _ } from '../../../i18n';
  import { addressLines } from './model.js';

  // address: order.billingInfo or order.shippingAddress (null / absent without OM / PAY: nothing is rendered).
  // embedded: rendered inside another card's body (the Customer card) instead of as a card.
  let { address = null, billing = false, embedded = false } = $props();

  const parts = $derived(addressLines(address, { billing }));
  const lines = $derived(parts.lines);
  const extra = $derived(parts.extra);
</script>
