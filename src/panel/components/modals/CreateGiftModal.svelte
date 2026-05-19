<script>
  import { onMount } from 'svelte';
  import ProductSelector from '../ProductSelector.svelte';

  let { isEdit = $bindable(false) } = $props();

  let giftCode = $state('');
  let giftType = $state('product'); // 'product', 'credit', or 'random'
  
  let selectedProductId = $state('');
  let creditAmount = $state('');
  let selectedRandomProductIds = $state([]);

  let isExpiryUnlimited = $state(true);
  let startDate = $state('');
  let expiryDate = $state('');
  let status = $state('active'); // 'active' or 'inactive'

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


  onMount(() => {
    const modalEl = document.getElementById('createGiftModal');
    if (modalEl) {
      const handleShow = (event) => {
        const trigger = event.relatedTarget;
        if (trigger && (trigger.classList.contains('dropdown-item') || trigger.closest('.dropdown-item') || trigger.classList.contains('font-monospace') || trigger.closest('.font-monospace'))) {
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
</script>

<div class="modal fade" id="createGiftModal" tabindex="-1" aria-labelledby="createGiftModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createGiftModalLabel">
          {#if isEdit}Hediyeyi Düzenle{:else}Hediye Oluştur{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body pb-0">
        
        <!-- Status Switch -->
        <div class="mb-3">
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="giftStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="giftStatusSwitch">
              Aktif
            </label>
          </div>
        </div>

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
              <label class="form-check-label cursor-pointer" for="typeProduct">Ürün</label>
            </div>
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="giftType" id="typeCredit" value="credit" bind:group={giftType}>
              <label class="form-check-label cursor-pointer" for="typeCredit">Kredi</label>
            </div>
            <div class="form-check m-0">
              <input class="form-check-input cursor-pointer" type="radio" name="giftType" id="typeRandom" value="random" bind:group={giftType}>
              <label class="form-check-label cursor-pointer" for="typeRandom">Rastgele</label>
            </div>
          </div>

          {#if giftType === 'product'}
            <ProductSelector products={products} bind:selected={selectedProductId} multiple={false} maxHeight="200px" />
          {:else if giftType === 'credit'}
            <div class="input-group">
              <div class="form-floating">
                <input type="number" class="form-control" id="creditAmountInput" bind:value={creditAmount} placeholder="Kredi Miktarı" min="1" />
                <label for="creditAmountInput">Kredi Miktarı</label>
              </div>
              <span class="input-group-text">
                <i class="fas fa-coins text-warning"></i>
              </span>
            </div>
          {:else if giftType === 'random'}
            <div class="vstack gap-2 animate__animated animate__fadeIn">
              <label class="form-label mb-0">Rastgele Verilecek Ürünler</label>
              <ProductSelector products={products} bind:selected={selectedRandomProductIds} multiple={true} maxHeight="180px" />
            </div>
          {/if}
        </div>

        <!-- Date / Expiry Settings -->
        <div class="vstack gap-2 mb-3">
          <div class="form-check form-switch m-0">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="giftExpirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-1 cursor-pointer mt-1" for="giftExpirySwitch">Süresiz</label>
          </div>
          {#if !isExpiryUnlimited}
            <div class="row g-3 animate__animated animate__fadeIn">
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="giftStartDateInput" bind:value={startDate} placeholder="Başlangıç Tarihi" />
                  <label for="giftStartDateInput">Başlangıç Tarihi</label>
                </div>
              </div>
              <div class="col-6">
                <div class="form-floating">
                  <input type="date" class="form-control" id="giftExpiryInput" bind:value={expiryDate} placeholder="Bitiş Tarihi" />
                  <label for="giftExpiryInput">Bitiş Tarihi</label>
                </div>
              </div>
            </div>
          {/if}
        </div>

      </div>
      <div class="modal-footer p-3 pt-3">
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
