<script>
  let { isEdit = false } = $props();

  let giftCode = $state('');
  let giftType = $state('product'); // 'product' or 'credit'
  
  let selectedProductId = $state('');
  let creditAmount = $state('');

  let isExpiryUnlimited = $state(true);
  let expiryDate = $state('');
  
  let isRedeemUnlimited = $state(true);
  let redeemLimit = $state('');

  function generateGiftCode() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let result = '';
    for (let i = 0; i < 8; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    giftCode = result;
  }

  const products = [
    { id: 1, name: 'VIP Üyelik (Aylık)' },
    { id: 2, name: '1000 Kredi' },
    { id: 3, name: 'Kasa Anahtarı x10' },
    { id: 4, name: 'Özel Kanat' },
    { id: 5, name: 'Efekt Paketi' }
  ];
</script>

<div class="modal fade" id="createGiftModal" tabindex="-1" aria-labelledby="createGiftModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createGiftModalLabel">Hediye Oluştur</h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body pb-0">
        
        <!-- Gift Code -->
        <div class="input-group mb-3">
          <div class="form-floating">
            <input type="text" class="form-control" id="giftCodeInput" bind:value={giftCode} placeholder="Hediye kodunu girin" />
            <label for="giftCodeInput">Hediye Kodu</label>
          </div>
          <button class="btn btn-primary px-3 px-sm-4" type="button" onclick={generateGiftCode} title="Kodu Oluştur">
            <i class="fas fa-wand-magic-sparkles"></i>
            <span class="d-none d-sm-inline ms-2">Oluştur</span>
          </button>
        </div>

        <!-- Gift Type -->
        <div class="mb-3">
          <div class="d-flex flex-column flex-sm-row gap-2 gap-sm-3 mb-3 mt-1">
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="giftType" id="typeProduct" value="product" bind:group={giftType}>
              <label class="form-check-label cursor-pointer" for="typeProduct">Ürün Hediye Et</label>
            </div>
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="giftType" id="typeCredit" value="credit" bind:group={giftType}>
              <label class="form-check-label cursor-pointer" for="typeCredit">Kredi Hediye Et</label>
            </div>
          </div>

          {#if giftType === 'product'}
            <div class="list-group list-group-flush border rounded overflow-y-auto mb-0" style="max-height: 200px;">
              {#each products as product}
                <label class="list-group-item d-flex align-items-center gap-3 py-2 cursor-pointer list-group-item-action">
                  <input class="form-check-input flex-shrink-0 mt-0 cursor-pointer" type="radio" name="giftProduct" value={product.id} bind:group={selectedProductId}>
                  <span>{product.name}</span>
                </label>
              {/each}
            </div>
          {:else}
            <div class="input-group">
              <div class="form-floating">
                <input type="number" class="form-control" id="creditAmountInput" bind:value={creditAmount} placeholder="Kredi Miktarı" min="1" />
                <label for="creditAmountInput">Kredi Miktarı</label>
              </div>
              <span class="input-group-text">
                <i class="fas fa-coins text-warning"></i>
              </span>
            </div>
          {/if}
        </div>

        <!-- Expiry Date -->
        <div class="d-flex flex-column flex-sm-row align-items-start align-items-sm-center mb-3 gap-2">
          <div class="form-floating w-100">
            <input type="date" class="form-control" id="giftExpiryInput" bind:value={expiryDate} disabled={isExpiryUnlimited} placeholder="Son Kullanma Tarihi" />
            <label for="giftExpiryInput">Son Kullanma Tarihi</label>
          </div>
          <div class="form-check form-switch m-0 pe-2 pt-1 pt-sm-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="giftExpirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-1 cursor-pointer mt-1" for="giftExpirySwitch">Asla</label>
          </div>
        </div>

        <!-- Redeem Limit -->
        <div class="d-flex flex-column flex-sm-row align-items-start align-items-sm-center mb-0 gap-2">
          <div class="form-floating w-100">
            <input type="number" class="form-control" id="giftRedeemLimitInput" bind:value={redeemLimit} disabled={isRedeemUnlimited} placeholder="Kullanım Sınırı" min="1" />
            <label for="giftRedeemLimitInput">Kullanım Sınırı</label>
          </div>
          <div class="form-check form-switch m-0 pe-2 pt-1 pt-sm-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="giftRedeemLimitSwitch" bind:checked={isRedeemUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-1 cursor-pointer mt-1" for="giftRedeemLimitSwitch">Sınırsız</label>
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
