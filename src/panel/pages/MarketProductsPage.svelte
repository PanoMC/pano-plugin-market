<script>
  import { _ } from '../../main';
  import MarketNav from '../components/MarketNav.svelte';
  import { CardHeader } from '@panomc/sdk/components/panel';

  let view = 'list'; // list | grid | create | details
  let selectedActionType = 'command';
  let selectedProduct = null;

  // Demo ürün listesi
  let products = [
    { id: 1, name: 'VIP Üyelik (Aylık)', price: 50.00, discountPrice: 35.00, category: 'VIP Üyelikler', stock: -1, image: 'fa-star' },
    { id: 2, name: '10 Adet Elmas', price: 15.00, discountPrice: null, category: 'Oyun İçi Eşyalar', stock: 100, image: 'fa-gem' },
    { id: 3, name: 'Efsanevi Kasa Anahtarı', price: 30.00, discountPrice: null, category: 'Kutular', stock: 50, image: 'fa-key' }
  ];

  function openDetails(product) {
    selectedProduct = product;
    view = 'details';
  }
</script>

<div class="container vstack gap-3">
  <MarketNav />

  {#if view === 'list' || view === 'grid'}
    <div class="card">
      <CardHeader>
        <div slot="left">Tüm Ürünler ({products.length})</div>
        <div slot="middle">
          <div class="btn-group">
            <button class="btn btn-sm text-secondary {view === 'list' ? 'bg-body-secondary' : 'bg-transparent border-0'}" on:click={() => (view = 'list')}><i class="fa fa-list"></i></button>
            <button class="btn btn-sm text-secondary {view === 'grid' ? 'bg-body-secondary' : 'bg-transparent border-0'}" on:click={() => (view = 'grid')}><i class="fa fa-th-large"></i></button>
          </div>
        </div>
        <div slot="right">
          <button class="btn btn-primary btn-sm" on:click={() => (view = 'create')}>
            <i class="fa fa-plus me-1"></i> Ürün Ekle
          </button>
        </div>
      </CardHeader>
      
      <div class="card-body p-0">
        {#if products.length === 0}
          <div class="text-center py-4 text-muted">Arama kriterlerine uygun ürün bulunamadı.</div>
        {:else if view === 'list'}
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead>
                <tr>
                  <th class="ps-4" style="width: 50px;">ID</th>
                  <th style="width: 60px;">Görsel</th>
                  <th>Ürün Adı</th>
                  <th>Kategori</th>
                  <th>Fiyat Seçenekleri</th>
                  <th>Stok Durumu</th>
                  <th class="text-end pe-4">İşlemler</th>
                </tr>
              </thead>
              <tbody>
                {#each products as product}
                  <tr>
                    <td class="ps-4 text-muted">#{product.id}</td>
                    <td>
                      <div class="bg-body-tertiary rounded d-flex align-items-center justify-content-center border" style="width: 40px; height: 40px;">
                        <i class="fa {product.image} text-primary"></i>
                      </div>
                    </td>
                    <td class="fw-medium">{product.name}</td>
                    <td>{product.category}</td>
                    <td>
                      {#if product.discountPrice}
                        <span class="text-danger fw-bold">{product.discountPrice.toFixed(2)} ₺</span>
                        <span class="text-muted text-decoration-line-through small ms-1">{product.price.toFixed(2)} ₺</span>
                      {:else}
                        <span class="fw-medium">{product.price.toFixed(2)} ₺</span>
                      {/if}
                    </td>
                    <td>
                      {#if product.stock === -1}
                        <span class="text-success"><i class="fa fa-infinity me-1"></i>Sınırsız</span>
                      {:else}
                        <span class="text-warning fw-medium">{product.stock} Adet Kaldı</span>
                      {/if}
                    </td>
                    <td class="text-end pe-4">
                      <button class="btn btn-sm bg-body-secondary border text-primary tooltip-trigger" title="Düzenle ve Detaylar" on:click={() => openDetails(product)}><i class="fa fa-search me-1"></i>Detay</button>
                      <button class="btn btn-sm bg-body-secondary border text-danger ms-1 tooltip-trigger" title="Sil"><i class="fa fa-trash"></i></button>
                    </td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        {:else if view === 'grid'}
          <div class="row g-3 p-4">
            {#each products as product}
              <div class="col-12 col-sm-6 col-lg-4 col-xl-3">
                <div class="card h-100 shadow-sm border-0 position-relative">
                  {#if product.discountPrice}
                    <div class="position-absolute top-0 end-0 m-2 badge bg-danger text-white rounded-pill px-2 py-1 z-1" style="font-size: 11px;">İNDİRİM</div>
                  {/if}
                  <div class="card-body text-center d-flex flex-column align-items-center justify-content-center py-4 bg-body-tertiary rounded-top">
                    <i class="fa {product.image} fa-3x text-primary mb-3"></i>
                    <h6 class="fw-bold mb-1">{product.name}</h6>
                    <span class="text-muted small">{product.category}</span>
                  </div>
                  <div class="card-footer bg-transparent d-flex justify-content-between align-items-center border-top">
                    <div>
                      {#if product.discountPrice}
                        <h6 class="fw-bold text-danger mb-0">{product.discountPrice.toFixed(2)} ₺</h6>
                      {:else}
                        <h6 class="fw-bold mb-0">{product.price.toFixed(2)} ₺</h6>
                      {/if}
                    </div>
                    <div>
                      <button class="btn btn-sm btn-outline-primary" on:click={() => openDetails(product)}><i class="fa fa-search"></i></button>
                    </div>
                  </div>
                </div>
              </div>
            {/each}
          </div>
        {/if}
      </div>
      <div class="card-footer bg-transparent py-3 d-flex justify-content-between align-items-center">
        <span class="text-muted small">Toplam {products.length} ürün listeleniyor.</span>
        <ul class="pagination pagination-sm m-0">
          <li class="page-item disabled"><a class="page-link" href="#">&laquo;</a></li>
          <li class="page-item active"><a class="page-link" href="#">1</a></li>
          <li class="page-item disabled"><a class="page-link" href="#">&raquo;</a></li>
        </ul>
      </div>
    </div>
  {:else if view === 'create'}
    <div class="row g-3">
      <div class="col-12 col-xl-8">
        <div class="card">
          <CardHeader>
            <div slot="left"><i class="fa fa-box-open me-2"></i>Ürün Oluştur</div>
            <div slot="right">
              <button class="btn btn-sm bg-body-secondary border text-body" on:click={() => (view = 'list')}><i class="fa fa-arrow-left me-1"></i>Geri</button>
            </div>
          </CardHeader>
          
          <div class="card-body">
            <h6 class="fw-bold mb-3 border-bottom pb-2">Temel Bilgiler</h6>
            <div class="row g-3 mb-4">
              <div class="col-md-6">
                <label class="form-label" for="productName">Ürün Adı*</label>
                <input type="text" class="form-control" id="productName" placeholder="Örn: 1 Aylık VIP">
              </div>
              <div class="col-md-6">
                <label class="form-label" for="productCategory">Bağlı Olduğu Kategori*</label>
                <select class="form-select" id="productCategory">
                  <option value="1">VIP Üyelikler</option>
                  <option value="2">Kutular</option>
                </select>
              </div>
            </div>

            <h6 class="fw-bold mb-3 border-bottom pb-2 mt-4 text-danger"><i class="fa fa-percent me-2"></i>Fiyatlandırma & İndirim (YENİ)</h6>
            <div class="row g-3 mb-4">
              <div class="col-md-4">
                <label class="form-label" for="productPrice">Satış Fiyatı*</label>
                <div class="input-group">
                  <input type="number" class="form-control" id="productPrice" placeholder="50.00">
                  <span class="input-group-text">₺</span>
                </div>
              </div>
              <div class="col-md-4">
                <label class="form-label text-danger fw-semibold" for="productDiscount">İndirimli Fiyat</label>
                <div class="input-group">
                  <input type="number" class="form-control border-danger" id="productDiscount" placeholder="Veya boş bırak">
                  <span class="input-group-text border-danger bg-danger text-white">₺</span>
                </div>
              </div>
              <div class="col-md-4">
                <label class="form-label" for="productDiscountDate">İndirim Bitiş Tarihi</label>
                <input type="datetime-local" class="form-control" id="productDiscountDate">
              </div>
            </div>

            <h6 class="fw-bold mb-3 border-bottom pb-2 mt-4 text-success">Satın Alındığında Yapılacaklar (Oyun Entegrasyonu)</h6>
            <div class="mb-3">
              <label class="form-label" for="actionType">Tetikleyici Türü</label>
              <select class="form-select w-50" id="actionType" bind:value={selectedActionType}>
                <option value="command">Minecraft Sunucusunda Komut Çalıştır</option>
                <option value="event">Pano Event Fırlat (Plugin Geliştiricileri İçin)</option>
              </select>
            </div>

            <div class="bg-body-tertiary p-3 rounded mb-4 border">
              {#if selectedActionType === 'command'}
                <div class="mb-3">
                  <label class="form-label fw-semibold" for="actionCommand">Çalışacak Komut</label>
                  <div class="input-group">
                    <span class="input-group-text bg-body">/</span>
                    <input type="text" class="form-control" placeholder="lp user &#123;player&#125; parent add vip">
                  </div>
                </div>
                <div class="mb-0">
                  <label class="form-label fw-semibold" for="targetServer">Hedef Sunucu</label>
                  <select class="form-select" id="targetServer">
                    <option value="all">Herhangi bir sunucu (Girdiğinde)</option>
                  </select>
                </div>
              {:else if selectedActionType === 'event'}
                <div class="mb-0">
                  <label class="form-label fw-semibold" for="actionEvent">Event Adı</label>
                  <input type="text" class="form-control font-monospace" placeholder="custom_publish_event">
                </div>
              {/if}
            </div>

            <div class="text-end mt-4 pt-3 border-top">
              <button class="btn bg-body-secondary text-body border px-4 me-2" on:click={() => (view = 'list')}>İptal</button>
              <button class="btn btn-primary px-4">Kaydet</button>
            </div>
          </div>
        </div>
      </div>

      <div class="col-12 col-xl-4">
        <div class="card mb-3">
          <CardHeader>
             <div slot="left"><i class="fa fa-image me-2 text-muted"></i>Görünüm Ayarları</div>
          </CardHeader>
          <div class="card-body">
            <div class="text-center mb-3">
              <div class="bg-body-tertiary border rounded d-flex align-items-center justify-content-center mx-auto mb-2" style="width: 100px; height: 100px;">
                <i class="fa fa-upload text-muted fa-2x"></i>
              </div>
              <button class="btn btn-sm btn-outline-secondary">Resim Yükle</button>
            </div>
          </div>
        </div>
      </div>
    </div>
  {:else if view === 'details' && selectedProduct}
    <div class="row g-3">
      <div class="col-12 col-xl-4">
        <div class="card">
          <div class="card-body text-center p-5">
            <div class="bg-body-tertiary rounded-circle d-flex align-items-center justify-content-center mx-auto mb-4 border" style="width: 120px; height: 120px;">
              <i class="fa {selectedProduct.image} fa-4x text-primary"></i>
            </div>
            <h4 class="fw-bold mb-1">{selectedProduct.name}</h4>
            <span class="badge bg-primary bg-opacity-10 text-primary border border-primary border-opacity-25 mb-3">{selectedProduct.category}</span>
            <div class="d-flex flex-column gap-2 text-start mt-4 pt-4 border-top">
              <div class="d-flex justify-content-between">
                <span class="text-muted">Fiyat:</span>
                <span class="fw-bold">{selectedProduct.price.toFixed(2)} ₺</span>
              </div>
              <div class="d-flex justify-content-between">
                <span class="text-muted">İndirimli:</span>
                {#if selectedProduct.discountPrice}
                  <span class="fw-bold text-danger">{selectedProduct.discountPrice.toFixed(2)} ₺</span>
                {:else}
                  <span class="text-muted">-</span>
                {/if}
              </div>
              <div class="d-flex justify-content-between mt-2 pt-2 border-top">
                <span class="text-muted">Stok Durumu:</span>
                {#if selectedProduct.stock === -1}
                  <span class="text-success"><i class="fa fa-infinity me-1"></i>Sınırsız</span>
                {:else}
                  <span class="text-warning fw-bold">{selectedProduct.stock} Adet Kaldı</span>
                {/if}
              </div>
            </div>
          </div>
        </div>
      </div>
      <div class="col-12 col-xl-8">
        <div class="card h-100">
          <CardHeader>
            <div slot="left"><i class="fa fa-info-circle me-2"></i>Ürün Detayları ve Yönetimi</div>
            <div slot="right">
              <button class="btn btn-sm bg-body-secondary border text-body" on:click={() => (view = 'list')}><i class="fa fa-arrow-left me-1"></i>Listeye Dön</button>
              <button class="btn btn-sm btn-primary ms-1"><i class="fa fa-pen me-1"></i>Düzenle</button>
            </div>
          </CardHeader>
          <div class="card-body">
            <h6 class="fw-bold mb-3 border-bottom pb-2">Oyun İçi Aksiyonlar Tablosu</h6>
            <div class="table-responsive border rounded">
              <table class="table table-sm table-hover mb-0">
                <thead>
                  <tr>
                    <th>Tetikleyici Olay</th>
                    <th>Tür</th>
                    <th>Değer (Komut/Event)</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td class="text-success"><i class="fa fa-shopping-cart me-2"></i>Satın Alındığında</td>
                    <td>Komut (all)</td>
                    <td class="font-monospace text-muted">/lp user &#123;player&#125; parent add vip</td>
                  </tr>
                  <tr>
                    <td class="text-danger"><i class="fa fa-clock me-2"></i>Süresi Bittiğinde</td>
                    <td>Komut (all)</td>
                    <td class="font-monospace text-muted">/lp user &#123;player&#125; parent remove vip</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </div>
      </div>
    </div>
  {/if}
</div>
