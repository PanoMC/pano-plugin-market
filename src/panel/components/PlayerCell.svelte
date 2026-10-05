{#snippet content()}
  <img class="rounded me-2" src={picture} width="24" height="24" loading="lazy" alt="" />
  <span>{username}</span>
{/snippet}

{#if link && username}
  <a
    class="d-inline-flex align-items-center text-decoration-none"
    href="{base}/players/detail/{encodeURIComponent(username)}">
    {@render content()}
  </a>
{:else}
  <span class="d-inline-flex align-items-center">{@render content()}</span>
{/if}

<script>
  import { base } from '@panomc/sdk/svelte';

  let { username, link = true } = $props();

  const picture = $derived(`/api/profile/picture/${encodeURIComponent(username ?? '')}`);
</script>
