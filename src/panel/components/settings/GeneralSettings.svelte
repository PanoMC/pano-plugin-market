<script>
  let storeName = $state('');
  let storeDescription = $state('');
  let currency = $state('TRY');
  let vatPercent = $state(20);
  let showVatInPrice = $state(true);
  let testMode = $state(false);
  let allowGuestCheckout = $state(true);
  let minimumOrderAmount = $state(0);
  let removeCents = $state(false);
  let showBestsellers = $state(true);
  let showFeaturedProducts = $state(true);
  let sendEmailAfterPurchase = $state(true);
  let combineDiscountsAndCoupons = $state(true);

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
    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="storeNameInput">Mağaza Adı</label>
      <div class="col-md-6">
        <input type="text" class="form-control" id="storeNameInput" placeholder="Mağaza Adı" bind:value={storeName} />
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="storeDescInput">Mağaza Açıklaması</label>
      <div class="col-md-6">
        <textarea class="form-control" id="storeDescInput" style="height: 80px;" placeholder="Mağaza Açıklaması" bind:value={storeDescription}></textarea>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="currencyInput">Para Birimi</label>
      <div class="col-md-6">
        <select class="form-select" id="currencyInput" bind:value={currency}>
          {#each currencies as c (c.value)}
            <option value={c.value}>{c.label}</option>
          {/each}
        </select>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="vatInput">Vergi (%)</label>
      <div class="col-md-6">
        <input type="number" min="0" max="100" step="0.5" class="form-control" id="vatInput" placeholder="Vergi %" bind:value={vatPercent} />
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6 col-form-label" for="minOrderInput">Minimum Sipariş Tutarı</label>
      <div class="col-md-6">
        <input type="number" min="0" step="1" class="form-control" id="minOrderInput" placeholder="Minimum Sipariş Tutarı" bind:value={minimumOrderAmount} />
      </div>
    </div>

    <hr class="my-4" />

    <div class="row mb-3">
      <label class="col-md-6" for="vatInPriceInput">Fiyatlara vergi dahildir göster</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="vatInPriceInput" bind:checked={showVatInPrice} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="guestCheckoutInput">Misafir alışverişine izin ver</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="guestCheckoutInput" bind:checked={allowGuestCheckout} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="testModeInput">Test modu (canlı satış yapılmaz)</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="testModeInput" bind:checked={testMode} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="removeCentsInput">Para biriminden .00 kısmını kaldır</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="removeCentsInput" bind:checked={removeCents} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="showBestsellersInput">En çok satanları göster (Bestseller)</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="showBestsellersInput" bind:checked={showBestsellers} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="showFeaturedProductsInput">Öne çıkan ürünleri göster (Featured Products)</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="showFeaturedProductsInput" bind:checked={showFeaturedProducts} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="sendEmailAfterPurchaseInput">Satın alımdan sonra e-posta gönder</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="sendEmailAfterPurchaseInput" bind:checked={sendEmailAfterPurchase} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <label class="col-md-6" for="combineDiscountsAndCouponsInput">Genel indirimler ve kuponlar birlikte kullanılabilir mi</label>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input cursor-pointer" type="checkbox" role="switch" id="combineDiscountsAndCouponsInput" bind:checked={combineDiscountsAndCoupons} />
        </div>
      </div>
    </div>


  </div>

  <div class="card-footer d-flex justify-content-start">
    <button type="button" class="btn btn-secondary">
      Kaydet
    </button>
  </div>
</div>
