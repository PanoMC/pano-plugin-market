<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';
  import { marketPath } from '../utils/api.js';
  import { loadContext } from '../utils/context.js';
  import { loadList } from '../utils/list.js';

  // Each discount section maps to its own list endpoint + the empty-row key of a failed load.
  const LISTS = {
    general: { path: '/discounts', emptyKey: 'discounts' },
    coupons: { path: '/coupons', emptyKey: 'coupons' },
    creators: { path: '/creator-codes', emptyKey: 'creatorCodes' },
  };
  const SECTION_KEYS = ['general', 'coupons', 'creators', 'payouts'];

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const sectionParam = event.url.searchParams.get('section');
    const section = SECTION_KEYS.includes(sectionParam) ? sectionParam : 'general';

    if (section === 'payouts') {
      const { pageTitle } = await event.parent();
      pageTitle?.set?.('plugins.pano-plugin-market.pages.discounts.title');
      const params = event.url.searchParams;
      const filters = { from: params.get('from'), to: params.get('to') };
      const [body, ctx] = await Promise.all([
        ApiUtil.get({
          path: marketPath('/creator-codes/report') + buildQueryParams(filters),
          request: event,
        }),
        loadContext(event),
      ]);
      if (!body || typeof body !== 'object' || body.error)
        return {
          data: {
            section,
            creators: [],
            currency: ctx?.currency ?? '',
            filters,
            ctx,
            error: (body && typeof body === 'object' && body.error) || 'NETWORK_ERROR',
          },
        };
      return {
        data: {
          section,
          creators: body.creators ?? [],
          currency: body.currency ?? ctx?.currency ?? '',
          filters,
          ctx,
        },
      };
    }

    const result = await loadList(event, {
      path: LISTS[section].path,
      params: ['search', 'status'],
      nodes: ['DISC'],
      emptyKey: LISTS[section].emptyKey,
      title: 'pages.discounts.title',
    });
    result.data.section = section;
    return result;
  }
</script>

<script>
  import { base, goto, invalidateAll, page } from '@panomc/sdk/svelte';
  import { _, showSuccessToast } from '../../i18n';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import LoadError from '../components/LoadError.svelte';
  import CouponCodes from '../components/discounts/CouponCodes.svelte';
  import CreatorCodes from '../components/discounts/CreatorCodes.svelte';
  import CreatorPayouts from '../components/discounts/CreatorPayouts.svelte';
  import GeneralDiscounts from '../components/discounts/GeneralDiscounts.svelte';
  import CreateCouponModal from '../components/modals/CreateCouponModal.svelte';
  import CreateCreatorCodeModal from '../components/modals/CreateCreatorCodeModal.svelte';
  import CreateDiscountModal from '../components/modals/CreateDiscountModal.svelte';
  import PayoutModal from '../components/modals/PayoutModal.svelte';
  import RedemptionsModal from '../components/modals/RedemptionsModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call } from '../utils/api.js';
  import { toastError } from '../utils/toast.js';

  let { data } = $props();

  const user = $derived($page.data?.user);
  const ctx = $derived(data.ctx ?? null);
  const section = $derived(SECTION_KEYS.includes(data.section) ? data.section : 'general');
  const currentPage = $derived(data.page || 1);
  const currentSearch = $derived($page.url.searchParams.get('search') || '');
  const currentStatus = $derived($page.url.searchParams.get('status') || 'all');
  const loadError = $derived(data.error || null);

  let confirmModal = $state(null);
  let redemptionsModal = $state(null);
  let payoutModal = $state(null);

  // Called after a mutation from the modals (already hidden); a modal is hidden before the page is
  // re-loaded (13 §1.4), Bootstrap's fade takes 300 ms.
  function refresh() {
    setTimeout(() => invalidateAll(), 350);
  }

  // Modal state owned by the page; passed down to the shared Bootstrap modals.
  let discountEdit = $state(false);
  let selectedDiscount = $state(null);
  let couponEdit = $state(false);
  let selectedCoupon = $state(null);
  let creatorEdit = $state(false);
  let selectedCreator = $state(null);

  const openDiscountCreate = () => ((discountEdit = false), (selectedDiscount = null));
  const openDiscountEdit = (row) => ((discountEdit = true), (selectedDiscount = { ...row }));
  const openCouponCreate = () => ((couponEdit = false), (selectedCoupon = null));
  const openCouponEdit = (row) => ((couponEdit = true), (selectedCoupon = { ...row }));
  const openCreatorCreate = () => ((creatorEdit = false), (selectedCreator = null));
  const openCreatorEdit = (row) => ((creatorEdit = true), (selectedCreator = { ...row }));

  // kind -> endpoint, row count of the page and the locale group of its texts.
  const DELETE = {
    discount: { path: '/discounts', group: 'general', label: (row) => row.name },
    coupon: { path: '/coupons', group: 'coupons', label: (row) => row.code },
    creator: { path: '/creator-codes', group: 'creators', label: (row) => row.code },
  };

  function remove(kind, row, count) {
    const config = DELETE[kind];
    confirmModal?.open({
      icon: 'fa-solid fa-trash',
      title: $_(`discounts.${config.group}.delete-title`),
      description: $_(`discounts.${config.group}.confirm-delete`, {
        values: { name: config.label(row), code: config.label(row) },
      }),
      confirmLabel: $_('common.delete'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(ApiUtil.delete({ path: marketPath(`${config.path}/${row.id}`) }));
        if (!result.ok) {
          // A row that is already gone (404 NOT_FOUND) is toasted and the list refreshed.
          toastError($_, result);
          if (result.error === 'NOT_FOUND') refreshStepBack(count);
          return false;
        }
        showSuccessToast($_(`discounts.${config.group}.toast-delete-success`));
        refreshStepBack(count);
      },
    });
  }

  // The deleted row may have been the last on this page; step back so the refetch does not request
  // a now-out-of-range page (backend -> PAGE_NOT_FOUND).
  function refreshStepBack(count) {
    const target = count === 1 && currentPage > 1 ? currentPage - 1 : currentPage;
    const query = buildQueryParams({
      section: section === 'general' ? null : section,
      page: target > 1 ? target : null,
      search: currentSearch || null,
      status: currentStatus === 'all' ? null : currentStatus,
    });
    setTimeout(() => goto(`${base}/market/discounts${query}`, { invalidateAll: true }), 350);
  }

  function openRedemptions(kind, row) {
    redemptionsModal?.open({ kind, id: row.id, code: row.code });
  }

  function openPayout(row) {
    payoutModal?.open({ creator: row, currency: data.currency });
  }
</script>

<MarketLayout area="discounts" sections={sectionsFor('discounts', user)} active={section}>
  {#snippet right()}
    {#if section === 'general'}
      <button
        type="button"
        class="btn btn-secondary"
        data-bs-toggle="modal"
        data-bs-target="#createDiscountModal"
        onclick={openDiscountCreate}>
        <i class="fa-solid fa-plus" aria-hidden="true"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.discounts.create-discount')}</span>
      </button>
    {:else if section === 'coupons'}
      <button
        type="button"
        class="btn btn-secondary"
        data-bs-toggle="modal"
        data-bs-target="#createCouponModal"
        onclick={openCouponCreate}>
        <i class="fa-solid fa-plus" aria-hidden="true"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.discounts.create-coupon')}</span>
      </button>
    {:else if section === 'creators'}
      <button
        type="button"
        class="btn btn-secondary"
        data-bs-toggle="modal"
        data-bs-target="#createCreatorCodeModal"
        onclick={openCreatorCreate}>
        <i class="fa-solid fa-plus" aria-hidden="true"></i>
        <span class="d-lg-inline d-none ms-2">{$_('pages.discounts.create-creator-code')}</span>
      </button>
    {/if}
  {/snippet}

  {#if loadError}
    <LoadError error={loadError} />
  {:else if section === 'general'}
    <GeneralDiscounts
      discounts={data.discounts}
      discountCount={data.discountCount}
      page={currentPage}
      totalPage={data.totalPage}
      search={currentSearch}
      status={currentStatus}
      {section}
      {ctx}
      onEdit={openDiscountEdit}
      onDelete={(row) => remove('discount', row, data.discounts?.length ?? 0)} />
  {:else if section === 'coupons'}
    <CouponCodes
      coupons={data.coupons}
      couponCount={data.couponCount}
      page={currentPage}
      totalPage={data.totalPage}
      search={currentSearch}
      status={currentStatus}
      {section}
      {ctx}
      onEdit={openCouponEdit}
      onRedemptions={(row) => openRedemptions('coupons', row)}
      onDelete={(row) => remove('coupon', row, data.coupons?.length ?? 0)} />
  {:else if section === 'creators'}
    <CreatorCodes
      creatorCodes={data.creatorCodes}
      creatorCodeCount={data.creatorCodeCount}
      page={currentPage}
      totalPage={data.totalPage}
      search={currentSearch}
      status={currentStatus}
      {section}
      {ctx}
      onEdit={openCreatorEdit}
      onRedemptions={(row) => openRedemptions('creator-codes', row)}
      onDelete={(row) => remove('creator', row, data.creatorCodes?.length ?? 0)} />
  {:else}
    <CreatorPayouts
      creators={data.creators}
      currency={data.currency}
      filters={data.filters}
      {user}
      onPayout={openPayout} />
  {/if}
</MarketLayout>

<ConfirmModal bind:this={confirmModal} />
<RedemptionsModal bind:this={redemptionsModal} {ctx} />
<PayoutModal bind:this={payoutModal} {ctx} onSaved={refresh} />
<CreateDiscountModal isEdit={discountEdit} discount={selectedDiscount} {ctx} onSaved={refresh} />
<CreateCouponModal isEdit={couponEdit} coupon={selectedCoupon} {ctx} onSaved={refresh} />
<CreateCreatorCodeModal
  isEdit={creatorEdit}
  creatorCode={selectedCreator}
  {ctx}
  onSaved={refresh} />
