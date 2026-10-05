{#if supported}
  <button
    type="button"
    class="btn btn-sm btn-outline-secondary"
    aria-label={label || $_('theme.common.copy')}
    onclick={copy}>
    <i class={copied ? 'fa-solid fa-check' : 'fa-regular fa-copy'} aria-hidden="true"></i>
  </button>
  <span class="visually-hidden" aria-live="polite">{copied ? $_('theme.common.copied') : ''}</span>
{/if}

<script>
  import { onMount } from 'svelte';
  import { _ } from '../../../i18n.js';

  let { text = '', label = '' } = $props();

  let supported = $state(false);
  let copied = $state(false);
  let timer;

  onMount(() => {
    supported = typeof navigator !== 'undefined' && !!navigator.clipboard?.writeText;

    return () => clearTimeout(timer);
  });

  async function copy() {
    try {
      await navigator.clipboard.writeText(String(text));
    } catch (e) {
      return;
    }

    copied = true;
    clearTimeout(timer);
    timer = setTimeout(() => (copied = false), 2000);
  }
</script>
