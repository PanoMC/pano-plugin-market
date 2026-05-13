<script>
  let { isEdit = false } = $props();

  let couponCode = $state('');
  let productSelection = $state('all'); // 'all' or 'selected'
  
  let isExpiryUnlimited = $state(true);
  let expiryDate = $state('');
  
  let isRedeemUnlimited = $state(true);
  let redeemLimit = $state('');
  
  let isCustomerRedeemUnlimited = $state(true);
  let customerRedeemLimit = $state('');
  
  let discount = $state('');
  let minPaymentAmount = $state('');

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

  function toggleProduct(id) {
    if (selectedProducts.includes(id)) {
      selectedProducts = selectedProducts.filter(pId => pId !== id);
    } else {
      selectedProducts = [...selectedProducts, id];
    }
  }
</script>

<div class="modal fade" id="createCouponModal" tabindex="-1" aria-labelledby="createCouponModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createCouponModalLabel">Kupon Oluştur</h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body pb-0">
        
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
            <div class="list-group list-group-flush border rounded overflow-y-auto mb-0" style="max-height: 200px;">
              {#each products as product}
                <label class="list-group-item d-flex align-items-center gap-3 py-2 cursor-pointer list-group-item-action">
                  <input class="form-check-input flex-shrink-0 mt-0 cursor-pointer" type="checkbox" value={product.id} 
                         checked={selectedProducts.includes(product.id)}
                         onchange={() => toggleProduct(product.id)}>
                  <span>{product.name}</span>
                </label>
              {/each}
            </div>
          {/if}
        </div>

        <!-- Discount and Min Payment Amount -->
        <div class="d-flex flex-column flex-sm-row gap-3 mb-3">
          <div class="input-group w-100">
            <div class="form-floating">
              <input type="number" class="form-control" id="discountInput" bind:value={discount} placeholder="İndirim Oranı" min="0" max="100" />
              <label for="discountInput">İndirim Oranı</label>
            </div>
            <span class="input-group-text">%</span>
          </div>
          
          <div class="input-group w-100">
            <div class="form-floating">
              <input type="number" class="form-control" id="minPaymentInput" bind:value={minPaymentAmount} placeholder="Min Sepet Tutarı" min="0" />
            <label for="minPaymentInput">Min Sepet Tutarı</label>
            </div>
            <span class="input-group-text">₺</span>
          </div>
        </div>

        <!-- Expiry Date -->
        <div class="d-flex flex-column flex-sm-row align-items-start align-items-sm-center mb-3 gap-2">
          <div class="form-floating w-100">
            <input type="date" class="form-control" id="expiryInput" bind:value={expiryDate} disabled={isExpiryUnlimited} placeholder="Son Kullanma Tarihi" />
            <label for="expiryInput">Son Kullanma Tarihi</label>
          </div>
          <div class="form-check form-switch m-0 pe-2 pt-1 pt-sm-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="expirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label fw-medium ms-1 cursor-pointer mt-1" for="expirySwitch">Sınırsız</label>
          </div>
        </div>

        <!-- Redeem Limit -->
        <div class="d-flex flex-column flex-sm-row align-items-start align-items-sm-center mb-3 gap-2">
          <div class="form-floating w-100">
            <input type="number" class="form-control" id="redeemLimitInput" bind:value={redeemLimit} disabled={isRedeemUnlimited} placeholder="Kullanım Sınırı" min="1" />
            <label for="redeemLimitInput">Kullanım Sınırı</label>
          </div>
          <div class="form-check form-switch m-0 pe-2 pt-1 pt-sm-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="redeemLimitSwitch" bind:checked={isRedeemUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label fw-medium ms-1 cursor-pointer mt-1" for="redeemLimitSwitch">Sınırsız</label>
          </div>
        </div>

        <!-- Redeem Limit Per Customer -->
        <div class="d-flex flex-column flex-sm-row align-items-start align-items-sm-center mb-0 gap-2">
          <div class="form-floating w-100">
            <input type="number" class="form-control" id="customerLimitInput" bind:value={customerRedeemLimit} disabled={isCustomerRedeemUnlimited} placeholder="Kişi Başı Sınır" min="1" />
            <label for="customerLimitInput">Kişi Başı Sınır</label>
          </div>
          <div class="form-check form-switch m-0 pe-2 pt-1 pt-sm-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="customerLimitSwitch" bind:checked={isCustomerRedeemUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label fw-medium ms-1 cursor-pointer mt-1" for="customerLimitSwitch">Sınırsız</label>
          </div>
        </div>

      </div>
      <div class="modal-footer border-0 p-3 pt-3">
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
