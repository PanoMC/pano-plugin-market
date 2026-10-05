<button
  class="btn btn-link btn-sm"
  type="button"
  aria-label={title}
  use:tooltip={[title]}
  {onclick}>
  <i class="fa-solid {copied ? 'fa-check' : 'fa-copy'}"></i>
</button>

<script>
  import { copy } from '@panomc/sdk/utils/text';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _, showErrorToast } from '../../i18n';

  let { text, label = '' } = $props();

  let copied = $state(false);
  let timer = null;

  async function onclick() {
    try {
      const ok = await copy(text);
      if (ok === false) throw new Error('copy failed');
    } catch {
      showErrorToast($_('common.copy-failed'));
      return;
    }
    copied = true;
    clearTimeout(timer);
    timer = setTimeout(() => (copied = false), 1500);
  }

  $effect(() => () => clearTimeout(timer));

  const title = $derived(label || $_('common.copy'));
</script>
