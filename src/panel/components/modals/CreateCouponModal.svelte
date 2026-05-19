<script>
  import { onMount } from 'svelte';
  import ProductSelector from '../ProductSelector.svelte';

  let { isEdit = $bindable(false) } = $props();

  let couponCode = $state('');
  let couponName = $state('');

  onMount(() => {
    const modalEl = document.getElementById('createCouponModal');
    if (modalEl) {
      const handleShow = (event) => {
        const trigger = event.relatedTarget;
        if (trigger && (trigger.classList.contains('dropdown-item') || trigger.closest('.dropdown-item'))) {
          isEdit = true;
        } else {
          isEdit = false;
        }
      };
      modalEl.addEventListener('show.bs.modal', handleShow);
      return () => {
        modalEl.removeEventListener('show.bs.modal', handleShow);
      };
    }
  });
  let productSelection = $state('all'); // 'all' or 'selected'
  
  let isExpiryUnlimited = $state(true);
  let startDate = $state('');
  let expiryDate = $state('');
  
  let isRedeemUnlimited = $state(true);
  let redeemLimit = $state('');
  
  let isCustomerRedeemUnlimited = $state(true);
  let customerRedeemLimit = $state('');
  
  let discount = $state('');
  let discountUnit = $state('%');
  let minPaymentAmount = $state('');
  let status = $state('active'); // 'active' or 'inactive'

  function generateCouponCode() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let result = '';
    for (let i = 0; i < 8; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    couponCode = result;
  }

  const products = [
    { id: 1, name: 'VIP Üyelik (Aylık)' },
    { id: 2, name: '1000 Kredi' },
    { id: 3, name: 'Kasa Anahtarı x10' },
    { id: 4, name: 'Özel Kanat' },
    { id: 5, name: 'Efekt Paketi' }
  ];
  let selectedProducts = $state([]);
</script>

<div class="modal fade" id="createCouponModal" tabindex="-1" aria-labelledby="createCouponModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header p-3">
        <h5 class="modal-title" id="createCouponModalLabel">
          {#if isEdit}Kupon Kodu Düzenle{:else}Kupon Kodu Oluştur{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body p-3">
        
        <!-- Status Switch -->
        <div class="mb-3">
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="couponStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="couponStatusSwitch">
              Aktif
            </label>
          </div>
        </div>

        <!-- Coupon Name -->
        <div class="form-floating mb-3">
          <input type="text" class="form-control" id="couponNameInput" bind:value={couponName} placeholder="Kupon Adı" />
          <label for="couponNameInput">Kupon Adı</label>
        </div>

        <!-- Coupon Code -->
        <div class="input-group mb-3">
          <div class="form-floating">
            <input type="text" class="form-control" id="couponCodeInput" bind:value={couponCode} placeholder="Kupon kodunu girin" />
            <label for="couponCodeInput">Kupon Kodu</label>
          </div>
          <button class="btn btn-primary px-3 px-sm-4" type="button" onclick={generateCouponCode} title="Kodu Oluştur">
            <i class="fas fa-wand-magic-sparkles"></i>
            <span class="d-none d-sm-inline ms-2">Oluştur</span>
          </button>
        </div>

        <!-- Products Selection -->
        <div class="mb-3">
          <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3 mb-2 mt-1">
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="productSelection" id="allProducts" value="all" bind:group={productSelection}>
              <label class="form-check-label cursor-pointer" for="allProducts">Tüm Ürünler</label>
            </div>
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="productSelection" id="selectedProducts" value="selected" bind:group={productSelection}>
              <label class="form-check-label cursor-pointer" for="selectedProducts">Seçili Ürünler</label>
            </div>
          </div>

          {#if productSelection === 'selected'}
            <ProductSelector products={products} bind:selected={selectedProducts} multiple={true} maxHeight="200px" />
          {/if}
        </div>

        <!-- Discount and Min Payment Amount -->
        <div class="row g-3 mb-3">
          <div class="col-8">
            <div class="form-floating">
              <input type="number" class="form-control" id="discountInput" bind:value={discount} placeholder="İndirim Değeri" min="0" />
              <label for="discountInput">İndirim Değeri</label>
            </div>
          </div>
          <div class="col-4">
            <div class="form-floating">
              <select class="form-select" id="discountUnitSelect" bind:value={discountUnit}>
                <option value="%">Yüzde (%)</option>
                <option value="₺">Sabit (₺)</option>
              </select>
              <label for="discountUnitSelect">Birim</label>
            </div>
          </div>
        </div>

        <!-- Minimum Sepet Tutarı -->
        <div class="input-group mb-3">
          <div class="form-floating">
            <input type="number" class="form-control" id="minPaymentInput" bind:value={minPaymentAmount} placeholder="Min Sepet Tutarı" min="0" />
            <label for="minPaymentInput">Min Sepet Tutarı</label>
          </div>
          <span class="input-group-text">₺</span>
        </div>

        <!-- Date / Expiry Settings -->
        <div class="vstack gap-2 mb-3">
          <div class="form-check form-switch m-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="expirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-1 cursor-pointer mt-1" for="expirySwitch">Süresiz</label>
          </div>
          {#if !isExpiryUnlimited}
            <div class="row g-3 animate__animated animate__fadeIn">
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="startDateInput" bind:value={startDate} placeholder="Başlangıç Tarihi" />
                  <label for="startDateInput">Başlangıç Tarihi</label>
                </div>
              </div>
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="expiryInput" bind:value={expiryDate} placeholder="Bitiş Tarihi" />
                  <label for="expiryInput">Bitiş Tarihi</label>
                </div>
              </div>
            </div>
          {/if}
        </div>

        <!-- Redeem Limit -->
        <div class="row g-3 align-items-center mb-3">
          <div class="col-6">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="redeemLimitSwitch" bind:checked={isRedeemUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="redeemLimitSwitch">Limitsiz</label>
            </div>
          </div>
          <div class="col-6">
            {#if !isRedeemUnlimited}
              <div class="form-floating animate__animated animate__fadeIn">
                <input type="number" class="form-control" id="redeemLimitInput" bind:value={redeemLimit} placeholder="Kullanım Limiti" min="1" />
                <label for="redeemLimitInput">Kullanım Limiti</label>
              </div>
            {/if}
          </div>
        </div>

        <!-- Redeem Limit Per Customer -->
        <div class="row g-3 align-items-center mb-0">
          <div class="col-6">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="customerLimitSwitch" bind:checked={isCustomerRedeemUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="customerLimitSwitch">Kişi Başı Sınırsız</label>
            </div>
          </div>
          <div class="col-6">
            {#if !isCustomerRedeemUnlimited}
              <div class="form-floating animate__animated animate__fadeIn">
                <input type="number" class="form-control" id="customerLimitInput" bind:value={customerRedeemLimit} placeholder="Kişi Başı Limit" min="1" />
                <label for="customerLimitInput">Kişi Başı Limit</label>
              </div>
            {/if}
          </div>
        </div>

      </div>
      <div class="modal-footer p-3">
        {#if isEdit}
          <button type="button" class="btn btn-primary w-100 m-0">Kaydet</button>
        {:else}
          <button type="button" class="btn btn-secondary w-100 m-0">Oluştur</button>
        {/if}
      </div>
    </div>
  </div>
</div>

<style>
  .cursor-pointer {
    cursor: pointer;
  }
</style>
