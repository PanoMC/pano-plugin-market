{#if value !== null && value !== undefined && value !== ''}
  <span class="badge {cls}">{known ? $_(`enums.${kind}.${value}`) : value}</span>
{/if}

<script>
  import { _ } from '../../i18n';
  import { badgeClass, isKnownStatus } from '../utils/status.js';

  let { kind, value } = $props();

  const known = $derived(isKnownStatus(kind, value));
  const cls = $derived(badgeClass(kind, value));
</script>
