{#if visible}
  <div class="card">
    <CardHeader>
      <div slot="left">{$_('pages.order-detail.cards.invoices')}</div>
      <div slot="right">
        <CardMenu items={menu} />
      </div>
    </CardHeader>
    {#if invoices.length === 0}
      <NoContent icon="" />
    {:else}
      <ul class="list-group list-group-flush">
        {#each invoices as invoice (invoice.id)}
          <li class="list-group-item d-flex justify-content-between align-items-center gap-2">
            {#if canSeeInvoices(user)}
              <a href={invoiceUrl(base, order.id, invoice)} target="_blank" rel="noopener">
                {invoice.number}
              </a>
            {:else}
              <span>{invoice.number}</span>
            {/if}
            <span class="text-body-secondary">
              {isKnownInvoiceType(invoice.type)
                ? $_(`pages.order-detail.invoice-type.${invoice.type}`)
                : invoice.type}
            </span>
          </li>
        {/each}
      </ul>
    {/if}
  </div>
{/if}

<script>
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { _ } from '../../../i18n';
  import { canSeeInvoices, showInvoicesCard } from './actions.js';
  import CardMenu from './CardMenu.svelte';
  import { invoiceUrl, isKnownInvoiceType } from './model.js';
  import { can } from '../../utils/permissions.js';

  // onRegenerate(): the page asks for a confirmation and sends POST /orders/:id/invoice/regenerate.
  let { detail, ctx = null, user = null, onRegenerate = () => {} } = $props();

  const order = $derived(detail?.order);
  const invoices = $derived(detail?.invoices ?? []);
  const visible = $derived(showInvoicesCard(detail, ctx, user));
  const menu = $derived(
    can(user, 'OM')
      ? [
          {
            key: 'regenerate',
            label: $_('pages.order-detail.actions.regenerate'),
            icon: 'fa-file-invoice',
            onclick: onRegenerate,
          },
        ]
      : [],
  );
</script>
