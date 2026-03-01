<script>
  import { _ } from '../../main';
  import MarketNav from '../components/MarketNav.svelte';

  let activeSettingsTab = 'general';

  // Gerçek Ayar Modelleri (Örnek)
  let generalSettings = {
    useCart: true,
    useCredit: true,
    creditName: 'PanoCoin',
    creditIcon: 'fa-coins',
  };

  let paymentGateways = [
    { id: 'paytr', name: 'PayTR', active: true, desc: 'Kredi Kartı / Havale EFT', fields: { merchantId: '12345', merchantKey: '*******', merchantSalt: '*******' } },
    { id: 'tebex', name: 'Tebex', active: false, desc: 'Yurtdışı / Global Ödemeler', fields: { secret: '' } },
    { id: 'stripe', name: 'Stripe', active: false, desc: 'Hızlı Kredi Kartı', fields: { publishKey: '', secretKey: '' } }
  ];
</script>

<div class="container vstack gap-3">
  <MarketNav />

  <div class="row">
  <div class="col-12 col-xl-3 mb-4">
    <!-- Sol Tarafta Ayar Menüsü -->
    <div class="list-group shadow-sm border-0">
      <button class="list-group-item list-group-item-action d-flex align-items-center rounded-top {activeSettingsTab === 'general' ? 'active shadow-sm z-1' : 'border-bottom-0'}" on:click={() => activeSettingsTab = 'general'}>
        <div class="bg-primary text-white bg-opacity-10 text-primary rounded-circle d-flex align-items-center justify-content-center me-3" style="width: 32px; height: 32px; {activeSettingsTab === 'general' ? 'background: rgba(255,255,255,0.2) !important; color: #fff !important;' : ''}">
          <i class="fa fa-cogs"></i>
        </div>
        <div class="fw-medium">Genel Ayarlar</div>
      </button>

      <button class="list-group-item list-group-item-action d-flex align-items-center {activeSettingsTab === 'payments' ? 'active shadow-sm z-1' : 'border-bottom-0'}" on:click={() => activeSettingsTab = 'payments'}>
        <div class="bg-success text-white bg-opacity-10 text-success rounded-circle d-flex align-items-center justify-content-center me-3" style="width: 32px; height: 32px; {activeSettingsTab === 'payments' ? 'background: rgba(255,255,255,0.2) !important; color: #fff !important;' : ''}">
          <i class="fa fa-credit-card"></i>
        </div>
        <div class="fw-medium">Ödeme Yöntemleri</div>
      </button>
      
      <button class="list-group-item list-group-item-action d-flex align-items-center rounded-bottom {activeSettingsTab === 'currencies' ? 'active shadow-sm z-1' : ''}" on:click={() => activeSettingsTab = 'currencies'}>
        <div class="bg-warning text-white bg-opacity-10 text-warning rounded-circle d-flex align-items-center justify-content-center me-3" style="width: 32px; height: 32px; {activeSettingsTab === 'currencies' ? 'background: rgba(255,255,255,0.2) !important; color: #fff !important;' : ''}">
          <i class="fa fa-money-bill-wave"></i>
        </div>
        <div class="fw-medium">Döviz ve Kur Ayarları</div>
      </button>
    </div>
  </div>

  <div class="col-12 col-xl-9 mb-4">
    <!-- Sağ Tarafta Aktif İçerik -->
    <div class="card shadow-sm border-0 h-100">
      {#if activeSettingsTab === 'general'}
        <div class="card-header bg-transparent py-3 border-bottom d-flex justify-content-between align-items-center">
          <h5 class="mb-0 text-primary">Market Genel Özellikleri</h5>
          <button class="btn btn-primary btn-sm px-3"><i class="fa fa-save me-1"></i> Kaydet</button>
        </div>
        <div class="card-body">
          <div class="row g-4 mb-4">
            <div class="col-md-6 border-end">
              <h6 class="fw-bold mb-3">Market Deneyimi</h6>
              <div class="form-check form-switch mb-3">
                <input class="form-check-input flex-shrink-0 cursor-pointer" type="checkbox" role="switch" id="useCartSwitch" bind:checked={generalSettings.useCart}>
                <label class="form-check-label ms-2 d-flex flex-column cursor-pointer" for="useCartSwitch">
                  <span class="fw-medium text-body">Sepet Sistemini Aktif Et</span>
                  <span class="text-muted small">Kullanıcıların birden çok ürünü sepete ekleyip tek seferde almasını sağlar.</span>
                </label>
              </div>
              <div class="form-check form-switch mb-3">
                <input class="form-check-input flex-shrink-0 cursor-pointer" type="checkbox" role="switch" id="guestCheckout">
                <label class="form-check-label ms-2 d-flex flex-column cursor-pointer" for="guestCheckout">
                  <span class="fw-medium text-body">Misafir Satın Alma (Üyeliksiz)</span>
                  <span class="text-muted small">Minecraft sunucusuna hiç giriş yapmamış kullancılar adıyla alım yapabilir.</span>
                </label>
              </div>
            </div>
            
            <div class="col-md-6">
              <h6 class="fw-bold mb-3">Kredi (Bakiye) Sistemi</h6>
              <div class="form-check form-switch mb-3">
                <input class="form-check-input flex-shrink-0 cursor-pointer" type="checkbox" role="switch" id="useCreditSwitch" bind:checked={generalSettings.useCredit}>
                <label class="form-check-label ms-2 d-flex flex-column cursor-pointer" for="useCreditSwitch">
                  <span class="fw-medium text-body">Site İçi Kredi Kullanımı</span>
                  <span class="text-muted small">Bakiye yükleyerek (örn: PanoCoins) alışveriş yapmayı sağlar.</span>
                </label>
              </div>
              
              {#if generalSettings.useCredit}
                <div class="bg-body-tertiary p-3 rounded mt-3 border animation-fadeIn">
                  <div class="mb-2">
                    <label class="form-label fw-semibold small" for="creditName">Özel Kredi Adı</label>
                    <input type="text" class="form-control form-control-sm" id="creditName" bind:value={generalSettings.creditName}>
                  </div>
                  <div>
                    <label class="form-label fw-semibold small" for="creditIcon">Kredi İkonu (FontAwesome)</label>
                    <div class="input-group input-group-sm">
                      <span class="input-group-text"><i class="fa {generalSettings.creditIcon}"></i></span>
                      <input type="text" class="form-control" id="creditIcon" bind:value={generalSettings.creditIcon}>
                    </div>
                  </div>
                </div>
              {/if}
            </div>
          </div>
        </div>

      {:else if activeSettingsTab === 'payments'}
        <div class="card-header bg-transparent py-3 border-bottom d-flex justify-content-between align-items-center">
          <h5 class="mb-0 text-success">Desteklenen Ödeme Entegrasyonları</h5>
          <button class="btn btn-success btn-sm px-3"><i class="fa fa-save me-1"></i> Tümünü Kaydet</button>
        </div>
        <div class="card-body bg-body-tertiary">
          <p class="text-muted small mb-4"><i class="fa fa-info-circle me-1"></i>Sadece aktif edilen yöntemler kasa/ödeme ekranında oyunculara gösterilir.</p>
          
          <div class="accordion" id="paymentsAccordion">
            {#each paymentGateways as gateway, i}
              <div class="accordion-item shadow-sm mb-3 border-0 rounded overflow-hidden">
                <h2 class="accordion-header" id="heading-{gateway.id}">
                  <button class="accordion-button {gateway.active ? '' : 'collapsed'} bg-body border-bottom-0" type="button" data-bs-toggle="collapse" data-bs-target="#collapse-{gateway.id}" aria-expanded={gateway.active ? 'true' : 'false'}>
                    <div class="d-flex justify-content-between align-items-center w-100 pe-3">
                      <div class="d-flex align-items-center">
                        <div class="form-check form-switch m-0 me-3" on:click|stopPropagation>
                          <input class="form-check-input cursor-pointer" type="checkbox" role="switch" bind:checked={gateway.active} id="switch-{gateway.id}">
                        </div>
                        <div>
                          <strong class="{gateway.active ? 'text-body' : 'text-muted'} fs-5">{gateway.name}</strong>
                          <div class="small text-muted">{gateway.desc}</div>
                        </div>
                      </div>
                      {#if gateway.active}
                        <span class="badge bg-success bg-opacity-10 text-success border border-success border-opacity-25 rounded-pill px-3 py-2"><i class="fa fa-circle me-1" style="font-size: 8px;"></i> Aktif</span>
                      {/if}
                    </div>
                  </button>
                </h2>
                <div id="collapse-{gateway.id}" class="accordion-collapse collapse {gateway.active ? 'show' : ''}" aria-labelledby="heading-{gateway.id}" data-bs-parent="#paymentsAccordion">
                  <div class="accordion-body bg-body border-top">
                    <div class="row g-3">
                      {#each Object.entries(gateway.fields) as [key, value]}
                        <div class="col-md-6">
                          <label class="form-label fw-semibold text-capitalize small">{key.replace(/([A-Z])/g, ' $1').trim()}</label>
                          <input type="text" class="form-control" bind:value={gateway.fields[key]} placeholder="API Anahtarı / Değer">
                        </div>
                      {/each}
                    </div>
                  </div>
                </div>
              </div>
            {/each}
          </div>
        </div>

      {:else if activeSettingsTab === 'currencies'}
        <div class="card-header bg-transparent py-3 border-bottom">
          <h5 class="mb-0 text-warning">Çoklu Para Birimi & Kur</h5>
        </div>
        <div class="card-body">
           <div class="alert alert-warning bg-opacity-10 border-warning text-body" role="alert">
             <i class="fa fa-info-circle me-2 text-warning"></i> Eğer ödemeleri farklı kur türlerinde (örn: hem TL hem EUR) almak istiyorsanız veya yurt dışı kullanıcıları için kur çevrimi lazımsa buradan sabit kurları belirleyebilirsiniz.
           </div>
           
           <div class="mb-4">
             <label class="form-label fw-bold">Ana (Base) Para Birimi</label>
             <select class="form-select w-auto">
               <option value="TRY" selected>TRY - Türk Lirası (₺)</option>
               <option value="USD">USD - Amerikan Doları ($)</option>
               <option value="EUR">EUR - Euro (€)</option>
             </select>
           </div>
           
           <hr>
           <h6 class="fw-bold mb-3">Dönüşüm Kurları</h6>
           <table class="table table-sm align-middle">
             <thead>
               <tr>
                 <th>Hedef Para Birimi</th>
                 <th>{`1 Base (TRY) = ? Hedef`}</th>
                 <th>Durum</th>
               </tr>
             </thead>
             <tbody>
               <tr>
                 <td class="fw-medium"><i class="fa fa-dollar-sign text-success me-2"></i>USD</td>
                 <td><input type="number" class="form-control form-control-sm w-50" value="0.031"></td>
                 <td>
                   <div class="form-check form-switch m-0">
                     <input class="form-check-input" type="checkbox" role="switch" checked>
                   </div>
                 </td>
               </tr>
               <tr>
                 <td class="fw-medium"><i class="fa fa-euro-sign text-primary me-2"></i>EUR</td>
                 <td><input type="number" class="form-control form-control-sm w-50" value="0.029"></td>
                 <td>
                   <div class="form-check form-switch m-0">
                     <input class="form-check-input" type="checkbox" role="switch">
                   </div>
                 </td>
               </tr>
             </tbody>
           </table>
           <button class="btn btn-sm btn-outline-secondary mt-2"><i class="fa fa-plus me-1"></i> Yeni Kur Ekle</button>
        </div>
      {/if}
    </div>
  </div>
</div>
</div>
