<!-- A product name in a table cell: one line, truncated (full name in the title), and a link to the
     product's edit page when the viewer may open the catalog. Without CAT or an id it stays text. -->
{#if href}
  <a
    class="rounded focus-ring text-decoration-none d-block text-truncate"
    style="max-width: {maxWidth};"
    title={text}
    href="{base}{href}">{text}</a>
{:else}
  <div class="text-truncate" style="max-width: {maxWidth};" title={text}>{text}</div>
{/if}

<script>
  import { base, page } from '@panomc/sdk/svelte';
  import { productHref } from '../utils/product-link.js';

  let { productId = null, name = '', maxWidth = '240px' } = $props();

  const user = $derived($page.data?.user);
  const text = $derived(name || '—');
  const href = $derived(name ? productHref(productId, user) : null);
</script>
