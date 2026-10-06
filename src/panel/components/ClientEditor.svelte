{#if mounted}
  <Editor {...rest} bind:content />
{:else}
  <!-- the host editor builds its document while it is created, which needs `window`: it only exists in the browser -->
  <div class="form-control editor-placeholder" style="min-height: 12rem;" aria-hidden="true"></div>
{/if}

<script>
  import { Editor } from '@panomc/sdk/components/panel';
  import { onMount } from 'svelte';

  // The host's rich text editor throws while it renders on the server as soon as it holds content ("[tiptap error]: there is
  // no window object", a direct visit of a saved product or of the legal text answered 500). Every market page therefore
  // mounts it through this wrapper: the server renders a placeholder, the browser the editor.
  let { content = $bindable(), ...rest } = $props();

  let mounted = $state(false);

  onMount(() => {
    mounted = true;
  });
</script>
