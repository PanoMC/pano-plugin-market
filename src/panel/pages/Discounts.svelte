<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';

  // Each discount sub-section maps to its own list endpoint + safe empty shape.
  const SECTION_CONFIG = {
    general: {
      path: '/api/panel/market/discounts',
      empty: { discounts: [], discountCount: 0 },
    },
    coupons: {
      path: '/api/panel/market/coupons',
      empty: { coupons: [], couponCount: 0 },
    },
    creators: {
      path: '/api/panel/market/creator-codes',
      empty: { creatorCodes: [], creatorCodeCount: 0 },
    },
  };

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams },
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.discounts.title');

    const sectionParam = searchParams.get('section');
    const section = SECTION_CONFIG[sectionParam] ? sectionParam : 'general';

    const pageNum = parseInt(searchParams.get('page')) || 1;
    const search = searchParams.get('search');
    const statusParam = searchParams.get('status');

    const { path, empty } = SECTION_CONFIG[section];

    const fetchPage = (p) =>
      ApiUtil.get({
        path:
          path +
          buildQueryParams({
            page: p === 1 ? null : p,
            search,
            status: statusParam,
          }),
        request: event,
      });

    let effectivePage = pageNum;
    // Fetch the section list and the market settings in parallel; settings carries
    // the dynamic SALES-currency symbol shown by every money value on this page.
    let [body, settingsRes] = await Promise.all([
      fetchPage(pageNum),
      ApiUtil.get({ path: '/api/panel/market/settings', request: event }),
    ]);

    // Empty string when settings fail so money renders without a symbol (never ₺).
    const currencySymbol =
      settingsRes && !settingsRes.error ? settingsRes.currencySymbol || '' : '';

    // A stale ?page= (bookmark / back-button after deletes) points past the last
    // page; fall back to page 1 with the same filters instead of faking an empty store.
    if (body?.error === 'PAGE_NOT_FOUND' && pageNum > 1) {
      effectivePage = 1;
      body = await fetchPage(1);
    }

    if (!body || body.error) {
      return {
        data: { ...empty, totalPage: 1, page: 1, currencySymbol, error: body?.error || 'NETWORK_ERROR' },
      };
    }

    body.page = effectivePage;
    body.currencySymbol = currencySymbol;
    return { data: body };
  }
</script>

<script>
  import { page, base, goto } from '@panomc/sdk/svelte';
  import { _ } from '../../i18n.js';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import GeneralDiscounts from '../components/discounts/GeneralDiscounts.svelte';
  import CouponCodes from '../components/discounts/CouponCodes.svelte';
  import CreatorCodes from '../components/discounts/CreatorCodes.svelte';
  import CreateDiscountModal from '../components/modals/CreateDiscountModal.svelte';
  import CreateCouponModal from '../components/modals/CreateCouponModal.svelte';
  import CreateCreatorCodeModal from '../components/modals/CreateCreatorCodeModal.svelte';

  const SECTIONS = [
    { key: 'general', label: 'pages.discounts.sections.general' },
    { key: 'coupons', label: 'pages.discounts.sections.coupons' },
    { key: 'creators', label: 'pages.discounts.sections.creators' }
  ];

  let { data } = $props();

  // The active section AND every list filter (page/search/status) live in the URL
  // search params; load() reads them and fetches the active section's list. Tabs,
  // filter pills, search and pagination all navigate via goto() so the address bar
  // stays deep-linkable and the back button is correct. The panel host remounts the
  // whole plugin page on every load() re-run ({#key data}); that remount is the
  // accepted cost of URL-driven navigation here.
  let section = $derived.by(() => {
    const s = $page.url.searchParams.get('section');
    return SECTIONS.some((item) => item.key === s) ? s : 'general';
  });
  let currentPage = $derived(data.page || 1);
  let currentSearch = $derived($page.url.searchParams.get('search') || '');
  let currentStatus = $derived($page.url.searchParams.get('status') || 'all');
  let loadError = $derived(data.error || null);

  // Dynamic SALES-currency symbol from GET /settings (load()); threaded down to the
  // section lists and the create/edit modals so no money value hardcodes ₺.
  let currencySymbol = $derived(data.currencySymbol || '');

  // Called after create/update/save mutations from the shared modals: re-run load()
  // for the current section + page/search/status so the list reflects the change.
  function refresh() {
    const params = $page.url.searchParams;
    const queryParams = buildQueryParams({
      // Default section ('general') is omitted so the URL stays minimal.
      section: params.get('section') === 'general' ? null : params.get('section'),
      page: params.get('page'),
      search: params.get('search'),
      status: params.get('status'),
    });
    return goto(`${base}/market/discounts${queryParams}`, { invalidateAll: true });
  }

  // Switching tabs resets pagination/search/filters back to the section defaults;
  // the default section ('general') is omitted so it yields a clean /market/discounts.
  function goToSection(key) {
    const queryParams = buildQueryParams({ section: key === 'general' ? null : key });
    return goto(`${base}/market/discounts${queryParams}`, { invalidateAll: true });
  }

  // Modal state owned by the page; passed down to the shared Bootstrap modals.
  let discountEdit = $state(false);
  let selectedDiscount = $state(null);

  let couponEdit = $state(false);
  let selectedCoupon = $state(null);

  let creatorEdit = $state(false);
  let selectedCreator = $state(null);

  function openDiscountCreate() {
    discountEdit = false;
    selectedDiscount = null;
  }
  function openDiscountEdit(discount) {
    discountEdit = true;
    selectedDiscount = { ...discount };
  }

  function openCouponCreate() {
    couponEdit = false;
    selectedCoupon = null;
  }
  function openCouponEdit(coupon) {
    couponEdit = true;
    selectedCoupon = { ...coupon };
  }

  function openCreatorCreate() {
    creatorEdit = false;
    selectedCreator = null;
  }
  function openCreatorEdit(creatorCode) {
    creatorEdit = true;
    selectedCreator = { ...creatorCode };
  }
</script>

<MarketLayout>
  {#snippet right()}
    {#if section === 'general'}
      <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createDiscountModal" onclick={openDiscountCreate}>
        <i class="fa-solid fa-plus"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.discounts.create-discount')}</span>
      </button>
    {:else if section === 'coupons'}
      <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createCouponModal" onclick={openCouponCreate}>
        <i class="fa-solid fa-plus"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.discounts.create-coupon')}</span>
      </button>
    {:else if section === 'creators'}
      <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal" onclick={openCreatorCreate}>
        <i class="fa-solid fa-plus"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.discounts.create-creator-code')}</span>
      </button>
    {/if}
  {/snippet}

  <div class="row g-3">
    <aside class="col-12 col-md-3">
      <div class="nav flex-column nav-pills sticky-md-top" role="tablist" aria-orientation="vertical" aria-label={$_('pages.discounts.menu-label')}>
        {#each SECTIONS as item (item.key)}
          <button
            type="button"
            class="nav-link text-start"
            class:active={section === item.key}
            role="tab"
            aria-selected={section === item.key}
            onclick={() => goToSection(item.key)}>
            {$_(item.label)}
          </button>
        {/each}
      </div>
    </aside>

    <div class="col-12 col-md-9">
      {#if loadError}
        <div class="card">
          <div class="card-body text-center text-body-secondary py-5">
            <i class="fas fa-triangle-exclamation mb-2 fs-3"></i>
            <div>{$_('pages.discounts.load-error')}</div>
          </div>
        </div>
      {:else if section === 'general'}
        <GeneralDiscounts
          discounts={data.discounts}
          discountCount={data.discountCount}
          page={currentPage}
          totalPage={data.totalPage}
          search={currentSearch}
          status={currentStatus}
          {section}
          {currencySymbol}
          onEdit={openDiscountEdit} />
      {:else if section === 'coupons'}
        <CouponCodes
          coupons={data.coupons}
          couponCount={data.couponCount}
          page={currentPage}
          totalPage={data.totalPage}
          search={currentSearch}
          status={currentStatus}
          {section}
          {currencySymbol}
          onEdit={openCouponEdit} />
      {:else if section === 'creators'}
        <CreatorCodes
          creatorCodes={data.creatorCodes}
          creatorCodeCount={data.creatorCodeCount}
          page={currentPage}
          totalPage={data.totalPage}
          search={currentSearch}
          status={currentStatus}
          {section}
          {currencySymbol}
          onEdit={openCreatorEdit} />
      {/if}
    </div>
  </div>
</MarketLayout>

<CreateDiscountModal isEdit={discountEdit} discount={selectedDiscount} {currencySymbol} onSaved={refresh} />
<CreateCouponModal isEdit={couponEdit} coupon={selectedCoupon} {currencySymbol} onSaved={refresh} />
<CreateCreatorCodeModal isEdit={creatorEdit} creatorCode={selectedCreator} {currencySymbol} onSaved={refresh} />
