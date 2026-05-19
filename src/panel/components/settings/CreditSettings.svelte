<script>
  import { showToast } from '@panomc/sdk/toasts';

  let creditsEnabled = $state(true);
  let creditName = $state('Kredi');
  let cashbackPercent = $state(0);
  let onlyAcceptCredits = $state(false);

  function handleSave() {
    showToast('Kredi ayarları başarıyla kaydedildi.');
  }
</script>

<div class="card">
  <div class="card-header">
    <h6 class="mb-0">Kredi Ayarları</h6>
    <small class="text-body-secondary">Mağazanızın kredi sistemi ve ödeme davranış ayarları.</small>
  </div>

  <div class="card-body">
    <div class="row mb-3">
      <div class="col-md-6">
        <label for="creditsEnabledInput" class="d-block mb-1">Krediler</label>
        <small class="text-body-secondary d-block">Sistem genelinde kredi ile ödeme ve bakiye kullanımını aktif eder.</small>
      </div>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="creditsEnabledInput" bind:checked={creditsEnabled} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <div class="col-md-6">
        <label for="onlyAcceptCreditsInput" class="d-block mb-1">Sadece Kredi ile Ödeme Kabul Et</label>
        <small class="text-body-secondary d-block">Kullanıcıların doğrudan ödeme yöntemleri yerine yalnızca hesap bakiyelerindeki (kredileriyle) satın alım yapabilmesini zorunlu kılar.</small>
      </div>
      <div class="col d-flex align-items-center">
        <div class="form-check form-switch m-0">
          <input class="form-check-input" type="checkbox" role="switch" id="onlyAcceptCreditsInput" bind:checked={onlyAcceptCredits} />
        </div>
      </div>
    </div>

    <div class="row mb-3">
      <div class="col-md-6">
        <label class="col-form-label pb-0" for="creditNameInput">Kredi İsmi</label>
        <small class="text-body-secondary d-block">Kullanıcılara gösterilecek para birimi ismi.</small>
      </div>
      <div class="col-md-6">
        <input type="text" class="form-control" id="creditNameInput" placeholder="Kredi İsmi" bind:value={creditName} />
      </div>
    </div>

    <div class="row mb-3">
      <div class="col-md-6">
        <label class="col-form-label pb-0" for="cashbackInput">Cashback (%)</label>
        <small class="text-body-secondary d-block">Mağazadan yapılan harcamalarda kullanıcıya geri verilecek kredi oranı.</small>
      </div>
      <div class="col-md-6">
        <input type="number" min="0" max="100" step="0.5" class="form-control" id="cashbackInput" placeholder="Cashback %" bind:value={cashbackPercent} />
      </div>
    </div>
  </div>

  <div class="card-footer d-flex justify-content-start">
    <button type="button" class="btn btn-secondary" onclick={handleSave}>
      Kaydet
    </button>
  </div>
</div>
