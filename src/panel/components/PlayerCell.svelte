{#snippet content()}
  <img
    class="rounded me-2 flex-shrink-0"
    src={picture}
    width="24"
    height="24"
    loading="lazy"
    alt="" />
  <span class="text-truncate">{username}</span>
{/snippet}

<!-- one line in a narrow table cell: the name truncates (full name in the title), the avatar stays -->
{#if link && username}
  <a
    class="rounded focus-ring d-inline-flex align-items-center text-decoration-none text-nowrap mw-100"
    title={username}
    href="{base}/players/detail/{encodeURIComponent(username)}">
    {@render content()}
  </a>
{:else}
  <span class="d-inline-flex align-items-center text-nowrap mw-100" title={username}
    >{@render content()}</span>
{/if}

<script>
  import { base } from '@panomc/sdk/svelte';

  let { username, link = true } = $props();

  const picture = $derived(`/api/v1/profile/picture/${encodeURIComponent(username ?? '')}`);
</script>
