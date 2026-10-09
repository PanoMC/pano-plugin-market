<div class="modal fade" tabindex="-1" aria-hidden="true" bind:this={modalElement}>
  <div class="modal-dialog modal-dialog-centered modal-dialog-scrollable">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title">
          {goalId === null ? $_('modals.goal.title-create') : $_('modals.goal.title-edit')}
        </h5>
        <button
          type="button"
          class="btn-close"
          data-bs-dismiss="modal"
          aria-label={$_('common.close')}></button>
      </div>
      <form onsubmit={submit} novalidate>
        <div class="modal-body vstack gap-3">
          <div>
            <div class="form-floating">
              <input
                id="goal-name"
                type="text"
                class="form-control"
                class:is-invalid={shown.name}
                maxlength={NAME_MAX}
                placeholder={$_('modals.goal.name')}
                bind:value={form.name} />
              <label for="goal-name">{$_('modals.goal.name')}</label>
            </div>
            {#if shown.name}
              <div class="invalid-feedback d-block">
                {$_(`pages.goals.field-errors.${errors.name}`)}
              </div>
            {/if}
          </div>

          <div>
            <div class="form-floating">
              <textarea
                id="goal-description"
                class="form-control"
                class:is-invalid={shown.description}
                style="height: 80px"
                placeholder={$_('modals.goal.description')}
                bind:value={form.description}></textarea>
              <label for="goal-description">{$_('modals.goal.description')}</label>
            </div>
            {#if shown.description}
              <div class="invalid-feedback d-block">
                {$_(`pages.goals.field-errors.${errors.description}`)}
              </div>
            {/if}
          </div>

          <div class="form-floating">
            <select id="goal-metric" class="form-select" bind:value={form.metric}>
              {#each METRICS as value (value)}
                <option {value}>{$_(`enums.goal-metric.${value}`)}</option>
              {/each}
            </select>
            <label for="goal-metric">{$_('modals.goal.metric')}</label>
          </div>

          {#if form.metric === 'PRODUCT_SALES'}
            <div>
              <label class="form-label" for="goal-products">{$_('modals.goal.products')}</label>
              <div id="goal-products" class:border-danger={shown.productIds}>
                <ProductSelector bind:selected={form.productIds} multiple={true} maxHeight="180px" />
              </div>
              {#if shown.productIds}
                <div class="invalid-feedback d-block">
                  {$_(`pages.goals.field-errors.${errors.productIds}`)}
                </div>
              {/if}
            </div>
          {/if}

          <div>
            <label class="form-label" for="goal-target">{$_('modals.goal.target')}</label>
            {#if form.metric === 'REVENUE'}
              <MoneyInput
                id="goal-target"
                bind:value={form.target}
                currency={ctx?.currency ?? ''}
                invalid={shown.target === true}
                placeholder={$_('modals.goal.target')} />
            {:else}
              <input
                id="goal-target"
                type="number"
                min="1"
                step="1"
                class="form-control"
                class:is-invalid={shown.target}
                bind:value={form.target} />
            {/if}
            {#if shown.target}
              <div class="invalid-feedback d-block">
                {$_(`pages.goals.field-errors.${errors.target}`)}
              </div>
            {/if}
          </div>

          <div class="form-floating">
            <select id="goal-period" class="form-select" bind:value={form.period}>
              {#each PERIODS as value (value)}
                <option {value}>{$_(`enums.goal-period.${value}`)}</option>
              {/each}
            </select>
            <label for="goal-period">{$_('modals.goal.period')}</label>
          </div>

          <div class="row g-3">
            <div class="col-sm-6">
              <div class="form-floating">
                <input
                  id="goal-starts"
                  type="datetime-local"
                  class="form-control"
                  bind:value={startsText} />
                <label for="goal-starts">{$_('modals.goal.starts-at')}</label>
              </div>
            </div>
            <div class="col-sm-6">
              <div class="form-floating">
                <input
                  id="goal-ends"
                  type="datetime-local"
                  class="form-control"
                  class:is-invalid={shown.endsAt}
                  bind:value={endsText} />
                <label for="goal-ends">{$_('modals.goal.ends-at')}</label>
              </div>
              {#if shown.endsAt}
                <div class="invalid-feedback d-block">
                  {$_(`pages.goals.field-errors.${errors.endsAt}`)}
                </div>
              {/if}
            </div>
          </div>

          <div class="form-check form-switch">
            <input
              class="form-check-input"
              type="checkbox"
              role="switch"
              id="goal-active"
              bind:checked={form.active} />
            <label class="form-check-label" for="goal-active">{$_('common.active')}</label>
          </div>
          <div class="form-check form-switch">
            <input
              class="form-check-input"
              type="checkbox"
              role="switch"
              id="goal-show-on-store"
              bind:checked={form.showOnStore} />
            <label class="form-check-label" for="goal-show-on-store">
              {$_('modals.goal.show-on-store')}
            </label>
          </div>
        </div>
        <div class="modal-footer">
          <button class="btn btn-primary w-100" type="submit" disabled={saving}>
            {#if saving}
              <span class="spinner-border spinner-border-sm me-1" aria-hidden="true"></span>
            {/if}
            {goalId === null ? $_('common.create') : $_('common.save')}
          </button>
        </div>
      </form>
    </div>
  </div>
</div>

<script>
  import { api } from '@panomc/sdk/plugin-api';
  import { _, showErrorToast, showSuccessToast } from '../../../i18n';
  import { call, errorKey } from '../../utils/api.js';
  import { toEpoch, toLocalInput } from '../../utils/format.js';
  import {
    METRICS,
    NAME_MAX,
    PERIODS,
    blankGoal,
    goalBody,
    goalToForm,
    validateGoal,
  } from '../../utils/goals.js';
  import MoneyInput from '../MoneyInput.svelte';
  import ProductSelector from '../ProductSelector.svelte';

  // ctx: GET /context (or null); onSaved(): called after the modal is hidden by a successful save.
  let { ctx = null, onSaved = () => {} } = $props();

  let modalElement = $state(null);
  let goalId = $state(null);
  let form = $state(blankGoal());
  let startsText = $state('');
  let endsText = $state('');
  let saving = $state(false);
  let submitted = $state(false);

  const current = $derived({ ...form, startsAt: toEpoch(startsText), endsAt: toEpoch(endsText) });
  const errors = $derived(validateGoal(current));
  // A field shows its error after the first submit attempt.
  const shown = $derived(
    Object.fromEntries(Object.keys(errors).map((name) => [name, submitted])),
  );

  /** `goal` null = create. */
  export function open(goal = null) {
    goalId = goal?.id ?? null;
    form = goal ? goalToForm(goal) : blankGoal();
    startsText = toLocalInput(form.startsAt);
    endsText = toLocalInput(form.endsAt);
    submitted = false;
    saving = false;
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).show();
    }
  }

  async function submit(event) {
    event.preventDefault();
    if (saving) return;
    submitted = true;
    if (Object.keys(errors).length > 0) return;

    saving = true;
    const body = goalBody(current);
    const result = await call(
      goalId === null
        ? api.panel.post({ path: '/goals', body })
        : api.panel.put({ path: `/goals/${goalId}`, body }),
    );
    saving = false;

    if (!result.ok) {
      showErrorToast($_(errorKey(result.error)));
      return;
    }
    showSuccessToast($_(goalId === null ? 'modals.goal.toast-created' : 'modals.goal.toast-updated'));
    if (modalElement && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(modalElement).hide();
    }
    onSaved();
  }

  $effect(() => {
    const el = modalElement;
    return () => {
      if (el && window.bootstrap) window.bootstrap.Modal.getInstance(el)?.dispose();
    };
  });
</script>
