<script module>
  import ApiUtil from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams },
    } = event;
    const { pageTitle } = await parent();

    pageTitle.set('plugins.pano-plugin-market.pages.create-product.title');

    const id = searchParams.get('id');

    const [productRes, categoriesRes, serversRes, settingsRes] = await Promise.all([
      id
        ? ApiUtil.get({ path: `/api/panel/market/products/${id}`, request: event })
        : Promise.resolve(null),
      ApiUtil.get({ path: '/api/panel/market/categories', request: event }),
      ApiUtil.get({ path: '/api/panel/market/servers', request: event }),
      ApiUtil.get({ path: '/api/panel/market/settings', request: event }),
    ]);

    const product = productRes && !productRes.error ? productRes.product || null : null;
    const categories = categoriesRes && !categoriesRes.error ? categoriesRes.categories || [] : [];
    const servers = serversRes && !serversRes.error ? serversRes.servers || [] : [];
    const currencySymbol =
      settingsRes && !settingsRes.error ? settingsRes.currencySymbol || '' : '';

    return { data: { product, categories, servers, currencySymbol } };
  }
</script>

<script>
  import { tick } from 'svelte';
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { Editor, DragAndDropZone, NoContent } from '@panomc/sdk/components/panel';
  import IconPicker from '../components/IconPicker.svelte';
  import ProductSelector from '../components/ProductSelector.svelte';
  import { base, goto, page } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import { _ } from '../../i18n';

  let { data } = $props();

  // An edit URL whose product no longer exists (deleted product / stale bookmark):
  // render an explicit error state instead of silently falling through to create
  // mode — and never toast during init, this component also renders on the server.
  const productMissing = $derived(!!$page.url.searchParams.get('id') && !data.product);

  // The numeric DB id (set when editing); product.id below is the user-editable slug string.
  let productDbId = $state(null);
  let saving = $state(false);

  let existingImageFileName = $state(null);
  let imageRemoved = $state(false);

  // Category tree + servers come from load(); the <select> consumes the flattened list.
  let categories = $derived(flattenCategories(data.categories || []));
  let servers = $derived(data.servers || []);

  // SALES-currency symbol from GET /settings (fetched in load()); used for the
  // price input adornment. Never hardcode a currency symbol.
  let currencySymbol = $derived(data.currencySymbol || '');

  function defaultProductState() {
    return {
      id: '',
      name: '',
      description: '',
      category: -1,
      price: 0,
      creditPrice: 0,
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
    };
  }

  let product = $state(defaultProductState());

  let selectedFile = $state(null);
  let previewUrl = $state(null);
  let fileInput;

  let hasPermission = $state(false);
  let hasRequiredProducts = $state(false);

  let activeTab = $state('general');
  let isDirty = $state(false);

  // Dirty baseline; null until captured (after the editor mounts, see below).
  let initialProduct = $state(null);

  // (Re)initializes the form from a load() record (or back to create-mode defaults).
  function syncFromLoad(record) {
    selectedFile = null;
    imageRemoved = false;

    if (record) {
      applyProduct(record);
    } else {
      productDbId = null;
      product = defaultProductState();
      existingImageFileName = null;
      previewUrl = null;
    }

    hasPermission = product.permission !== '';
    hasRequiredProducts = product.requiredProducts.length > 0;
    isDirty = false;
  }

  // Prefill from load() data when editing; the dirty baseline is captured after mount.
  // svelte-ignore state_referenced_locally -- intentional init-time seeding; the
  // effect below re-syncs if load() ever re-runs without a remount.
  syncFromLoad(data.product);

  // Defensive re-sync if load() ever re-runs without a component remount — the
  // current host remounts on every data change, but that is its private contract;
  // a stale `productDbId` here would make "create" silently PUT to the old record.
  let appliedRecord = null;
  let recordEffectRan = false;
  $effect(() => {
    const record = data.product;
    if (recordEffectRan && record !== appliedRecord) {
      syncFromLoad(record);
      captureBaseline();
    }
    recordEffectRan = true;
    appliedRecord = record;
  });

  // Capture the dirty baseline only after the editor has mounted: TipTap's
  // onCreate writes normalized HTML back into product.description (e.g.
  // '' -> '<p></p>'), which would otherwise flip isDirty on a pristine form.
  function captureBaseline() {
    initialProduct = null;
    tick().then(() => {
      initialProduct = JSON.stringify(product);
    });
  }

  $effect(() => {
    captureBaseline();
  });

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

  $effect(() => {
    const snapshot = JSON.stringify(product);
    if (initialProduct !== null && (snapshot !== initialProduct || selectedFile)) {
      isDirty = true;
    }
  });

  const tabs = [
    { id: 'general', label: 'pages.create-product.tab-general' },
    { id: 'pricing', label: 'pages.create-product.tab-pricing' },
    { id: 'restrictions', label: 'pages.create-product.tab-restrictions' },
    { id: 'actions', label: 'pages.create-product.tab-actions' }
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
      case 'credit': return $_('pages.create-product.action-credit');
      case 'permission': return $_('pages.create-product.action-permission');
      case 'command': return $_('pages.create-product.action-command');
      default: return $_('pages.create-product.action-default');
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

  // ── Data loading ────────────────────────────────────────────────────────

  function flattenCategories(tree, out = []) {
    for (const cat of tree) {
      out.push({ id: cat.id, name: cat.name });
      if (cat.children?.length) flattenCategories(cat.children, out);
    }
    return out;
  }

  // epoch millis -> value for <input type="datetime-local"> (local time, to match
  // how handleSave reparses the string via new Date(...)).
  function toLocalInput(epoch) {
    const d = new Date(epoch);
    return new Date(d.getTime() - d.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  }

  function mapActionFromApi(a, index) {
    const t = (a.type || '').toLowerCase();
    return {
      id: Date.now() + index,
      type: t,
      value: a.value !== undefined && a.value !== null ? a.value : (t === 'credit' ? '' : []),
      currentInput: '',
      delay: t === 'command' ? (a.delay ?? 0) : undefined,
      targetServers: t === 'command' ? (a.targetServers || []) : undefined
    };
  }

  // Maps a loaded product record onto the editable `product` state (the dirty
  // baseline is captured after mount, so Save starts disabled).
  function applyProduct(p) {
    productDbId = p.id;
    product.name = p.name || '';
    product.id = p.slug || '';
    product.description = p.description || '';
    product.category = (p.categoryId === null || p.categoryId === undefined) ? -1 : p.categoryId;
    product.price = p.price ?? 0;
    product.creditPrice = p.creditPrice ?? 0;
    product.hasStockLimit = p.stock !== null && p.stock !== undefined;
    product.stock = p.stock ?? 0;
    product.requiredProducts = p.requiredProducts || [];
    product.requireOnlyOne = p.requireOnlyOne || false;
    product.permission = p.requiredPermission || '';
    product.status = p.status === 'ACTIVE' ? 'active' : 'inactive';
    product.featured = p.featured || false;
    product.durationStatus = p.durationType === 'TEMPORARY' ? 'Temporary' : 'Lifetime';
    product.durationStart = p.durationStart ? toLocalInput(p.durationStart) : '';
    product.durationExpiry = p.durationExpiry ? toLocalInput(p.durationExpiry) : '';
    product.priority = p.priority ?? 0;
    product.icon = p.icon || 'fa-box';
    product.actions = (p.actions || []).map((a, i) => mapActionFromApi(a, i));

    existingImageFileName = p.imageFileName || null;
    imageRemoved = false;
    if (existingImageFileName) {
      previewUrl = `${base}/api/panel/market/products/image/${existingImageFileName}`;
    }
  }

  // ── Saving ──────────────────────────────────────────────────────────────

  function buildActionsPayload() {
    return (product.actions || []).map((a) => {
      const type = (a.type || '').toUpperCase();
      if (a.type === 'command') {
        return {
          type,
          value: a.value || [],
          delay: Number(a.delay) || 0,
          targetServers: a.targetServers || []
        };
      }
      if (a.type === 'credit') {
        return { type, value: Number(a.value) };
      }
      // permission (value is a string array)
      return { type, value: a.value };
    });
  }

  function mapSaveError(error) {
    if (error === 'SLUG_ALREADY_EXISTS') {
      return $_('pages.create-product.slug-exists');
    }
    return $_('pages.create-product.save-error');
  }

  async function handleSave() {
    if (!product.name || !product.name.trim()) {
      showToast($_('pages.create-product.name-required'));
      return;
    }

    // Validate credit actions client-side: the backend rejects a non-numeric
    // value with a blanket 400, which would surface as a generic error toast.
    for (const action of product.actions || []) {
      if (action.type === 'credit' && !(Number(action.value) > 0)) {
        showToast($_('pages.create-product.credit-amount-invalid'));
        return;
      }
    }

    saving = true;
    try {
      const formData = new FormData();
      formData.append('name', product.name);
      formData.append('slug', product.id || '');
      formData.append('description', product.description || '');
      formData.append('categoryId', product.category);
      formData.append('price', product.price || 0);
      formData.append('creditPrice', product.creditPrice || 0);

      if (product.hasStockLimit) {
        formData.append('stock', product.stock || 0);
      }

      if (hasRequiredProducts) {
        formData.append('requiredProducts', JSON.stringify(product.requiredProducts || []));
        formData.append('requireOnlyOne', product.requireOnlyOne);
      }

      if (hasPermission && product.permission) {
        formData.append('requiredPermission', product.permission);
      }

      formData.append('status', product.status === 'active' ? 'ACTIVE' : 'INACTIVE');
      formData.append('featured', product.featured);
      formData.append('durationType', product.durationStatus === 'Temporary' ? 'TEMPORARY' : 'LIFETIME');

      if (product.durationStatus === 'Temporary') {
        if (product.durationStart) {
          formData.append('durationStart', new Date(product.durationStart).getTime());
        }
        if (product.durationExpiry) {
          formData.append('durationExpiry', new Date(product.durationExpiry).getTime());
        }
      }

      formData.append('priority', product.priority || 0);
      formData.append('icon', product.icon || 'fa-box');
      formData.append('actions', JSON.stringify(buildActionsPayload()));

      if (productDbId && imageRemoved && !selectedFile) {
        formData.append('removeImage', true);
      }
      if (selectedFile) {
        formData.append('image', selectedFile);
      }

      let result;
      if (productDbId) {
        result = await ApiUtil.put({
          path: `/api/panel/market/products/${productDbId}`,
          body: formData,
          headers: {}
        });
      } else {
        result = await ApiUtil.post({
          path: `/api/panel/market/products`,
          body: formData,
          headers: {}
        });
      }

      if (result?.error) {
        showToast(mapSaveError(result.error));
        return;
      }

      showToast(productDbId ? $_('pages.create-product.update-success') : $_('pages.create-product.create-success'));
      isDirty = false;
      initialProduct = JSON.stringify(product);
      goto(`${base}/market/products`);
    } catch (e) {
      console.error('[Market] Failed to save product', e);
      showToast($_('pages.create-product.save-error'));
    } finally {
      saving = false;
    }
  }

  async function deleteProduct() {
    if (!productDbId) return;
    if (!window.confirm($_('pages.create-product.delete-confirm', { values: { name: product.name } }))) return;

    saving = true;
    try {
      const result = await ApiUtil.delete({ path: `/api/panel/market/products/${productDbId}` });
      if (result?.error) {
        showToast($_('pages.create-product.delete-error'));
        return;
      }
      showToast($_('pages.create-product.delete-success'));
      goto(`${base}/market/products`);
    } catch (e) {
      console.error('[Market] Failed to delete product', e);
      showToast($_('pages.create-product.delete-error'));
    } finally {
      saving = false;
    }
  }

  function processFile(file) {
    selectedFile = file;
    imageRemoved = false;
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
    if (existingImageFileName) {
      imageRemoved = true;
    }
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
      <a href="{base}/market/products" class="btn btn-link text-decoration-none p-0">
        <i class="fas fa-arrow-left"></i>
        <span class="ms-2">{$_('pages.create-product.products')}</span>
      </a>

      <ul class="nav nav-pills">
        {#each tabs as tab}
          <li class="nav-item">
            <button 
              class="nav-link {activeTab === tab.id ? 'active' : ''}" 
              onclick={() => activeTab = tab.id}>
              {$_(tab.label)}
            </button>
          </li>
        {/each}
      </ul>
    </div>
  {/snippet}

  {#snippet right()}
    {#if !productMissing}
    <div class="hstack gap-1">
      <button class="btn btn-link link-danger" title={$_('pages.create-product.remove-title')} onclick={deleteProduct} disabled={!productDbId || saving}>
        <i class="fas fa-trash"></i>
      </button>
      {#if activeTab === 'actions'}
        <button
          class="btn btn-link"
          title={$_('pages.create-product.add-action-title')}
          data-bs-toggle="modal"
          data-bs-target="#addActionModal">
          <i class="fas fa-plus"></i>
        </button>
      {/if}
      <button class="btn btn-secondary ms-2" onclick={handleSave} disabled={!isDirty || saving}>
        {#if saving}
          <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
        {:else}
          <i class="fas fa-save"></i>
        {/if}
        <span class="d-lg-inline d-none ms-2">{$_('common.save')}</span>
      </button>
    </div>
    {/if}
  {/snippet}

  {#if productMissing}
    <div class="card animate__animated animate__fadeIn">
      <div class="card-body text-center text-body-secondary py-5">
        <i class="fas fa-circle-exclamation mb-2 fs-3"></i>
        <div>{$_('pages.create-product.not-found')}</div>
        <a href="{base}/market/products" class="btn btn-secondary mt-3">{$_('pages.create-product.back-to-products')}</a>
      </div>
    </div>
  {:else if activeTab === 'general' || activeTab === 'pricing' || activeTab === 'restrictions' || activeTab === 'actions'}
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
                placeholder={$_('pages.create-product.name-placeholder')}
                bind:value={product.name} />

              <div class="form-floating mb-0">
                <input
                  type="text"
                  class="form-control font-monospace"
                  id="productId"
                  placeholder={$_('pages.create-product.id-placeholder')}
                  bind:value={product.id} />
                <label for="productId">{$_('pages.create-product.product-id')}</label>
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
                <label class="col-sm-3 col-form-label" for="p-price">{$_('pages.create-product.price')}</label>
                <div class="col-sm-9">
                  <div class="input-group">
                    <input type="number" id="p-price" class="form-control" placeholder="0.00" bind:value={product.price} />
                    <span class="input-group-text">{currencySymbol}</span>
                  </div>
                </div>
              </div>

              <!-- Kredi Fiyatı -->
              <div class="row mb-3 align-items-center">
                <label class="col-sm-3 col-form-label" for="p-credit-price">{$_('pages.create-product.credit-price')}</label>
                <div class="col-sm-9">
                  <div class="input-group">
                    <input type="number" id="p-credit-price" class="form-control" placeholder="0" bind:value={product.creditPrice} />
                    <span class="input-group-text"><i class="fas fa-coins text-warning me-2"></i> {$_('pages.create-product.credit')}</span>
                  </div>
                  <div class="form-text small mt-1 text-body-secondary">
                    {$_('pages.create-product.credit-price-help')}
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
                <label class="col-sm-3 col-form-label" for="p-stock-switch">{$_('pages.create-product.stock')}</label>
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
                  <label class="col-sm-3 col-form-label" for="p-stock-amount">{$_('pages.create-product.stock-amount')}</label>
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
                <label class="col-sm-3 col-form-label" for="p-required-switch">{$_('pages.create-product.required-products-switch')}</label>
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
                    <label class="col-sm-3 col-form-label">{$_('pages.create-product.required-products')}</label>
                    <div class="col-sm-9">
                      <ProductSelector bind:selected={product.requiredProducts} multiple={true} />
                      <div class="form-text small mt-2">
                        {$_('pages.create-product.required-products-help')}
                      </div>
                    </div>
                  </div>

                  <!-- Tek Ürün Şartı -->
                  <div class="row mb-3 align-items-center">
                    <label class="col-sm-3 col-form-label" for="p-require-one">{$_('pages.create-product.require-one')}</label>
                    <div class="col-sm-9">
                      <div class="form-check form-switch m-0 d-flex align-items-center gap-2">
                        <input 
                          class="form-check-input" 
                          type="checkbox" 
                          role="switch" 
                          id="p-require-one" 
                          bind:checked={product.requireOnlyOne} />
                        <span>
                          {$_('pages.create-product.require-one-help')}
                        </span>
                      </div>
                    </div>
                  </div>
                </div>
              {/if}

              <hr class="my-4 opacity-25" />

              <!-- Yetki Gereksinimi -->
              <div class="row mb-3 align-items-center">
                <label class="col-sm-3 col-form-label" for="p-permission-switch">{$_('pages.create-product.permission-switch')}</label>
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
                  <label class="col-sm-3 col-form-label" for="p-permission-node">{$_('pages.create-product.permission-node')}</label>
                  <div class="col-sm-9">
                    <input
                      type="text"
                      id="p-permission-node"
                      class="form-control"
                      placeholder={$_('pages.create-product.permission-placeholder')}
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
                        title={$_('pages.create-product.delete-action-title')}
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
                            <label class="col-sm-3 col-form-label" for="action-val-{action.id}">{$_('pages.create-product.amount')}</label>
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
                            <label class="col-sm-3 col-form-label" for="action-val-{action.id}">{$_('pages.create-product.permission-node-label')}</label>
                            <div class="col-sm-9">
                              <input
                                type="text"
                                id="action-val-{action.id}"
                                class="form-control font-monospace mb-2"
                                placeholder={$_('pages.create-product.permission-node-placeholder')}
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
                            <label class="col-sm-3 col-form-label" for="action-server-{action.id}">{$_('pages.create-product.target-server')}</label>
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
                                {$_('pages.create-product.target-server-help')}
                              </div>
                            </div>
                          </div>

                          <!-- Komutlar -->
                          <div class="row mb-3">
                            <label class="col-sm-3 col-form-label" for="action-val-{action.id}">{$_('pages.create-product.command')}</label>
                            <div class="col-sm-9">
                              <input
                                type="text"
                                id="action-val-{action.id}"
                                class="form-control mb-2"
                                placeholder={$_('pages.create-product.command-placeholder')}
                                bind:value={action.currentInput}
                                onkeydown={(e) => addCommand(action, e)} />
                              
                              <div class="form-text small mt-0 mb-3">
                                {$_('pages.create-product.variables')} <code>{'{player}'}</code>, <code>{'{product}'}</code>
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
                            <label class="col-sm-3 col-form-label" for="action-delay-{action.id}">{$_('pages.create-product.delay')}</label>
                            <div class="col-sm-9">
                              <div class="input-group">
                                <input 
                                  type="number" 
                                  id="action-delay-{action.id}" 
                                  class="form-control" 
                                  placeholder="0" 
                                  bind:value={action.delay} />
                                <span class="input-group-text bg-transparent small">{$_('pages.create-product.seconds')}</span>
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
                        alt={$_('pages.create-product.image-alt')}
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
                    title={$_('pages.create-product.image-title')}
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
                  <div class="col-6">{$_('common.status')}</div>
                  <div class="col-6 d-flex justify-content-end align-items-center gap-2">
                    <span>
                      {product.status === 'active' ? $_('common.active') : $_('common.inactive')}
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
                  <div class="col-6">{$_('pages.create-product.featured')}</div>
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
                  <div class="col-6">{$_('pages.create-product.category')}</div>
                  <div class="col-6">
                    <select class="form-select form-select-sm" bind:value={product.category}>
                      <option value={-1} selected>{$_('pages.create-product.uncategorized')}</option>
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
                  <div class="col-6">{$_('pages.create-product.icon')}</div>
                  <div class="col-6">
                    <IconPicker bind:value={product.icon} color="#0d6efd" placement="top-end" />
                  </div>
                </div>
              </li>

              <!-- Süre Durumu -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">{$_('pages.create-product.duration')}</div>
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
                      <label for="p-duration-start" class="form-label x-small text-body-secondary mb-1">{$_('pages.create-product.start-date')}</label>
                      <input type="datetime-local" id="p-duration-start" class="form-control form-control-sm" bind:value={product.durationStart} />
                    </div>
                    <div class="col-6">
                      <label for="p-duration-expiry" class="form-label x-small text-body-secondary mb-1">{$_('pages.create-product.end-date')}</label>
                      <input type="datetime-local" id="p-duration-expiry" class="form-control form-control-sm" bind:value={product.durationExpiry} />
                    </div>
                  </div>
                </li>
              {/if}

              <!-- Öncelik -->
              <li class="list-group-item">
                <div class="row g-0 align-items-center">
                  <div class="col-6">{$_('pages.create-product.priority')}</div>
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
          <h6 class="modal-title fw-bold">{$_('pages.create-product.select-action')}</h6>
          <button type="button" class="btn-close small" data-bs-dismiss="modal" aria-label={$_('common.close')}></button>
        </div>
        <div class="modal-body p-3">
          <div class="list-group list-group-flush border rounded overflow-hidden">
            <button 
              type="button" 
              class="list-group-item list-group-item-action d-flex align-items-center gap-3 py-3"
              onclick={() => addAction('credit')}>
              <i class="fas fa-coins text-warning fa-lg"></i>
              <div class="d-flex flex-column">
                <span class="fw-medium">{$_('pages.create-product.action-credit')}</span>
                <span class="x-small text-body-secondary">{$_('pages.create-product.action-credit-desc')}</span>
              </div>
            </button>
            <button 
              type="button" 
              class="list-group-item list-group-item-action d-flex align-items-center gap-3 py-3"
              onclick={() => addAction('permission')}>
              <i class="fas fa-gavel text-info fa-lg"></i>
              <div class="d-flex flex-column">
                <span class="fw-medium">{$_('pages.create-product.action-permission')}</span>
                <span class="x-small text-body-secondary">{$_('pages.create-product.action-permission-desc')}</span>
              </div>
            </button>
            <button 
              type="button" 
              class="list-group-item list-group-item-action d-flex align-items-center gap-3 py-3"
              onclick={() => addAction('command')}>
              <i class="fas fa-terminal text-body-secondary fa-lg"></i>
              <div class="d-flex flex-column">
                <span class="fw-medium">{$_('pages.create-product.action-command')}</span>
                <span class="x-small text-body-secondary">{$_('pages.create-product.action-command-desc')}</span>
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
