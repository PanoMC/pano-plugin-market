<div class="card" id="deliveries">
  <CardHeader>
    <div slot="left">
      {$_('pages.order-detail.cards.deliveries', { values: { count: deliveries.length } })}
    </div>
    <div slot="right">
      <CardMenu items={cardMenu} />
    </div>
  </CardHeader>

  {#if deliveries.length === 0}
    <NoContent icon="" />
  {:else}
    <div class="table-responsive">
      <table class="table table-hover">
        <thead>
          <tr>
            <th class="align-middle text-nowrap" scope="col"></th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.product')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.action')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.server')}
            </th>
            <th class="align-middle text-nowrap" scope="col">{$_('common.status')}</th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.attempts')}
            </th>
            <th class="align-middle text-nowrap" scope="col">
              {$_('pages.order-detail.table.last-error')}
            </th>
            <th class="align-middle text-nowrap" scope="col"
              >{$_('pages.order-detail.table.when')}</th>
          </tr>
        </thead>
        <tbody>
          {#each deliveries as delivery (delivery.id)}
            {@const when = delivery.confirmedAt ?? delivery.sentAt ?? delivery.runAfter}
            <tr>
              <th scope="row" class="align-middle">
                <ControlDropdown items={menu(delivery)} />
              </th>
              <td class="align-middle">{delivery.productName ?? '—'}</td>
              <td class="align-middle text-nowrap">
                {#if isKnownActionType(delivery.actionType)}
                  {$_(`enums.action-type.${delivery.actionType}`)}
                {:else}
                  {delivery.actionType ?? '—'}
                {/if}
                {#if delivery.phase && delivery.phase !== 'GRANT'}
                  <span class="badge text-bg-secondary ms-1">
                    {isKnownPhase(delivery.phase)
                      ? $_(`enums.phase.${delivery.phase}`)
                      : delivery.phase}
                  </span>
                {/if}
              </td>
              <td class="align-middle">{delivery.serverName ?? '—'}</td>
              <td class="align-middle">
                <StatusBadge kind="delivery" value={delivery.status} />
                {#if delivery.cancelRequested}
                  <span class="badge text-bg-secondary ms-1">
                    {$_('pages.order-detail.cancel-requested')}
                  </span>
                {/if}
                {#if delivery.requiresOnline}
                  <i
                    class="fa-solid fa-user-clock ms-1 text-body-secondary"
                    role="img"
                    aria-label={$_('pages.order-detail.needs-online')}
                    use:tooltip={[$_('pages.order-detail.needs-online')]}></i>
                {/if}
                {#if mayHaveRun(delivery)}
                  <div class="small text-danger">{$_('pages.order-detail.may-have-run')}</div>
                {/if}
              </td>
              <td class="align-middle">{delivery.attempts ?? 0}</td>
              <td class="align-middle">
                {#if delivery.lastErrorCode}
                  <span use:tooltip={delivery.lastError ? [delivery.lastError] : undefined}>
                    {isKnownDeliveryError(delivery.lastErrorCode)
                      ? $_(`enums.delivery-error.${delivery.lastErrorCode}`)
                      : delivery.lastErrorCode}
                  </span>
                {:else}
                  <span class="text-body-secondary">—</span>
                {/if}
              </td>
              <td class="align-middle text-nowrap">
                {#if when}
                  {#if delivery.runAfter && delivery.runAfter > now && !delivery.confirmedAt && !delivery.sentAt}
                    <span class="text-body-secondary">
                      {$_('pages.order-detail.scheduled-for')}
                    </span>
                  {/if}
                  <DateComponent time={when} />
                {:else}
                  <span class="text-body-secondary">—</span>
                {/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
  {/if}
</div>

<script>
  import { CardHeader, Date as DateComponent, NoContent } from '@panomc/sdk/components/panel';
  import { tooltip } from '@panomc/sdk/utils/tooltip';
  import { _ } from '../../../i18n';
  import StatusBadge from '../StatusBadge.svelte';
  import { deliveryActions } from './actions.js';
  import CardMenu from './CardMenu.svelte';
  import ControlDropdown from './ControlDropdown.svelte';
  import { isKnownActionType, isKnownDeliveryError, isKnownPhase, mayHaveRun } from './model.js';
  import { can } from '../../utils/permissions.js';

  // onRetry(delivery) / onCancel(delivery) ask for a confirmation; onRerunAll() opens the re-run modal;
  // onRevokeAll() asks for a confirmation.
  let {
    detail,
    user = null,
    onRetry = () => {},
    onCancel = () => {},
    onRerunAll = () => {},
    onRevokeAll = () => {},
  } = $props();

  // Clock for "scheduled for": read when the page renders, never during SSR-sensitive logic.
  const now = Date.now();
  const deliveries = $derived(detail?.deliveries ?? []);

  const cardMenu = $derived(
    can(user, 'OM')
      ? [
          ...(detail?.allowed?.rerunDelivery === true
            ? [
                {
                  key: 'rerun-all',
                  label: $_('pages.order-detail.actions.rerun-all'),
                  icon: 'fa-rotate-right',
                  onclick: onRerunAll,
                },
              ]
            : []),
          ...(detail?.allowed?.revoke === true
            ? [
                {
                  key: 'revoke-all',
                  label: $_('pages.order-detail.actions.revoke-all'),
                  icon: 'fa-user-slash',
                  danger: true,
                  onclick: onRevokeAll,
                },
              ]
            : []),
        ]
      : [],
  );

  function menu(delivery) {
    return deliveryActions(delivery, user).map((id) =>
      id === 'cancel'
        ? {
            key: id,
            label: $_('pages.order-detail.actions.cancel-row'),
            icon: 'fa-ban',
            danger: true,
            onclick: () => onCancel(delivery),
          }
        : {
            key: id,
            label: $_(
              id === 'offer-again'
                ? 'pages.order-detail.actions.offer-again'
                : 'pages.order-detail.actions.retry',
            ),
            icon: 'fa-rotate-right',
            onclick: () => onRetry(delivery, id),
          },
    );
  }
</script>
