<script>
  let storeName = $state('');
  let storeDescription = $state('');
  let currency = $state('TRY');
  let vatPercent = $state(20);
  let showVatInPrice = $state(true);
  let testMode = $state(false);
  let allowGuestCheckout = $state(true);
  let minimumOrderAmount = $state(0);

  const currencies = [
    { value: 'TRY', label: 'Türk Lirası (₺)' },
    { value: 'USD', label: 'US Dollar ($)' },
    { value: 'EUR', label: 'Euro (€)' },
    { value: 'GBP', label: 'British Pound (£)' }
  ];
</script>

<div class="card">
  <div class="card-header">
    <h6 class="mb-0">Genel Ayarlar</h6>
    <small class="text-body-secondary">Mağazanızın temel görünüm ve davranış ayarları.</small>
  </div>

  <div class="card-body">
    <div class="row g-3">
      <div class="col-md-12">
        <div class="form-floating">
          <input type="text" class="form-control" id="storeNameInput" placeholder="Mağaza Adı" bind:value={storeName} />
          <label for="storeNameInput">Mağaza Adı</label>
        </div>
      </div>

      <div class="col-md-12">
        <div class="form-floating">
          <textarea class="form-control" id="storeDescInput" style="height: 80px;" placeholder="Mağaza Açıklaması" bind:value={storeDescription}></textarea>
          <label for="storeDescInput">Mağaza Açıklaması</label>
        </div>
      </div>

      <div class="col-md-6">
        <div class="form-floating">
          <select class="form-select" id="currencyInput" bind:value={currency}>
            {#each currencies as c (c.value)}
              <option value={c.value}>{c.label}</option>
            {/each}
          </select>
          <label for="currencyInput">Para Birimi</label>
        </div>
      </div>

      <div class="col-md-6">
        <div class="form-floating">
          <input type="number" min="0" max="100" step="0.5" class="form-control" id="vatInput" placeholder="KDV %" bind:value={vatPercent} />
          <label for="vatInput">KDV (%)</label>
        </div>
      </div>

      <div class="col-md-6">
        <div class="form-floating">
          <input type="number" min="0" step="1" class="form-control" id="minOrderInput" placeholder="Minimum Sipariş Tutarı" bind:value={minimumOrderAmount} />
          <label for="minOrderInput">Minimum Sipariş Tutarı</label>
        </div>
      </div>

      <div class="col-md-6 d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="vatInPriceInput" bind:checked={showVatInPrice} />
          <label class="form-check-label user-select-none" for="vatInPriceInput">
            Fiyatlara KDV dahildir göster
          </label>
        </div>
      </div>

      <div class="col-md-6 d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="guestCheckoutInput" bind:checked={allowGuestCheckout} />
          <label class="form-check-label user-select-none" for="guestCheckoutInput">
            Misafir alışverişine izin ver
          </label>
        </div>
      </div>

      <div class="col-md-6 d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="testModeInput" bind:checked={testMode} />
          <label class="form-check-label user-select-none" for="testModeInput">
            Test modu (canlı satış yapılmaz)
          </label>
        </div>
      </div>
    </div>
  </div>

  <div class="card-footer d-flex justify-content-end">
    <button type="button" class="btn btn-primary">
      <i class="fas fa-floppy-disk me-1"></i>
      Kaydet
    </button>
  </div>
</div>
