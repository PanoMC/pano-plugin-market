<script>
  import { _ } from '../../main';
  import MarketNav from '../components/MarketNav.svelte';
  import { CardHeader } from '@panomc/sdk/components/panel';

  // Demo kategori verisi
  let categories = [
    { id: 1, name: 'VIP Üyelikler', icon: 'fa-star', count: 4, order: 1 },
    { id: 2, name: 'Kutular', icon: 'fa-box', count: 12, order: 2 },
    { id: 3, name: 'Oyun İçi Eşyalar', icon: 'fa-gem', count: 8, order: 3 },
  ];

  let view = 'list'; // list | create
</script>

<div class="container vstack gap-3">
  <MarketNav />

  <div class="row">
    <div class="col-12 col-xl-8">
      <div class="card h-100">
        <CardHeader>
          <div slot="left">Mevcut Kategoriler</div>
          <div slot="right">
            <button class="btn btn-sm btn-primary" on:click={() => (view = 'create')}>
              <i class="fa fa-plus me-1"></i> Yeni Kategori
            </button>
          </div>
        </CardHeader>
        
        <div class="card-body p-0">
          <div class="table-responsive">
            <table class="table table-hover align-middle mb-0">
              <thead>
                <tr>
                  <th style="width: 50px;" class="ps-4">#</th>
                  <th style="width: 50px;">İkon</th>
                  <th>Kategori Adı</th>
                  <th>Ürün Sayısı</th>
                  <th class="text-end pe-4">İşlemler</th>
                </tr>
              </thead>
              <tbody>
                {#if categories.length === 0}
                  <tr><td colspan="5" class="text-center py-4 text-muted">Kayıtlı kategori yok.</td></tr>
                {/if}
                {#each categories.sort((a,b) => a.order - b.order) as cat}
                  <tr>
                    <td class="ps-4 text-muted">
                      <i class="fa fa-grip-vertical me-2" style="cursor: grab;"></i>
                    </td>
                    <td>
                      <div class="bg-body-tertiary rounded d-flex align-items-center justify-content-center border" style="width: 36px; height: 36px;">
                        <i class="fa {cat.icon} text-primary"></i>
                      </div>
                    </td>
                    <td class="fw-medium">{cat.name}</td>
                    <td>
                      <span class="badge bg-secondary">{cat.count} Ürün</span>
                    </td>
                    <td class="text-end pe-4">
                      <button class="btn btn-sm bg-body-secondary border text-primary tooltip-trigger" title="Düzenle"><i class="fa fa-pen"></i></button>
                      <button class="btn btn-sm bg-body-secondary border text-danger ms-1 tooltip-trigger" title="Sil"><i class="fa fa-trash"></i></button>
                    </td>
                  </tr>
                {/each}
              </tbody>
            </table>
          </div>
        </div>
        <div class="card-footer bg-transparent py-3 text-muted small">
          <i class="fa fa-info-circle me-1"></i>Kategorilerin sırasını değiştirmek için sol taraftaki ikonundan tutarak sürükleyebilirsiniz.
        </div>
      </div>
    </div>

    <div class="col-12 col-xl-4 mt-4 mt-xl-0">
      {#if view === 'create'}
        <div class="card bg-body-tertiary border-0 animation-fadeIn">
          <CardHeader>
            <div slot="left"><i class="fa fa-folder-plus text-primary me-2"></i>Kategori Oluştur</div>
          </CardHeader>
          <div class="card-body">
            <div class="mb-3">
              <label class="form-label" for="catName">Kategori İsmi</label>
              <input type="text" class="form-control" id="catName" placeholder="örn: Kasalar">
            </div>
            <div class="mb-3">
              <label class="form-label d-block" for="catIcon">FontAwesome İkonu</label>
              <div class="input-group">
                <span class="input-group-text"><i class="fa fa-folder"></i></span>
                <input type="text" class="form-control" id="catIcon" placeholder="fa-box">
              </div>
              <div class="form-text mt-1">Örn: fa-gem, fa-star, fa-box</div>
            </div>
            <div class="mb-4">
              <label class="form-label" for="catDesc">Açıklama (Opsiyonel)</label>
              <textarea class="form-control" id="catDesc" rows="2" placeholder="Market arayüzünde alt açıklama olarak görünür..."></textarea>
            </div>
            
            <div class="d-flex justify-content-end gap-2">
              <button class="btn bg-body-secondary text-body border" on:click={() => (view = 'list')}>İptal</button>
              <button class="btn btn-primary"><i class="fa fa-save me-1"></i> Ekle</button>
            </div>
          </div>
        </div>
      {:else}
        <div class="card border-0 bg-primary text-white bg-opacity-10 h-100">
          <div class="card-body d-flex flex-column align-items-center justify-content-center text-center p-4">
             <i class="fa fa-layer-group fa-3x text-primary mb-3"></i>
             <h5 class="text-primary fw-bold">Kategori Yönetimi</h5>
             <p class="text-body opacity-75 small mb-0 mt-2">
               Marketinizdeki ürünleri gruplamak için kategoriler oluşturun. Sol listedeki panel üzerinden ürünlerinizi kategorize edebilirsiniz.
             </p>
          </div>
        </div>
      {/if}
    </div>
  </div>
</div>
