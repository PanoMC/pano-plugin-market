<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import GeneralSettings from '../components/settings/GeneralSettings.svelte';
  import PaymentMethods from '../components/settings/PaymentMethods.svelte';
  import { onMount } from 'svelte';

  const SECTIONS = [
    { key: 'general', label: 'Genel Ayarlar', icon: 'fa-sliders' },
    { key: 'payments', label: 'Ödeme Yöntemleri', icon: 'fa-credit-card' }
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
  <div class="row g-3">
    <aside class="col-12 col-md-3">
      <div class="nav flex-column nav-pills sticky-md-top" role="tablist" aria-orientation="vertical" aria-label="Ayarlar menüsü">
        {#each SECTIONS as item (item.key)}
          <button
            type="button"
            class="nav-link d-flex align-items-center gap-2"
            class:active={section === item.key}
            role="tab"
            aria-selected={section === item.key}
            onclick={() => (section = item.key)}>
            <i class="fas {item.icon}"></i>
            <span>{item.label}</span>
          </button>
        {/each}
      </div>
    </aside>

    <div class="col-12 col-md-9">
      {#if section === 'general'}
        <GeneralSettings />
      {:else if section === 'payments'}
        <PaymentMethods />
      {/if}
    </div>
  </div>
</MarketLayout>
