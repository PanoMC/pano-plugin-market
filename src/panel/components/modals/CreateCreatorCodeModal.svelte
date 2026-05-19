<script>
  import { onMount } from 'svelte';

  let { isEdit = $bindable(false) } = $props();

  let creator = $state('');
  let code = $state('');
  let discount = $state('');
  let discountUnit = $state('%');
  let commission = $state('');
  let startDate = $state('');
  let expiryDate = $state('');
  let isExpiryUnlimited = $state(true);
  let isLimitUnlimited = $state(true);
  let redeemLimit = $state('');
  let status = $state('active'); // 'active' or 'inactive'

  function generateCode() {
    const chars = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
    let result = '';
    for (let i = 0; i < 8; i++) {
      result += chars.charAt(Math.floor(Math.random() * chars.length));
    }
    code = result;
  }

  onMount(() => {
    const modalEl = document.getElementById('createCreatorCodeModal');
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
</script>

<div class="modal fade" id="createCreatorCodeModal" tabindex="-1" aria-labelledby="createCreatorCodeModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header p-3">
        <h5 class="modal-title" id="createCreatorCodeModalLabel">
          {#if isEdit}Referans Kodu Düzenle{:else}Referans Kodu Oluştur{/if}
        </h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body p-3">
        <div class="vstack gap-3">
          <!-- Status Switch -->
          <div class="form-check form-switch m-0 d-flex align-items-center">
            <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="creatorStatusSwitch"
                   checked={status === 'active'}
                   onchange={(e) => status = e.target.checked ? 'active' : 'inactive'}
                   style="width: 2.5em; height: 1.25em;">
            <label class="form-check-label ms-2 cursor-pointer mt-1" for="creatorStatusSwitch">
              Aktif
            </label>
          </div>

          <!-- Creator / Partner Name -->
          <div class="form-floating">
            <input type="text" class="form-control" id="creatorNameInput" bind:value={creator} placeholder="Referans Oyuncu" />
            <label for="creatorNameInput">Referans Oyuncu</label>
          </div>

          <!-- Commission Rate -->
          <div class="form-floating">
            <input type="number" class="form-control" id="creatorCommissionInput" bind:value={commission} placeholder="Komisyon Oranı (%)" min="0" max="100" />
            <label for="creatorCommissionInput">Komisyon Oranı (%)</label>
          </div>

          <!-- Code input with Generate button -->
          <div class="input-group">
            <div class="form-floating flex-grow-1">
              <input type="text" class="form-control" id="creatorCodeInput" bind:value={code} placeholder="Kupon Kodu" />
              <label for="creatorCodeInput">Kupon Kodu</label>
            </div>
            <button class="btn btn-primary px-3 px-sm-4" type="button" onclick={generateCode} title="Kodu Oluştur">
              <i class="fas fa-wand-magic-sparkles"></i>
              <span class="d-none d-sm-inline ms-2">Oluştur</span>
            </button>
          </div>

          <!-- Discount and Unit -->
          <div class="row g-3">
            <div class="col-8">
              <div class="form-floating">
                <input type="number" class="form-control" id="creatorDiscountInput" bind:value={discount} placeholder="İndirim Değeri" min="0" />
                <label for="creatorDiscountInput">İndirim Değeri</label>
              </div>
            </div>
            <div class="col-4">
              <div class="form-floating">
                <select class="form-select" id="creatorDiscountUnitSelect" bind:value={discountUnit}>
                  <option value="%">Yüzde (%)</option>
                  <option value="₺">Sabit (₺)</option>
                </select>
                <label for="creatorDiscountUnitSelect">Birim</label>
              </div>
            </div>
          </div>

          <!-- Date / Expiry Settings -->
          <div class="vstack gap-2">
            <div class="form-check form-switch m-0">
              <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="creatorExpirySwitch" bind:checked={isExpiryUnlimited} style="width: 2.5em; height: 1.25em;">
              <label class="form-check-label ms-1 cursor-pointer mt-1" for="creatorExpirySwitch">Süresiz</label>
            </div>
            {#if !isExpiryUnlimited}
              <div class="row g-3 animate__animated animate__fadeIn">
                <div class="col-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="creatorStartDateInput" bind:value={startDate} placeholder="Başlangıç Tarihi" />
                    <label for="creatorStartDateInput">Başlangıç Tarihi</label>
                  </div>
                </div>
                <div class="col-6">
                  <div class="form-floating">
                    <input type="date" class="form-control" id="creatorExpiryInput" bind:value={expiryDate} placeholder="Bitiş Tarihi" />
                    <label for="creatorExpiryInput">Bitiş Tarihi</label>
                  </div>
                </div>
              </div>
            {/if}
          </div>

          <!-- Redeem Limit -->
          <div class="row g-3 align-items-center mb-0">
            <div class="col-6">
              <div class="form-check form-switch m-0">
                <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="creatorLimitSwitch" bind:checked={isLimitUnlimited} style="width: 2.5em; height: 1.25em;">
                <label class="form-check-label ms-1 cursor-pointer mt-1" for="creatorLimitSwitch">Limitsiz</label>
              </div>
            </div>
            <div class="col-6">
              {#if !isLimitUnlimited}
                <div class="form-floating animate__animated animate__fadeIn">
                  <input type="number" class="form-control" id="creatorLimitInput" bind:value={redeemLimit} placeholder="Kullanım Limiti" min="1" />
                  <label for="creatorLimitInput">Kullanım Limiti</label>
                </div>
              {/if}
            </div>
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
