<MarketLayout area="orders" sections={sectionsFor('orders', user)} active="orders">
  {#snippet left()}
    <a class="btn btn-link px-0" href="{base}/market/orders">
      <i class="fa-solid fa-arrow-left me-1" aria-hidden="true"></i>
      {$_('pages.create-order.back')}
    </a>
  {/snippet}

  {#if data.error}
    <LoadError error={data.error} />
  {:else}
    {#if offerForce && !form.force}
      <div class="alert alert-warning d-flex align-items-start" role="alert">
        <i class="fa-solid fa-triangle-exclamation me-3 mt-1" aria-hidden="true"></i>
        <div>
          <b>{$_('pages.create-order.force-alert.title')}</b>
          <div>{$_('pages.create-order.force-alert.body')}</div>
          <button class="btn alert-btn mt-2" type="button" onclick={enableForce}>
            {$_('pages.create-order.force-alert.enable')}
          </button>
        </div>
      </div>
    {/if}

    <div class="row g-3">
      <div class="col-lg-8">
        <div class="vstack gap-3">
          <div class="card">
            <CardHeader>
              <div slot="left">{$_('pages.create-order.buyer.title')}</div>
            </CardHeader>
            <div class="card-body vstack gap-3">
              <div>
                <input
                  class="form-control"
                  class:is-invalid={touched.playerUsername && fieldErrors.playerUsername}
                  type="text"
                  maxlength="32"
                  autocomplete="off"
                  placeholder={$_('pages.create-order.buyer.player')}
                  aria-label={$_('pages.create-order.buyer.player')}
                  bind:value={form.playerUsername}
                  onblur={() => (touched.playerUsername = true)} />
                {#if touched.playerUsername && fieldErrors.playerUsername}
                  <div class="invalid-feedback d-block">
                    {$_('pages.create-order.errors.player-invalid')}
                  </div>
                {/if}
              </div>

              <div class="form-check form-switch">
                <input
                  class="form-check-input"
                  type="checkbox"
                  role="switch"
                  id="create-order-gift"
                  bind:checked={form.gift} />
                <label class="form-check-label" for="create-order-gift">
                  {$_('pages.create-order.buyer.gift')}
                </label>
              </div>

              {#if form.gift}
                <div>
                  <input
                    class="form-control"
                    class:is-invalid={recipientInvalid}
                    type="text"
                    maxlength="32"
                    autocomplete="off"
                    placeholder={$_('pages.create-order.buyer.recipient')}
                    aria-label={$_('pages.create-order.buyer.recipient')}
                    bind:value={form.recipientUsername}
                    oninput={() => (recipientRejected = false)}
                    onblur={() => (touched.recipientUsername = true)} />
                  {#if recipientInvalid}
                    <div class="invalid-feedback d-block">
                      {$_(
                        fieldErrors.recipientUsername === 'SAME_AS_BUYER'
                          ? 'pages.create-order.errors.recipient-same'
                          : 'pages.create-order.errors.recipient-invalid',
                      )}
                    </div>
                  {/if}
                </div>
              {/if}

              <div>
                <input
                  class="form-control"
                  class:is-invalid={touched.email && fieldErrors.email}
                  type="email"
                  autocomplete="off"
                  placeholder={$_('pages.create-order.buyer.email')}
                  aria-label={$_('pages.create-order.buyer.email')}
                  bind:value={form.email}
                  onblur={() => (touched.email = true)} />
                {#if touched.email && fieldErrors.email}
                  <div class="invalid-feedback d-block">
                    {$_('pages.create-order.errors.email-invalid')}
                  </div>
                {/if}
              </div>
            </div>
          </div>

          <div class="card">
            <CardHeader>
              <div slot="left">
                {$_('pages.create-order.items.title', { values: { count: lines.length } })}
              </div>
              <div slot="right">
                <div class="dropdown">
                  <button
                    type="button"
                    class="btn btn-sm btn-link"
                    data-bs-toggle="dropdown"
                    aria-expanded="false"
                    title={$_('common.actions')}
                    aria-label={$_('common.actions')}>
                    <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
                  </button>
                  <div class="dropdown-menu dropdown-menu-end animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item" onclick={() => itemModal?.open()}>
                      <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
                      {$_('pages.create-order.items.add')}
                    </button>
                  </div>
                </div>
              </div>
            </CardHeader>

            {#if lines.length === 0}
              <NoContent icon="" />
            {:else}
              <div class="table-responsive">
                <table class="table table-hover">
                  <thead>
                    <tr>
                      <th class="align-middle text-nowrap" scope="col"></th>
                      <th class="align-middle text-nowrap" scope="col">
                        {$_('pages.create-order.table.product')}
                      </th>
                      <th class="align-middle text-nowrap" scope="col">
                        {$_('pages.create-order.table.qty')}
                      </th>
                      <th class="align-middle text-nowrap" scope="col">
                        {$_('pages.create-order.table.unit-price')}
                      </th>
                      <th class="align-middle text-nowrap" scope="col">
                        {$_('pages.create-order.table.total')}
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {#each lines as row, index (row.id)}
                      {@const q = matched[index]}
                      {@const codes = lineErrorCodes(q, serverErrors[index])}
                      {@const entries = Object.entries(canonicalFieldValues(row.fieldValues))}
                      <tr>
                        <td class="align-middle">
                          <button
                            type="button"
                            class="btn btn-sm btn-link link-danger"
                            title={$_('common.remove')}
                            aria-label={$_('common.remove')}
                            onclick={() => remove(index)}>
                            <i class="fa-solid fa-xmark" aria-hidden="true"></i>
                          </button>
                        </td>
                        <td class="align-middle">
                          <div>{q?.name ?? row.name}</div>
                          {#if q?.variantName ?? row.variantName}
                            <div class="text-body-secondary">
                              {q?.variantName ?? row.variantName}
                            </div>
                          {/if}
                          {#if entries.length > 0}
                            <div class="text-body-secondary">
                              {entries.map(([k, v]) => `${k}: ${v}`).join(', ')}
                            </div>
                          {/if}
                          {#each codes as code (code)}
                            <div class="text-danger">{$_(lineErrorKey(code))}</div>
                          {/each}
                        </td>
                        <td class="align-middle" style="width: 7rem;">
                          <input
                            class="form-control form-control-sm"
                            class:is-invalid={quantityError(row.quantity, {
                              max: q?.maxQuantity ?? null,
                              force: form.force,
                            }) !== null}
                            type="number"
                            min="1"
                            step="1"
                            aria-label={$_('pages.create-order.table.qty')}
                            value={row.quantity}
                            oninput={(e) => changeQuantity(index, e.currentTarget.value)} />
                        </td>
                        <td class="align-middle text-nowrap">
                          {q && !pending ? fmt.money(q.unitPrice, quote?.currency) : DASH}
                        </td>
                        <td class="align-middle text-nowrap">
                          {q && !pending ? fmt.money(q.lineTotal, quote?.currency) : DASH}
                        </td>
                      </tr>
                    {/each}
                  </tbody>
                </table>
              </div>
            {/if}
          </div>
        </div>
      </div>

      <div class="col-lg-4">
        <div class="card">
          <CardHeader>
            <div slot="left">{$_('pages.create-order.summary.title')}</div>
          </CardHeader>
          <div class="card-body vstack gap-3">
            <dl class="row mb-0">
              <dt class="col-6 fw-normal">{$_('pages.create-order.summary.subtotal')}</dt>
              <dd class="col-6 text-end mb-1">{total('subtotal')}</dd>
              <dt class="col-6 fw-normal">{$_('pages.create-order.summary.discount')}</dt>
              <dd class="col-6 text-end mb-1">{total('discountTotal')}</dd>
              <dt class="col-6 fw-normal">{$_('pages.create-order.summary.vat')}</dt>
              <dd class="col-6 text-end mb-1">{total('vatTotal')}</dd>
              <dt class="col-6">{$_('pages.create-order.summary.total')}</dt>
              <dd class="col-6 text-end mb-0 fw-semibold">{total('total')}</dd>
            </dl>

            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="create-order-override"
                bind:checked={form.override} />
              <label class="form-check-label" for="create-order-override">
                {$_('pages.create-order.summary.override')}
              </label>
            </div>
            {#if form.override}
              <MoneyInput
                bind:value={form.priceOverride}
                {currency}
                exponent={currencyExponent}
                invalid={!!fieldErrors.priceOverride}
                placeholder={$_('pages.create-order.summary.override-placeholder')} />
            {/if}

            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="create-order-mark-paid"
                bind:checked={form.markPaid} />
              <label class="form-check-label" for="create-order-mark-paid">
                {$_('pages.create-order.summary.mark-paid')}
              </label>
            </div>
            {#if form.markPaid}
              <input
                class="form-control"
                class:is-invalid={fieldErrors.paymentLabel}
                type="text"
                maxlength="255"
                placeholder={defaultLabel}
                aria-label={$_('pages.create-order.summary.payment-label')}
                bind:value={form.paymentLabel} />
            {/if}

            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="create-order-run-deliveries"
                disabled={!form.markPaid}
                checked={effectiveRunDeliveries(form)}
                onchange={(e) => (form.runDeliveries = e.currentTarget.checked)} />
              <label class="form-check-label" for="create-order-run-deliveries">
                {$_('pages.create-order.summary.run-deliveries')}
              </label>
            </div>

            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="create-order-send-mail"
                disabled={!mailAllowed}
                checked={form.sendMail && mailAllowed}
                onchange={(e) => (form.sendMail = e.currentTarget.checked)} />
              <label class="form-check-label" for="create-order-send-mail">
                {$_('pages.create-order.summary.send-mail')}
              </label>
            </div>

            <div class="form-check form-switch">
              <input
                class="form-check-input"
                type="checkbox"
                role="switch"
                id="create-order-force"
                bind:checked={form.force} />
              <label class="form-check-label" for="create-order-force">
                {$_('pages.create-order.summary.force')}
              </label>
            </div>

            <div>
              <textarea
                class="form-control"
                class:is-invalid={fieldErrors.note}
                rows="3"
                maxlength="2000"
                placeholder={$_('pages.create-order.summary.note')}
                aria-label={$_('pages.create-order.summary.note')}
                bind:value={form.note}></textarea>
            </div>

            <button
              type="button"
              class="btn btn-primary w-100"
              disabled={!cta.canSubmit}
              onclick={submit}>
              {saving ? $_('common.saving') : $_('pages.create-order.submit')}
            </button>
            {#if cta.reason === 'OVER_LIMIT'}
              <div class="text-danger">{$_('pages.create-order.over-limit')}</div>
            {/if}
          </div>
        </div>
      </div>
    </div>
  {/if}
</MarketLayout>

<AddOrderItemModal
  bind:this={itemModal}
  {products}
  {servers}
  force={form.force}
  onAdd={addProduct} />

<script module>
  import ApiUtil from '@panomc/sdk/utils/api';
  import { guard } from '../utils/guard.js';
  import { loadContextWith } from '../utils/list-core.js';
  import { marketPath } from '../utils/api.js';
  import { PLUGIN_ID } from '../utils/plugin.js';
  import { playerFromSearch } from '../components/create-order/model.js';

  /**
   * GET /context, /products/simple, /servers (13 §7). A failed side list leaves the page usable with
   * an empty list; a missing context only drops the currency.
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const allowed = await guard(event, ['PAY']);
    if (allowed.denied) return allowed.denied;
    allowed.pageTitle?.set?.(`plugins.${PLUGIN_ID}.pages.create-order.title`);

    const get = (options) => ApiUtil.get(options);
    const [ctx, productsRes, serversRes] = await Promise.all([
      loadContextWith({ get }, event),
      get({ path: marketPath('/products/simple'), request: event }),
      get({ path: marketPath('/servers'), request: event }),
    ]);
    const ok = (res) => res && typeof res === 'object' && !res.error;
    return {
      data: {
        ctx,
        products: ok(productsRes) ? (productsRes.products ?? []) : [],
        servers: ok(serversRes) ? (serversRes.servers ?? []) : [],
        player: playerFromSearch(event.url?.search),
        error: null,
      },
    };
  }
</script>

<script>
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { base, goto, page } from '@panomc/sdk/svelte';
  import { onDestroy, untrack } from 'svelte';
  import { _, showErrorToast, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import LoadError from '../components/LoadError.svelte';
  import MoneyInput from '../components/MoneyInput.svelte';
  import AddOrderItemModal from '../components/modals/AddOrderItemModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call, errorKey, errorParams, newIdempotency, resetIdempotency } from '../utils/api.js';
  import { fmt } from '../utils/locale.js';
  import {
    addLine,
    buildBody,
    canSendMail,
    canonicalFieldValues,
    createQuoteRunner,
    detailPath,
    effectiveRunDeliveries,
    formErrors,
    initialForm,
    lineErrorCodes,
    lineErrorKey,
    matchQuoteLines,
    quantityError,
    removeLine,
    setQuantity,
    submitFailure,
    submitHeaders,
    submitState,
  } from '../components/create-order/model.js';

  let { data } = $props();

  const DASH = '—';
  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const products = $derived(data.products ?? []);
  const servers = $derived(data.servers ?? []);

  const prefill = untrack(() => data.player ?? '');
  let form = $state(initialForm(prefill));
  let touched = $state({});
  let lines = $state([]);
  let quote = $state(null);
  let pending = $state(false);
  let serverErrors = $state([]);
  let offerForce = $state(false);
  let recipientRejected = $state(false);
  let saving = $state(false);
  let submitted = $state(false);
  let itemModal = $state(null);
  let nextRowId = 1;
  const idempotency = newIdempotency();

  // A form the admin has touched guards navigation away (beforeunload).
  const dirty = $derived(
    !submitted &&
      (lines.length > 0 ||
        JSON.stringify($state.snapshot(form)) !== JSON.stringify(initialForm(prefill))),
  );
  const defaultLabel = $derived($_('pages.create-order.default-payment-label'));
  const fieldErrors = $derived(formErrors(form));
  const recipientInvalid = $derived(
    form.gift &&
      ((touched.recipientUsername && fieldErrors.recipientUsername) || recipientRejected),
  );
  const body = $derived(buildBody(form, lines, { defaultLabel }));
  const matched = $derived(matchQuoteLines(quote, lines));
  const mailAllowed = $derived(canSendMail(form, quote));
  const cta = $derived(submitState({ form, lines, quote, serverErrors, saving }));
  const currency = $derived(quote?.currency ?? ctx?.currency ?? '');
  const currencyExponent = $derived(
    ctx?.currencies?.find?.((c) => c.code === currency)?.exponent ?? 2,
  );

  function total(key) {
    if (!quote || pending) return DASH;
    return fmt.money(quote[key], quote.currency);
  }

  const runner = createQuoteRunner({
    send: (payload) => call(ApiUtil.post({ path: marketPath('/orders/quote'), body: payload })),
    onPending: (value) => (pending = value),
    onResult: (result) => (quote = result),
  });
  onDestroy(() => runner.cancel());

  // The quote is requested again on every change of the request body (debounced, last wins).
  $effect(() => {
    const payload = $state.snapshot(body);
    JSON.stringify(payload);
    untrack(() => {
      quote = null;
      serverErrors = [];
      if (
        payload.items.length === 0 ||
        fieldErrors.playerUsername ||
        fieldErrors.recipientUsername
      ) {
        runner.cancel();
        return;
      }
      runner.schedule(payload);
    });
  });

  // A dirty form guards navigation away.
  $effect(() => {
    if (!dirty) return;
    const handler = (event) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', handler);
    return () => window.removeEventListener('beforeunload', handler);
  });

  function addProduct(line, meta) {
    const withMeta = { ...line, name: meta.name, variantName: meta.variantName };
    const merged = addLine(
      lines.map((l) => $state.snapshot(l)),
      withMeta,
    );
    lines = merged.map((l) => ({ ...l, id: l.id ?? `r${nextRowId++}` }));
  }

  function remove(index) {
    lines = removeLine(lines, index);
  }

  function changeQuantity(index, value) {
    const text = String(value).trim();
    lines = setQuantity(lines, index, text === '' ? '' : Number(text));
  }

  function enableForce() {
    form.force = true;
    offerForce = false;
  }

  async function submit() {
    if (!cta.canSubmit || saving) return;
    saving = true;
    offerForce = false;
    const payload = $state.snapshot(body);
    const result = await call(
      ApiUtil.post({
        path: marketPath('/orders'),
        body: payload,
        headers: submitHeaders(idempotency, payload),
      }),
    );
    saving = false;
    if (!result.ok) {
      const outcome = submitFailure(result.error, result.body, lines.length);
      showErrorToast($_(errorKey(outcome.toast), errorParams(outcome.toast, result.body)));
      if (outcome.reset) resetIdempotency(idempotency);
      if (outcome.offerForce) offerForce = true;
      if (outcome.field === 'recipientUsername') {
        recipientRejected = true;
        touched.recipientUsername = true;
      }
      if (outcome.lineErrors) serverErrors = outcome.lineErrors;
      return;
    }
    resetIdempotency(idempotency);
    submitted = true;
    runner.cancel();
    showSuccessToast($_('pages.create-order.toast-created'));
    const path = detailPath(result.body);
    await goto(`${base}${path ?? '/market/orders'}`);
  }
</script>
