{#if data.state === 'DISABLED'}
  <StoreStateCard icon="fa-solid fa-store-slash fa-3x" text={$_('theme.store.closed')} />
{:else if data.state === 'ERROR'}
  <StoreStateCard
    icon="fa-solid fa-triangle-exclamation fa-3x"
    text={$_('theme.product.load-error')}
    onretry={() => location.reload()} />
{:else}
  <div class="row g-4">
    <div class="col-md-5">
      <div class="ratio ratio-1x1 bg-body-tertiary rounded overflow-hidden">
        {#if view.imageFileName}
          <img
            src="{base}/api/market/products/image/{encodeURIComponent(view.imageFileName)}"
            alt={product.name}
            class="object-fit-contain" />
        {:else}
          <div class="d-flex align-items-center justify-content-center">
            <i
              class={['fa-solid', product.icon || 'fa-box', 'fa-5x', 'text-body-secondary']}
              aria-hidden="true"></i>
          </div>
        {/if}
      </div>
    </div>

    <div class="col-md-7 vstack gap-3">
      <div class="vstack gap-2">
        {#if data.titleOptions}
          <h1 class="h3 mb-0">{product.name}</h1>
        {/if}
        <div class="d-flex flex-wrap align-items-center gap-2">
          {#if product.categoryName && product.categoryId != null}
            <a
              class="small link-secondary"
              href="{base}/store?category={encodeURIComponent(product.categoryId)}"
              >{product.categoryName}</a>
          {/if}
          {#if product.featured}
            <span class="badge text-bg-warning">
              <i class="fa-solid fa-star me-1" aria-hidden="true"></i>{$_(
                'theme.store.featured-badge',
              )}
            </span>
          {/if}
          <SaleBadge product={view} {settings} />
          {#if view.inStock === false}
            <span class="badge text-bg-secondary">{$_('theme.store.sold-out')}</span>
          {/if}
          {#if product.owned === true}
            <span class="badge text-bg-success">
              <i class="fa-solid fa-check me-1" aria-hidden="true"></i>{$_('theme.store.owned')}
            </span>
          {/if}
        </div>
      </div>

      <div>
        <div class="fs-4"><PriceTag product={view} {settings} showVat /></div>
        <SaleCountdown product={view} {settings} />
        <StockNote product={view} />
      </div>

      {#if product.upgrade}
        <div class="alert alert-info mb-0" role="status">
          {$_('theme.product.upgrade', {
            fromName: product.upgrade.fromName,
            deduction: formatMoney(product.upgrade.deduction, product.currency, {
              removeCents: settings.removeCents,
            }),
          })}
        </div>
      {/if}

      <form class="vstack gap-3" novalidate onsubmit={(event) => event.preventDefault()}>
        {#if product.hasVariants || product.variants?.length}
          <VariantPicker
            {product}
            {selection}
            {variantId}
            error={variantError}
            onselect={onSelect}
            onvariant={onVariant} />
        {/if}

        {#if product.fields?.length}
          <div>
            <CustomFields
              fields={product.fields}
              values={fieldValues}
              errors={fieldErrors}
              onchange={onFieldChange}
              onblur={onFieldBlur} />
          </div>
        {/if}

        {#if product.serverChoices?.length}
          <ServerSelect
            choices={product.serverChoices}
            value={serverId}
            error={serverError}
            onchange={onServer} />
        {/if}

        {#if showQuantity}
          <QuantityInput value={qty} max={qMax} onchange={(next) => (quantity = next)} />
        {/if}

        <AddToCart
          {buy}
          slug={data.slug}
          {missing}
          {pending}
          onadd={() => submit(false)}
          onbuy={() => submit(true)}
          onsubscribe={() => submit(true)} />
      </form>

      <ul class="list-unstyled small text-body-secondary mb-0 vstack gap-1">
        {#if product.allowGift && settings.allowGiftPurchase}
          <li>
            <i class="fa-solid fa-gift me-2" aria-hidden="true"></i>{$_(
              'theme.product.gift-available',
            )}
          </li>
        {/if}
        {#if product.physical}
          <li>
            <i class="fa-solid fa-truck me-2" aria-hidden="true"></i>{$_('theme.product.ships')}
          </li>
        {/if}
        {#if product.limitPerPlayer}
          <li>
            {$_('theme.product.limit-per-player', { count: product.limitPerPlayer })}
          </li>
        {/if}
      </ul>
    </div>
  </div>

  <div class="vstack gap-4 mt-4">
    {#if product.kind === 'BUNDLE'}
      <BundleList items={product.bundleItems || []} />
    {/if}

    <RequiredProducts
      products={product.requiredProducts || []}
      requireOnlyOne={product.requireOnlyOne === true} />

    {#if product.description}
      <div class="card">
        <div class="card-body">
          <!-- server-sanitised HTML (the only {@html} of the product page, 14 §2 rule 7) -->
          {@html product.description}
        </div>
      </div>
    {/if}
  </div>
{/if}

<script module>
  import { error } from '@panomc/sdk/svelte';
  import { resolveProductLoad, resolveSlug } from '../components/product/productModel.js';
  import { parseCurrency } from '../lib/storeFilter.js';
  import { ensureSettings, setSettings } from '../stores/storeSettings.js';
  import { call } from '../utils/api.js';
  import { has } from '../utils/host.js';

  export async function load(event) {
    const slug = resolveSlug(event.params?.slug, has('decoded-route-params'));
    const currency = parseCurrency(event.url.searchParams);

    const [res, settings] = await Promise.all([
      call('GET', `/api/market/products/${encodeURIComponent(slug)}`, {
        event,
        query: { currency },
      }),
      ensureSettings(event),
    ]);

    const result = resolveProductLoad({
      res,
      settings,
      slug,
      origin: event.url.origin,
      variantParam: event.url.searchParams.get('variant'),
      features: { meta: has('page-meta'), titleOptions: has('page-title-options') },
    });

    if (result.notFound) throw error(404);

    if (result.data.state === 'READY' && result.data.settingsLoaded)
      setSettings(result.data.settings);

    return result;
  }
</script>

<script>
  import { getContext, onMount, tick, untrack } from 'svelte';
  import { get } from 'svelte/store';
  import { base, goto } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n.js';
  import AddToCart from '../components/product/AddToCart.svelte';
  import BundleList from '../components/product/BundleList.svelte';
  import CustomFields from '../components/product/CustomFields.svelte';
  import QuantityInput from '../components/product/QuantityInput.svelte';
  import RequiredProducts from '../components/product/RequiredProducts.svelte';
  import ServerSelect from '../components/product/ServerSelect.svelte';
  import VariantPicker from '../components/product/VariantPicker.svelte';
  import {
    buildLine,
    buyState,
    clampQuantity,
    currentVariant,
    formValid,
    quantityMax,
    quantityVisible,
    validateForm,
    variantMissing,
  } from '../components/product/productModel.js';
  import PriceTag from '../components/store/PriceTag.svelte';
  import SaleBadge from '../components/store/SaleBadge.svelte';
  import SaleCountdown from '../components/store/SaleCountdown.svelte';
  import StockNote from '../components/store/StockNote.svelte';
  import StoreStateCard from '../components/store/StoreStateCard.svelte';
  import { validateField, initialFieldValues } from '../lib/validation.js';
  import { effectiveProduct, initialSelection } from '../lib/variants.js';
  import { cart } from '../stores/cart.js';
  import {
    adoptUrlCurrency,
    effectiveCurrency,
    initCurrency,
    needsCurrencyRefetch,
    preferred,
  } from '../stores/currency.js';
  import { bindSession } from '../stores/session.js';
  import { formatMoney } from '../utils/format.js';

  let { data } = $props();

  bindSession(getContext('session'));

  // The page is re-mounted whenever load() runs again (14 F2), so the loaded data only seeds the state.
  const init = untrack(() => data);
  const first =
    init.state === 'READY'
      ? initialSelection(init.product.variants, init.product.variantOptions, init.variantParam)
      : { variant: null, selection: {} };

  let product = $state(init.product ?? {});
  let settings = $state(init.settings ?? {});
  let selection = $state(first.selection);
  let variantId = $state(first.variant?.id ?? null);
  let fieldValues = $state(initialFieldValues(init.product?.fields));
  let fieldErrors = $state({});
  let variantError = $state(null);
  let serverError = $state(null);
  let serverId = $state(null);
  let quantity = $state(1);
  let pending = $state(false);
  let unmounted = false;

  const variant = $derived(currentVariant(product, { selection, variantId }));
  const view = $derived(effectiveProduct(product, variant));
  const buy = $derived(buyState(product, variant));
  const missing = $derived(variantMissing(product, variant));
  const qMax = $derived(quantityMax(product, variant));
  const qty = $derived(clampQuantity(quantity, qMax));
  const showQuantity = $derived(quantityVisible(product, variant));

  function writeVariantUrl(next) {
    if (!next) return;

    try {
      const url = new URL(window.location.href);
      url.searchParams.set('variant', String(next.id));
      window.history.replaceState(
        window.history.state,
        '',
        `${url.pathname}${url.search}${url.hash}`,
      );
    } catch (e) {
      // no-op
    }
  }

  function onSelect(next) {
    selection = next;
    variantError = null;
    writeVariantUrl(currentVariant(product, { selection: next }));
  }

  function onVariant(id) {
    variantId = id;
    variantError = null;
    writeVariantUrl(currentVariant(product, { variantId: id }));
  }

  function checkField(key) {
    const field = (product.fields || []).find((f) => f.fieldKey === key);
    if (!field) return;

    const code = validateField(field, fieldValues[key]);
    const next = { ...fieldErrors };
    if (code) next[key] = code;
    else delete next[key];
    fieldErrors = next;
  }

  function onFieldChange(key, value) {
    fieldValues = { ...fieldValues, [key]: value };
    if (fieldErrors[key]) checkField(key); // an error clears as soon as the value is fixed
  }

  function onFieldBlur(key) {
    checkField(key);
  }

  function onServer(id) {
    serverId = id;
    serverError = null;
  }

  async function submit(thenCheckout) {
    if (pending) return;

    const errors = validateForm(product, { variant, fieldValues, serverId });
    variantError = errors.variant;
    fieldErrors = errors.fields;
    serverError = errors.server;

    if (!formValid(errors)) {
      await tick();
      document.getElementById(errors.firstInvalid)?.focus();
      return;
    }

    pending = true;

    try {
      const line = buildLine(product, { variant, fieldValues, serverId, quantity: qty });
      const ok = await cart.add(line, view);

      if (ok && thenCheckout) await goto('/store/checkout');
    } finally {
      if (!unmounted) pending = false;
    }
  }

  // SSR rendered the default currency; one visible price update to the remembered one (14 §4.5)
  async function refetch(currency) {
    const res = await call('GET', `/api/market/products/${encodeURIComponent(data.slug)}`, {
      query: { currency },
    });

    if (!unmounted && res.ok && res.product) product = res.product;
  }

  onMount(() => {
    if (data.state !== 'READY') return undefined;

    if (data.settingsLoaded) setSettings(settings);
    initCurrency();

    const fromUrl = parseCurrency(new URLSearchParams(window.location.search));
    adoptUrlCurrency(settings, fromUrl);

    const urlCurrency = effectiveCurrency(settings, fromUrl, null);
    if (!urlCurrency && needsCurrencyRefetch(settings, get(preferred)))
      refetch(effectiveCurrency(settings, null, get(preferred)));

    return () => {
      unmounted = true;
    };
  });
</script>
