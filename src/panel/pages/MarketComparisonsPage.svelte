<script>
  import { _ } from '../../main';
  import MarketNav from '../components/MarketNav.svelte';
  import { CardHeader } from '@panomc/sdk/components/panel';

  let view = 'list';
  let comparisonTables = [
    { id: 1, name: 'VIP Paketleri Karşılaştırma', categories: ['VIP Üyelikler'], columns: 3 },
  ];
</script>

<div class="container vstack gap-3">
  <MarketNav />

  <div class="row">
    <div class="col-12">
      {#if view === 'list'}
        <div class="card shadow-sm border-0">
          <CardHeader>
            <div slot="left">Karşılaştırma Tabloları</div>
            <button slot="right" class="btn btn-primary btn-sm" on:click={() => (view = 'create')}>
              <i class="fa fa-plus me-1"></i> Yeni Tablo Oluştur
            </button>
          </CardHeader>
          <div class="card-body p-0">
            <div class="table-responsive">
              <table class="table table-hover align-middle mb-0">
                <thead>
                  <tr>
                    <th class="ps-4">Tablo Adı</th>
                    <th>Bağlı Kategoriler</th>
                    <th>Sütun Sayısı</th>
                    <th class="text-end pe-4">İşlemler</th>
                  </tr>
                </thead>
                <tbody>
                  {#if comparisonTables.length === 0}
                    <tr>
                      <td colspan="4" class="text-center py-4 text-muted">Henüz bir karşılaştırma tablosu oluşturulmadı.</td>
                    </tr>
                  {:else}
                    {#each comparisonTables as tb}
                      <tr>
                        <td class="ps-4 fw-medium"><i class="fa fa-table me-2 text-primary"></i>{tb.name}</td>
                        <td>{tb.categories.join(', ')}</td>
                        <td><span class="badge bg-secondary">{tb.columns}</span></td>
                        <td class="text-end pe-4">
                          <button class="btn btn-sm bg-body-secondary border text-primary tooltip-trigger" title="Tabloyu Düzenle">
                            <i class="fa fa-pen"></i> Düzenle
                          </button>
                        </td>
                      </tr>
                    {/each}
                  {/if}
                </tbody>
              </table>
            </div>
          </div>
          <div class="card-footer bg-transparent py-3 text-muted small">
            <i class="fa fa-info-circle me-1"></i>Karşılaştırma tabloları, birden fazla ürünü (örn. VIP, VIP+, MVP) yan yana özellik bazlı kıyaslamak için kullanılır.
          </div>
        </div>
      {:else if view === 'create'}
        <div class="card shadow-sm border-0">
          <CardHeader>
            <div slot="left"><i class="fa fa-table me-2 text-primary"></i>Karşılaştırma Tablosu Tasarla</div>
            <button slot="right" class="btn btn-sm bg-body-secondary border text-body" on:click={() => (view = 'list')}>
              <i class="fa fa-arrow-left me-1"></i> Geri Dön
            </button>
          </CardHeader>
          <div class="card-body">
            <div class="row mb-4">
              <div class="col-md-6 mb-3 mb-md-0">
                <label class="form-label fw-semibold" for="tableName">Tablo Başlığı</label>
                <input type="text" class="form-control" id="tableName" placeholder="VIP Paket Karşılaştırması">
              </div>
              <div class="col-md-6">
                <label class="form-label fw-semibold" for="categorySelect">Hangi Kategoride Gösterileceği</label>
                <select id="categorySelect" class="form-select">
                  <option value="">-- Kategori Seçin --</option>
                  <option value="1">VIP Üyelikler</option>
                </select>
                <div class="form-text">Bu tablo, seçilen kategorinin ürün listesinin hemen üstünde gösterilecektir.</div>
              </div>
            </div>

            <h6 class="fw-bold text-primary mb-3 border-bottom pb-2 mt-4">Özet Özellikler (Satırlar) ve Ürünler (Sütunlar)</h6>
            
            <div class="table-responsive mb-4 border rounded">
              <table class="table table-bordered mb-0">
                <thead>
                  <tr>
                    <th style="width: 250px;">Özellik Adı <button class="btn btn-sm text-success float-end p-0 tooltip-trigger" title="Satır Ekle"><i class="fa fa-plus"></i></button></th>
                    <th class="text-center">VIP <button class="btn btn-sm text-danger float-end p-0 tooltip-trigger" title="Sütunu Sil"><i class="fa fa-times"></i></button></th>
                    <th class="text-center">VIP+ <button class="btn btn-sm text-danger float-end p-0 tooltip-trigger" title="Sütunu Sil"><i class="fa fa-times"></i></button></th>
                    <th class="text-center">MVP <button class="btn btn-sm text-danger float-end p-0 tooltip-trigger" title="Sütunu Sil"><i class="fa fa-times"></i></button></th>
                    <th class="text-center bg-transparent" style="width:100px;"><button class="btn btn-sm btn-outline-primary"><i class="fa fa-plus me-1"></i> Sütun</button></th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <td><input type="text" class="form-control form-control-sm" value="Sunucuya Doluyken Giriş"></td>
                    <td class="text-center"><span class="d-flex justify-content-center align-items-center h-100 mt-2"><input type="checkbox" class="form-check-input mt-0" checked></span></td>
                    <td class="text-center"><span class="d-flex justify-content-center align-items-center h-100 mt-2"><input type="checkbox" class="form-check-input mt-0" checked></span></td>
                    <td class="text-center"><span class="d-flex justify-content-center align-items-center h-100 mt-2"><input type="checkbox" class="form-check-input mt-0" checked></span></td>
                    <td></td>
                  </tr>
                  <tr>
                    <td><input type="text" class="form-control form-control-sm" value="Özel Sohbet Rengi"></td>
                    <td class="text-center"><span class="d-flex justify-content-center align-items-center h-100 mt-2"><input type="checkbox" class="form-check-input mt-0"></span></td>
                    <td class="text-center"><span class="d-flex justify-content-center align-items-center h-100 mt-2"><input type="checkbox" class="form-check-input mt-0" checked></span></td>
                    <td class="text-center"><span class="d-flex justify-content-center align-items-center h-100 mt-2"><input type="checkbox" class="form-check-input mt-0" checked></span></td>
                    <td></td>
                  </tr>
                  <tr>
                    <td><input type="text" class="form-control form-control-sm" value="Haftalık Kit"></td>
                    <td class="text-center"><input type="text" class="form-control form-control-sm text-center" value="Elmas Set"></td>
                    <td class="text-center"><input type="text" class="form-control form-control-sm text-center" value="Özel Zırh"></td>
                    <td class="text-center"><input type="text" class="form-control form-control-sm text-center" value="Efsanevi Set"></td>
                    <td></td>
                  </tr>
                </tbody>
              </table>
            </div>

            <div class="text-end border-top pt-3">
              <button class="btn bg-body-secondary text-body border px-4 me-2" on:click={() => (view = 'list')}>Vazgeç</button>
              <button class="btn btn-primary px-4" on:click={() => (view = 'list')}><i class="fa fa-save me-1"></i> Tabloyu Kaydet</button>
            </div>
          </div>
        </div>
      {/if}
    </div>
  </div>
</div>
