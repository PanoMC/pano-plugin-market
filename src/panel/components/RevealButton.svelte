<button
  class="btn btn-link btn-sm"
  type="button"
  title={label}
  aria-label={label}
  aria-pressed={revealed}
  onclick={() => (revealed = !revealed)}>
  <i class="fa-solid {revealed ? 'fa-eye-slash' : 'fa-eye'}" aria-hidden="true"></i>
</button>

<script>
  import { _ } from '../../i18n';

  // Shows or hides a masked value next to it (the value itself is rendered by the parent).
  let { revealed = $bindable(false) } = $props();

  const label = $derived(revealed ? $_('common.hide') : $_('common.show'));
</script>
