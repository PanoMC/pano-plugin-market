{#if mails.length > 0}
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.order-detail.cards.mails', { values: { count: mails.length } })}
      </div>
    </CardHeader>
    <div class="table-responsive">
      <table class="table table-hover">
        <thead>
          <tr>
            <th class="align-middle text-nowrap" scope="col"></th>
            <th class="align-middle text-nowrap" scope="col"
              >{$_('pages.order-detail.table.kind')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.recipient')}
            </th>
            <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.attempts')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.last-error')}
            </th>
            <th class="align-middle text-nowrap" scope="col"
              >{$_('pages.order-detail.table.sent')}</th>
          </tr>
        </thead>
        <tbody>
          {#each mails as mail (mail.id)}
            <tr>
              <th scope="row" class="align-middle">
                <ControlDropdown items={menu(mail)} />
              </th>
              <td class="align-middle">
                {isKnownMailKind(mail.kind) ? $_(`enums.mail-kind.${mail.kind}`) : mail.kind}
              </td>
              <td class="align-middle">{mail.recipient ?? '—'}</td>
              <td class="align-middle"><StatusBadge kind="mail" value={mail.status} /></td>
              <td class="align-middle">{mail.attempts ?? 0}</td>
              <td class="align-middle">
                {#if mail.lastError}{mail.lastError}{:else}<span class="text-body-secondary">—</span
                  >{/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if mail.sentAt}<DateComponent time={mail.sentAt} />{:else}<span
                    class="text-body-secondary">—</span
                  >{/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  </div>
{/if}

<script>
  import { CardHeader, Date as DateComponent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import StatusBadge from '../StatusBadge.svelte';
  import { mailActions } from './actions.js';
  import ControlDropdown from './ControlDropdown.svelte';
  import { isKnownMailKind } from './model.js';

  // onRetry(mail): the page sends POST /mails/:id/retry.
  let { detail, user = null, onRetry = () => {} } = $props();

  const mails = $derived(detail?.mails ?? []);

  const menu = (mail) =>
    mailActions(mail, user).map(() => ({
      key: 'retry',
      label: $_('pages.order-detail.actions.retry'),
      icon: 'fa-rotate-right',
      onclick: () => onRetry(mail),
    }));
</script>
