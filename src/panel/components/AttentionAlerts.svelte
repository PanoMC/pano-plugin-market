{#each alerts as alert (alert.key)}
  <div class="alert alert-{alert.variant} d-flex align-items-start" role="alert">
    <i class="fa-solid {alert.icon} me-3 mt-1" aria-hidden="true"></i>
    <div>
      <b>{$_(`alerts.${alert.title}`)}</b>
      <div>
        {$_(`alerts.${alert.body}`, {
          values: { count: alert.count ?? 0, providers: alert.providers?.join(', ') ?? '' },
        })}
      </div>

      {#if alert.missing?.length}
        <ul class="mb-2">
          {#each alert.missing as name (name)}
            <li>{name}</li>
          {/each}
          {#if alert.more > 0}
            <li>{$_('alerts.more', { values: { count: alert.more } })}</li>
          {/if}
        </ul>
      {/if}

      {#if alert.items?.length}
        <ul class="mb-2">
          {#each alert.items as item (item.id)}
            <li>
              <b>{item.name}</b>
              {$_(`alerts.mc-plugin.${item.kind}`, {
                values: { have: item.have ?? '', want: item.want ?? '' },
              })}
              {#if item.count > 0}
                {$_('alerts.mc-plugin.waiting', { values: { count: item.count } })}
              {/if}
              {#if item.downloadUrl}
                <a
                  class="alert-link ms-1"
                  href={item.downloadUrl}
                  target="_blank"
                  rel="noopener noreferrer">
                  {$_('alerts.mc-plugin.download')}
                </a>
              {/if}
            </li>
          {/each}
        </ul>
      {/if}

      {#each alert.links as item (item.href)}
        <a class="alert-link me-3" href="{base}{item.href}">{$_(`alerts.actions.${item.label}`)}</a>
      {/each}
    </div>
  </div>
{/each}

<script>
  import { base, page } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n';
  import { can } from '../utils/permissions.js';
  import { alertsFor } from './overview/alerts.js';

  // 13 §4.2. Overview passes everything; Orders and Deliveries pass only `ctx` and `servers`.
  let { ctx = null, servers = [], health = null, reviewCount = 0, orders = [] } = $props();

  const user = $derived($page.data?.user);
  const alerts = $derived(
    alertsFor(health, servers, ctx, {
      reviewCount,
      orders,
      can: (...keys) => can(user, ...keys),
    }),
  );
</script>
