<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import GeneralDiscounts from '../components/discounts/GeneralDiscounts.svelte';
  import CouponCodes from '../components/discounts/CouponCodes.svelte';
  import CreatorCodes from '../components/discounts/CreatorCodes.svelte';
  import CreateDiscountModal from '../components/modals/CreateDiscountModal.svelte';
  import CreateCreatorCodeModal from '../components/modals/CreateCreatorCodeModal.svelte';
  import { onMount } from 'svelte';

  const SECTIONS = [
    { key: 'general', label: 'İndirimler' },
    { key: 'coupons', label: 'Kupon Kodları' },
    { key: 'creators', label: 'Referans Kodları' }
  ];

  let section = $state('general');

  onMount(() => {
    const params = new URLSearchParams(window.location.search);
    const value = params.get('section');
    if (value && SECTIONS.some((s) => s.key === value)) {
      section = value;
    }
  });

  $effect(() => {
    if (typeof window === 'undefined') return;
    const url = new URL(window.location.href);
    if (url.searchParams.get('section') !== section) {
      url.searchParams.set('section', section);
      window.history.replaceState({}, '', url);
    }
  });
</script>

<MarketLayout>
  {#snippet right()}
    {#if section === 'general'}
      <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createDiscountModal">
        <i class="fa-solid fa-plus"></i>
        <span class="d-lg-inline d-none ms-2">İndirim Oluştur</span>
      </button>
    {:else if section === 'coupons'}
      <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createCouponModal">
        <i class="fa-solid fa-plus"></i>
        <span class="d-lg-inline d-none ms-2">Kupon Kodu Oluştur</span>
      </button>
    {:else if section === 'creators'}
      <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createCreatorCodeModal">
        <i class="fa-solid fa-plus"></i>
        <span class="d-lg-inline d-none ms-2">Referans Kodu Oluştur</span>
      </button>
    {/if}
  {/snippet}

  <div class="row g-3">
    <aside class="col-12 col-md-3">
      <div class="nav flex-column nav-pills sticky-md-top" role="tablist" aria-orientation="vertical" aria-label="İndirimler menüsü">
        {#each SECTIONS as item (item.key)}
          <button
            type="button"
            class="nav-link text-start"
            class:active={section === item.key}
            role="tab"
            aria-selected={section === item.key}
            onclick={() => (section = item.key)}>
            {item.label}
          </button>
        {/each}
      </div>
    </aside>

    <div class="col-12 col-md-9">
      {#if section === 'general'}
        <GeneralDiscounts />
      {:else if section === 'coupons'}
        <CouponCodes />
      {:else if section === 'creators'}
        <CreatorCodes />
      {/if}
    </div>
  </div>
</MarketLayout>

<CreateDiscountModal />
<CreateCreatorCodeModal />
