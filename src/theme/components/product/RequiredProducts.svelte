{#if products.length}
  <section>
    <h2 class="h5">
      {requireOnlyOne ? $_('theme.product.requires-one') : $_('theme.product.requires-all')}
    </h2>
    <ul class="list-group">
      {#each products as required (required.id)}
        <li class="list-group-item d-flex align-items-center gap-2">
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
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';

  /** ProductDetail.requiredProducts[] with the "any one" / "all" heading. */
  let { products = [], requireOnlyOne = false } = $props();
</script>
