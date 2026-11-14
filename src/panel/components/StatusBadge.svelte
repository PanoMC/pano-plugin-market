{#if value !== null && value !== undefined && value !== ''}
  <span class="badge {cls}">{known ? $_(`enums.${kind}.${value}`) : value}</span>
{/if}

<script>
  import { _ } from '../../i18n';
  import { badgeClass, isKnownStatus } from '../utils/status.js';

  let { kind, value, secondaryClass = 'text-bg-secondary' } = $props();

  const known = $derived(isKnownStatus(kind, value));
  const statusClass = $derived(badgeClass(kind, value));
  const cls = $derived(statusClass === 'text-bg-secondary' ? secondaryClass : statusClass);
</script>
