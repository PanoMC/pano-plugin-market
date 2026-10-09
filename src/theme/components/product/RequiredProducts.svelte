{#if products.length}
  <section class="market-required-products">
    <h2 class="market-required-products__title h5">
      {requireOnlyOne ? $_('theme.product.requires-one') : $_('theme.product.requires-all')}
    </h2>
    <ul class="market-required-products__list list-group">
      {#each products as required (required.id)}
        <li class="market-required-products__item list-group-item d-flex align-items-center gap-2">
          {#if required.owned}
            <i class="fa-solid fa-circle-check text-success" aria-hidden="true"></i>
            <span class="visually-hidden">{$_('theme.store.owned')}</span>
          {:else}
            <i class="fa-regular fa-circle text-body-secondary" aria-hidden="true"></i>
          {/if}
          <a href="{base}/store/{encodeURIComponent(required.slug)}">{required.name}</a>
        </li>
      {/each}
    </ul>
  </section>
{/if}

<script>
  import { plugin } from '@panomc/sdk/controllers';
  import { base } from '@panomc/sdk/svelte';

  const market = plugin('market');
  const _ = market._;

  /** ProductDetail.requiredProducts[] with the "any one" / "all" heading. */
  let { products = [], requireOnlyOne = false } = $props();
</script>
