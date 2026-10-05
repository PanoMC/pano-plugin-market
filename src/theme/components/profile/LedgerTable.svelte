<div class="table-responsive">
  <table class="table align-middle">
    <caption class="visually-hidden">{$_('theme.profile.credits.ledger-title')}</caption>
    <thead>
      <tr>
        <th scope="col">{$_('theme.profile.credits.col-date')}</th>
        <th scope="col">{$_('theme.profile.credits.col-type')}</th>
        <th scope="col" class="text-end">{$_('theme.profile.credits.col-amount')}</th>
        <th scope="col" class="text-end">{$_('theme.profile.credits.col-balance')}</th>
        <th scope="col">{$_('theme.profile.credits.col-note')}</th>
      </tr>
    </thead>
    <tbody>
      {#each rows as row, index (row.id ?? index)}
        <tr>
          <td class="text-nowrap">{formatDateTime(row.createdAt)}</td>
          <td>{row.type.key ? $_(row.type.key) : row.type.raw}</td>
          <td class={['text-end', 'text-nowrap', row.amount.className]}>
            {row.amount.sign}{formatCredits(row.amount.value, creditName)}
          </td>
          <td class="text-end text-nowrap">{formatCredits(row.balanceAfter, creditName)}</td>
          <td>
            {#if row.note}<span>{row.note}</span>{/if}
            {#if row.orderHref}
              <a href="{base}{row.orderHref}" class={{ 'ms-2': !!row.note }}>
                <i class="fa-solid fa-receipt me-1" aria-hidden="true"></i>{$_(
                  'theme.profile.credits.order-link',
                )}
              </a>
            {/if}
          </td>
        </tr>
      {/each}
    </tbody>
  </table>
</div>

<script>
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n.js';
  import { ledgerRow } from '../../lib/profileModel.js';
  import { formatCredits, formatDateTime } from '../../utils/format.js';

  /** entries: rows of GET me/credits; creditName: unit text. Empty lists are the caller's NoContent. */
  let { entries = [], creditName = '' } = $props();

  const rows = $derived(entries.map(ledgerRow));
</script>
