<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { Editor, DragAndDropZone, NoContent } from '@panomc/sdk/components/panel';
  import IconPicker from '../components/IconPicker.svelte';
  import ProductSelector from '../components/ProductSelector.svelte';

  let product = $state({
    id: '',
    name: '',
    description: '',
    type: 'Süreli',
    category: -1,
    price: 0,
    creditPrice: 0,
    hasDiscount: false,
    discountPrice: 0,
    discountDuration: 'Lifetime',
    discountStart: '',
    discountExpiry: '',
    hasStockLimit: false,
    stock: 0,
    requiredProducts: [],
    requireOnlyOne: false,
    status: 'active',
    featured: false,
    durationStatus: 'Lifetime',
    durationStart: '',
    durationExpiry: '',
    permission: '',
    priority: 0,
    actions: [],
    image: null,
    icon: 'fa-box'
  });

  let selectedFile = $state(null);
  let previewUrl = $state(null);
  let fileInput;
  let hasPermission = $state(product.permission !== '');
  let hasRequiredProducts = $state(product.requiredProducts.length > 0);

  let activeTab = $state('general');
  let isDirty = $state(false);

  function slugify(text) {
    const trMap = {
      'ç': 'c', 'Ç': 'c',
      'ğ': 'g', 'Ğ': 'g',
      'ı': 'i', 'I': 'i', 'İ': 'i',
      'ö': 'o', 'Ö': 'o',
      'ş': 's', 'Ş': 's',
      'ü': 'u', 'Ü': 'u'
    };
    let slug = text || '';
    for (const key in trMap) {
      slug = slug.replace(new RegExp(key, 'g'), trMap[key]);
    }
    return slug
      .toLowerCase()
      .replace(/[^a-z0-9 -]/g, '')
      .replace(/\s+/g, '-')
      .replace(/-+/g, '-');
  }

  let oldSlug = '';
  $effect(() => {
    const currentName = product.name;
    const currentId = product.id;
    if (!currentId || currentId === oldSlug) {
      const newSlug = slugify(currentName);
      product.id = newSlug;
      oldSlug = newSlug;
    }
  });

  let initialProduct = JSON.stringify(product);
  $effect(() => {
    if (JSON.stringify(product) !== initialProduct || selectedFile) {
      isDirty = true;
    }
  });

  const tabs = [
    { id: 'general', label: 'Genel' },
    { id: 'pricing', label: 'Fiyatlandırma' },
    { id: 'restrictions', label: 'Kısıtlamalar' },
    { id: 'actions', label: 'Aksiyonlar' }
  ];

  function addAction(type) {
    const newAction = {
      id: Date.now(),
      type: type, // 'credit', 'permission', 'command'
      value: (type === 'permission' || type === 'command') ? [] : '',
      currentInput: '',
      delay: type === 'command' ? 0 : undefined,
      targetServers: type === 'command' ? [] : undefined
    };
    product.actions = [...product.actions, newAction];
    isDirty = true;
    
    // Close modal if using bootstrap JS
    const modalElement = document.getElementById('addActionModal');
    if (typeof bootstrap !== 'undefined') {
      const modal = bootstrap.Modal.getInstance(modalElement);
      if (modal) modal.hide();
    }
  }

  function toggleActionServer(action, serverId) {
    action.targetServers = action.targetServers || [];
    if (action.targetServers.includes(serverId)) {
      action.targetServers = action.targetServers.filter(id => id !== serverId);
    } else {
      action.targetServers = [...action.targetServers, serverId];
    }
    isDirty = true;
  }

  // Mock servers
  const servers = [
    { id: 1, name: 'Survival #1' },
    { id: 2, name: 'Creative' },
    { id: 3, name: 'Skyblock' },
    { id: 4, name: 'Lobi' }
  ];

  function addArrayItem(action, event) {
    if (event.key === 'Enter' && action.currentInput.trim()) {
      event.preventDefault();
      if (!action.value.includes(action.currentInput.trim())) {
        action.value = [...action.value, action.currentInput.trim()];
        action.currentInput = '';
        isDirty = true;
      }
    }
  }

  function removeArrayItem(action, item) {
    action.value = action.value.filter(n => n !== item);
    isDirty = true;
  }

  function addPermissionNode(action, event) { addArrayItem(action, event); }
  function removePermissionNode(action, node) { removeArrayItem(action, node); }
  function addCommand(action, event) { addArrayItem(action, event); }
  function removeCommand(action, command) { removeArrayItem(action, command); }

  function getActionLabel(type) {
    switch(type) {
      case 'credit': return 'Kredi Yükle';
      case 'permission': return 'Yetkilendir';
      case 'command': return 'Komut Çalıştır';
      default: return 'Aksiyon';
    }
  }

  function getActionIcon(type) {
    switch(type) {
      case 'credit': return 'fas fa-coins text-warning';
      case 'permission': return 'fas fa-gavel text-info';
      case 'command': return 'fas fa-terminal text-secondary';
      default: return 'fas fa-bolt';
    }
  }

  function removeAction(id) {
    product.actions = product.actions.filter(a => a.id !== id);
    isDirty = true;
  }

  // Mock categories (in real app these would be fetched)
  const categories = [
    { id: 1, name: 'VIP Üyelikler' },
    { id: 2, name: 'Kredi Paketleri' },
    { id: 3, name: 'Kasa Anahtarları' },
    { id: 4, name: 'Özel Eşyalar' },
    { id: 5, name: 'Kozmetik Ürünler' }
  ];

  // Mock products for requirements
  const allProducts = [
    { id: 101, name: 'VIP Başlangıç Paketi' },
    { id: 102, name: 'Kredi Cüzdanı' },
    { id: 103, name: 'Özel Kozmetik Seti' },
    { id: 104, name: 'Sınırsız Yetki Belgesi' }
  ];

  function toggleProduct(id) {
    if (product.requiredProducts.includes(id)) {
      product.requiredProducts = product.requiredProducts.filter(p => p !== id);
    } else {
      product.requiredProducts = [...product.requiredProducts, id];
    }
    isDirty = true;
  }

  function handleSave() {
    console.log('Ürün kaydediliyor:', product);
    isDirty = false;
    initialProduct = JSON.stringify(product);
  }

  function processFile(file) {
    selectedFile = file;
    isDirty = true;
    const reader = new FileReader();
    reader.onload = (e) => {
      previewUrl = e.target.result;
    };
    reader.readAsDataURL(file);
  }

  function onRemoveImage() {
    selectedFile = null;
    previewUrl = null;
    isDirty = true;
    if (fileInput) fileInput.value = '';
  }

  function onFileChange(event) {
    const file = event.target.files[0];
    if (file) {
      processFile(file);
    }
  }
</script>

<MarketLayout>
  {#snippet left()}
    <div class="d-flex align-items-center gap-4">
      <a href="/panel/market/products" class="btn btn-link text-decoration-none p-0">
        <i class="fas fa-arrow-left"></i>
        <span class="ms-2">Ürünler</span>
      </a>

      <ul class="nav nav-pills">
        {#each tabs as tab}
          <li class="nav-item">
            <button 
              class="nav-link {activeTab === tab.id ? 'active' : ''}" 
              onclick={() => activeTab = tab.id}>
              {tab.label}
            </button>
          </li>
        {/each}
      </ul>
    </div>
  {/snippet}

  {#snippet right()}
    <div class="hstack gap-1">
      <button class="btn btn-link link-danger" title="Kaldır">
        <i class="fas fa-trash"></i>
      </button>
      <button class="btn btn-link" title="Ön İzle">
        <i class="fas fa-eye"></i>
      </button>
      {#if activeTab === 'actions'}
        <button 
          class="btn btn-link" 
          title="Aksiyon Ekle" 
          data-bs-toggle="modal" 
          data-bs-target="#addActionModal">
          <i class="fas fa-plus"></i>
        </button>
      {/if}
      <button class="btn btn-secondary ms-2" onclick={handleSave} disabled={!isDirty}>
        <i class="fas fa-save"></i>
        <span class="d-lg-inline d-none ms-2">Kaydet</span>
      </button>
    </div>
  {/snippet}

  {#if activeTab === 'general' || activeTab === 'pricing' || activeTab === 'restrictions' || activeTab === 'actions'}
    <section class="row g-3">
      <!-- Ana Sütun -->
      <div class="col-lg-8">
        {#if activeTab === 'general'}
          <div class="card h-100 w-100 animate__animated animate__fadeIn">
            <div class="card-body d-flex flex-column gap-3">
              <input
                type="text"
                class="form-control form-control-lg"
                id="productName"
                placeholder="Ürün Başlığı"
                bind:value={product.name} />

              <div class="form-floating mb-0">
                <input
                  type="text"
                  class="form-control font-monospace"
                  id="productId"
                  placeholder="urun-id-ornek"
                  bind:value={product.id} />
                <label for="productId">Ürün ID</label>
              </div>

              <div class="w-100 flex-grow-1 d-flex flex-column">
                <Editor id="product-description" bind:content={product.description} />
              </div>
            </div>
          </div>
        {:else if activeTab === 'pricing'}
          <div class="card animate__animated animate__fadeIn">
            <div class="card-body p-4">
              <!-- Normal Fiyat -->
              <div class="row mb-3 align-items-center">
                <label class="col-sm-3 col-form-label" for="p-price">Fiyat</label>
                <div class="col-sm-9">
                  <div class="input-group">
                    <input type="number" id="p-price" class="form-control" placeholder="0.00" bind:value={product.price} />
                    <span class="input-group-text">₺</span>
                  </div>
                </div>
              </div>

              <!-- Kredi Fiyatı -->
              <div class="row mb-3 align-items-center">
                <label class="col-sm-3 col-form-label" for="p-credit-price">Kredi Fiyatı</label>
                <div class="col-sm-9">
                  <div class="input-group">
                    <input type="number" id="p-credit-price" class="form-control" placeholder="0" bind:value={product.creditPrice} />
                    <span class="input-group-text"><i class="fas fa-coins text-warning me-2"></i> Kredi</span>
                  </div>
                  <div class="form-text small mt-1 text-body-secondary">
                    Ürünün oyun içi kredi ile satın alınma bedeli. Boş veya 0 bırakılırsa krediyle satın alınamaz.
                  </div>
                </div>
              </div>
            </div>
          </div>
        {:else if activeTab === 'restrictions'}
          <div class="card animate__animated animate__fadeIn">
            <div class="card-body p-4">
              <!-- Stok Switch -->
              <div class="row mb-3 align-items-center">
                <label class="col-sm-3 col-form-label" for="p-stock-switch">Stok</label>
                <div class="col-sm-9">
                  <div class="form-check form-switch m-0">
                    <input 
                      class="form-check-input" 
                      type="checkbox" 
                      role="switch" 
                      id="p-stock-switch" 
                      bind:checked={product.hasStockLimit} />
                  </div>
                </div>
              </div>

              {#if product.hasStockLimit}
                <!-- Stok Adeti -->
                <div class="row mb-3 align-items-center animate__animated animate__fadeInDown animate__faster">
                  <label class="col-sm-3 col-form-label" for="p-stock-amount">Stok Adeti</label>
                  <div class="col-sm-9">
                    <input 
                      type="number" 
                      id="p-stock-amount" 
                      class="form-control" 
                      placeholder="0" 
                      bind:value={product.stock} />
                  </div>
                </div>
              {/if}

              <hr class="my-4 opacity-25" />

              <!-- Gerekli Ürünler Switch -->
              <div class="row mb-3 align-items-center">
                <label class="col-sm-3 col-form-label" for="p-required-switch">Ürün Gereksinimi</label>
                <div class="col-sm-9">
                  <div class="form-check form-switch m-0">
                    <input 
                      class="form-check-input" 
                      type="checkbox" 
                      role="switch" 
                      id="p-required-switch" 
                      bind:checked={hasRequiredProducts}
                      onchange={(e) => {
                        if (!e.target.checked) {
                          product.requiredProducts = [];
                          product.requireOnlyOne = false;
                        }
                      }} />
                  </div>
                </div>
              </div>

              {#if hasRequiredProducts}
                <div class="animate__animated animate__fadeInDown animate__faster">
                  <!-- Gerekli Ürün Listesi -->
                  <div class="row mb-3">
                    <label class="col-sm-3 col-form-label">Gerekli Ürünler</label>
                    <div class="col-sm-9">
                      <ProductSelector products={allProducts} bind:selected={product.requiredProducts} multiple={true} />
                      <div class="form-text small mt-2">
                        Bu ürünün satın alınabilmesi için müşterinin yukarıda seçilen ürünlere sahip olması gerekir.
                      </div>
                    </div>
                  </div>

                  <!-- Tek Ürün Şartı -->
                  <div class="row mb-3 align-items-center">
                    <label class="col-sm-3 col-form-label" for="p-require-one">Tek Ürün Yeterliliği</label>
                    <div class="col-sm-9">
                      <div class="form-check form-switch m-0 d-flex align-items-center gap-2">
                        <input 
                          class="form-check-input" 
                          type="checkbox" 
                          role="switch" 
                          id="p-require-one" 
                          bind:checked={product.requireOnlyOne} />
                        <span>
                          Seçili listeden en az bir ürünün satın alınmış olması yeterlidir.
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              {/if}

              <hr class="my-4 opacity-25" />

              <!-- Yetki Gereksinimi -->
              <div class="row mb-3 align-items-center">
                <label class="col-sm-3 col-form-label" for="p-permission-switch">Yetki Gereksinimi</label>
                <div class="col-sm-9">
                  <div class="form-check form-switch m-0">
                    <input 
                      class="form-check-input" 
                      type="checkbox" 
                      role="switch" 
                      id="p-permission-switch" 
                      bind:checked={hasPermission}
                      onchange={(e) => {
                        if (!e.target.checked) product.permission = '';
                      }} />
                  </div>
                </div>
              </div>

              {#if hasPermission}
                <div class="row mb-3 align-items-center animate__animated animate__fadeInDown animate__faster">
                  <label class="col-sm-3 col-form-label" for="p-permission-node">Yetki Node / Grubu</label>
                  <div class="col-sm-9">
                    <input 
                      type="text" 
                      id="p-permission-node"
                      class="form-control" 
                      placeholder="Örn: vip.group veya essentials.fly" 
                      bind:value={product.permission} />
                  </div>
                </div>
              {/if}
            </div>
          </div>
        {:else if activeTab === 'actions'}
          <div class="animate__animated animate__fadeIn">
            {#if product.actions.length > 0}
              <div class="accordion mb-3" id="actionsAccordion">
                {#each product.actions as action, index}
                  <div class="accordion-item card mb-2 border">
                    <h2 class="accordion-header d-flex align-items-center">
                      <button 
                        class="accordion-button {index === 0 ? '' : 'collapsed'} bg-transparent fw-medium flex-grow-1" 
                        type="button" 
                        data-bs-toggle="collapse" 
                        data-bs-target="#collapse-{action.id}">
                        <i class="{getActionIcon(action.type)} me-2 opacity-75"></i>
                        {getActionLabel(action.type)}
                        {#if action.value}
                          {#if Array.isArray(action.value)}
                            {#if action.value.length > 0}
                              <span class="ms-2 badge text-bg-secondary small fw-normal border">
                                {action.value.length}
                              </span>
                            {/if}
                          {:else if action.value}
                            <span class="ms-2 badge text-bg-secondary small fw-normal border text-truncate" style="max-width: 150px;">{action.value}</span>
                          {/if}
                        {/if}
                      </button>
                      <button 
                        class="btn btn-link link-danger px-3 py-0 border-0" 
                        title="Aksiyonu Sil"
                        onclick={(e) => { e.stopPropagation(); removeAction(action.id); }}>
                        <i class="fas fa-trash-can small"></i>
                      </button>
                    </h2>
                    <div 
                      id="collapse-{action.id}" 
                      class="accordion-collapse collapse {index === 0 ? 'show' : ''}" 
                      data-bs-parent="#actionsAccordion">
                      <div class="accordion-body p-4">
                        {#if action.type === 'credit'}
                          <div class="row mb-0 align-items-center">
                            <label class="col-sm-3 col-form-label" for="action-val-{action.id}">Miktar</label>
                            <div class="col-sm-9">
                              <input 
                                type="number" 
                                id="action-val-{action.id}" 
                                class="form-control" 
                                placeholder="0" 
                                bind:value={action.value} />
                            </div>
                          </div>
                        {:else if action.type === 'permission'}
                          <div class="row mb-0">
                            <label class="col-sm-3 col-form-label" for="action-val-{action.id}">Yetki Node</label>
                            <div class="col-sm-9">
                              <input 
                                type="text" 
                                id="action-val-{action.id}" 
                                class="form-control font-monospace mb-2" 
                                placeholder="Yetki yazın ve Enter'a basın..." 
                                bind:value={action.currentInput}
                                onkeydown={(e) => addPermissionNode(action, e)} />
                              
                              <div class="d-flex flex-wrap gap-1">
                                {#each action.value as node}
                                  <span class="badge rounded-pill text-bg-primary d-flex align-items-center gap-2 py-2 px-3">
                                    <span class="font-monospace small">{node}</span>
                                    <i 
                                      class="fas fa-xmark cursor-pointer opacity-75 hover-opacity-100" 
                                      role="button" 
                                      tabindex="0"
                                      onclick={() => removePermissionNode(action, node)}
                                      onkeydown={(e) => e.key === 'Enter' && removePermissionNode(action, node)}></i>
                                  </span>
                                {/each}
                              </div>
                            </div>
                          </div>
                        {:else if action.type === 'command'}
                          <!-- Hedef Sunucu -->
                          <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="action-server-{action.id}">Hedef Sunucu</label>
                            <div class="col-sm-9">
                              <div class="list-group list-group-flush border rounded overflow-y-auto mb-0" style="max-height: 150px;">
                                {#each servers as server}
                                  <label class="list-group-item d-flex align-items-center gap-3 py-2 cursor-pointer list-group-item-action">
                                    <input 
                                      class="form-check-input flex-shrink-0 mt-0 cursor-pointer" 
                                      type="checkbox" 
                                      checked={(action.targetServers || []).includes(server.id)} 
                                      onclick={() => toggleActionServer(action, server.id)}>
                                    <span class="fw-medium text-truncate">{server.name}</span>
                                  </label>
                                {/each}
                              </div>
                              <div class="form-text mt-2">
                                Komutların çalıştırılacağı sunucuları seçin.
                              </div>
                            </div>
                          </div>

                          <!-- Komutlar -->
                          <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="action-val-{action.id}">Komut</label>
                            <div class="col-sm-9">
                              <input 
                                type="text" 
                                id="action-val-{action.id}" 
                                class="form-control mb-2" 
                                placeholder="Komut yazın ve Enter'a basın..." 
                                bind:value={action.currentInput}
                                onkeydown={(e) => addCommand(action, e)} />
                              
                              <div class="form-text small mt-0 mb-3">
                                Değişkenler: <code>{'{player}'}</code>, <code>{'{product}'}</code>
                              </div>
                              
                              <div class="d-flex flex-wrap gap-1">
                                {#each action.value as cmd}
                                  <span class="badge rounded-pill text-bg-primary d-flex align-items-center gap-2 py-2 px-3">
                                    <span class="small">{cmd}</span>
                                    <i 
                                      class="fas fa-xmark cursor-pointer opacity-75 hover-opacity-100" 
                                      role="button" 
                                      tabindex="0"
                                      onclick={() => removeCommand(action, cmd)}
                                      onkeydown={(e) => e.key === 'Enter' && removeCommand(action, cmd)}></i>
                                  </span>
                                {/each}
                              </div>
                            </div>
                          </div>

                          <!-- Gecikme -->
                          <div class="row mb-0 align-items-center">
                            <label class="col-sm-3 col-form-label" for="action-delay-{action.id}">Gecikme</label>
                            <div class="col-sm-9">
                              <div class="input-group">
                                <input 
                                  type="number" 
                                  id="action-delay-{action.id}" 
                                  class="form-control" 
                                  placeholder="0" 
                                  bind:value={action.delay} />
                                <span class="input-group-text bg-transparent small">saniye</span>
                              </div>
                            </div>
                          </div>
                        {/if}
                      </div>
                    </div>
                  </div>
                {/each}
              </div>
            {:else}
              <NoContent />
            {/if}
          </div>
        {/if}
      </div>

      <!-- Yan Sütun: Özellikler ve Ayarlar -->
      <div class="col-lg-4">
        <div class="card">
          <div class="card-body">
            <ul class="list-group p-0 m-0">
              <!-- Görsel Yükleme -->
              <li class="list-group-item p-2">
                {#if previewUrl}
                  <div class="position-relative w-100">
                    <div
                      class="rounded border d-flex align-items-center justify-content-center bg-body-tertiary position-relative overflow-hidden"
                      style="aspect-ratio: 1/1; cursor: pointer;"
                      role="button"
                      tabindex="0"
                      onclick={() => fileInput.click()}
                      onkeydown={(e) => e.key === 'Enter' && fileInput.click()}>
                      <img
                        src={previewUrl}
                        alt="Ürün Önizleme"
                        class="w-100 h-100 object-fit-cover" />
                    </div>
                    <button
                      type="button"
                      class="btn btn-sm btn-danger position-absolute top-0 start-100 translate-middle rounded-circle shadow-sm"
                      style="z-index: 10; width: 24px; height: 24px; padding: 0;"
                      onclick={onRemoveImage}>
                      <i class="fas fa-minus small"></i>
                    </button>
                  </div>
                {:else}
                  <DragAndDropZone
                    style="aspect-ratio: 1/1;"
                    icon="fas fa-image fa-3x"
                    title="Ürün Görseli"
                    accept={['image/png', 'image/jpeg', 'image/gif', 'image/webp']}
                    on:drop={(e) => processFile(e.detail)} />
                {/if}
                <input
                  type="file"
                  class="d-none"
                  accept="image/png,image/jpeg,image/gif,image/webp"
                  onchange={onFileChange}
                  bind:this={fileInput} />
              </li>

              <!-- Durum Seçimi -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">Durum</div>
                  <div class="col-6 d-flex justify-content-end align-items-center gap-2">
                    <span>
                      {product.status === 'active' ? 'Aktif' : 'Pasif'}
                    </span>
                    <div class="form-check form-switch m-0">
                      <input 
                        class="form-check-input" 
                        type="checkbox" 
                        role="switch" 
                        id="productStatusSwitch" 
                        checked={product.status === 'active'}
                        onchange={(e) => product.status = e.target.checked ? 'active' : 'inactive'} />
                    </div>
                  </div>
                </div>
              </li>

              <!-- Öne Çıkarılan Ürün -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">Öne Çıkarılan</div>
                  <div class="col-6 d-flex justify-content-end">
                    <div class="form-check form-switch m-0">
                      <input 
                        class="form-check-input" 
                        type="checkbox" 
                        role="switch" 
                        id="productFeaturedSwitch" 
                        bind:checked={product.featured} />
                    </div>
                  </div>
                </div>
              </li>

              <!-- Kategori Seçimi -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">Kategori</div>
                  <div class="col-6">
                    <select class="form-select form-select-sm" bind:value={product.category}>
                      <option value={-1} selected>Kategorisiz</option>
                      {#each categories as cat}
                        <option value={cat.id}>{cat.name}</option>
                      {/each}
                    </select>
                  </div>
                </div>
              </li>

              <!-- İkon Seçimi -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">İkon</div>
                  <div class="col-6">
                    <IconPicker bind:value={product.icon} color="#0d6efd" placement="top-end" />
                  </div>
                </div>
              </li>

              <!-- Süre Durumu -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">Süre</div>
                  <div class="col-6 d-flex justify-content-end">
                    <div class="form-check form-switch m-0">
                      <input 
                        class="form-check-input" 
                        type="checkbox" 
                        role="switch" 
                        id="productDurationSwitch" 
                        checked={product.durationStatus === 'Temporary'}
                        onchange={(e) => product.durationStatus = e.target.checked ? 'Temporary' : 'Lifetime'} />
                    </div>
                  </div>
                </div>
              </li>

              {#if product.durationStatus === 'Temporary'}
                <li class="list-group-item bg-body-tertiary animate__animated animate__fadeInDown animate__faster">
                  <div class="row g-2">
                    <div class="col-6">
                      <label for="p-duration-start" class="form-label x-small text-body-secondary mb-1">Başlangıç Tarihi</label>
                      <input type="datetime-local" id="p-duration-start" class="form-control form-control-sm" bind:value={product.durationStart} />
                    </div>
                    <div class="col-6">
                      <label for="p-duration-expiry" class="form-label x-small text-body-secondary mb-1">Bitiş Tarihi</label>
                      <input type="datetime-local" id="p-duration-expiry" class="form-control form-control-sm" bind:value={product.durationExpiry} />
                    </div>
                  </div>
                </li>
              {/if}

              <!-- Öncelik -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">Öncelik</div>
                  <div class="col-6">
                    <input 
                      type="number" 
                      class="form-control form-control-sm" 
                      placeholder="0" 
                      bind:value={product.priority} />
                  </div>
                </div>
              </li>
            </ul>
          </div>
        </div>
      </div>
    </section>
  {/if}

  <!-- Aksiyon Ekle Modalı -->
  <div class="modal fade" id="addActionModal" tabindex="-1" aria-hidden="true">
    <div class="modal-dialog modal-sm modal-dialog-centered">
      <div class="modal-content border-0 shadow">
        <div class="modal-header border-0 pb-0">
          <h6 class="modal-title fw-bold">Aksiyon Seçin</h6>
          <button type="button" class="btn-close small" data-bs-dismiss="modal" aria-label="Close"></button>
        </div>
        <div class="modal-body p-3">
          <div class="list-group list-group-flush border rounded overflow-hidden">
            <button 
              type="button" 
              class="list-group-item list-group-item-action d-flex align-items-center gap-3 py-3"
              onclick={() => addAction('credit')}>
              <i class="fas fa-coins text-warning fa-lg"></i>
              <div class="d-flex flex-column">
                <span class="fw-medium">Kredi Yükle</span>
                <span class="x-small text-body-secondary">Oyuncuya bakiye ekler</span>
              </div>
            </button>
            <button 
              type="button" 
              class="list-group-item list-group-item-action d-flex align-items-center gap-3 py-3"
              onclick={() => addAction('permission')}>
              <i class="fas fa-gavel text-info fa-lg"></i>
              <div class="d-flex flex-column">
                <span class="fw-medium">Yetkilendir</span>
                <span class="x-small text-body-secondary">Yetki grubu veya node ekler</span>
              </div>
            </button>
            <button 
              type="button" 
              class="list-group-item list-group-item-action d-flex align-items-center gap-3 py-3"
              onclick={() => addAction('command')}>
              <i class="fas fa-terminal text-body-secondary fa-lg"></i>
              <div class="d-flex flex-column">
                <span class="fw-medium">Komut Çalıştır</span>
                <span class="x-small text-body-secondary">Özel konsol komutu çalıştırır</span>
              </div>
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</MarketLayout>

<style>
  :global(.editor-container .ProseMirror) {
    min-height: 300px;
  }
  
  .uppercase {
    text-transform: uppercase;
  }
  
  .tracking-wider {
    letter-spacing: 0.05em;
  }
  
  .x-small {
    font-size: 0.7rem;
  }
  .cursor-pointer {
    cursor: pointer;
  }

  .transition-all {
    transition: all 0.2s ease;
  }
</style>
