<script>
  import { DragAndDropZone } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import tooltip from '@panomc/sdk/utils/tooltip';
  import IconPicker from '../IconPicker.svelte';

  let { isEdit = false, category = null } = $props();

  let categoryName = $state('');
  let description = $state('');
  let iconClass = $state('fa-folder');
  let categoryColor = $state('#0d6efd');
  let categoryStatus = $state('active'); // 'active' or 'inactive'

  let fileInput = $state(null);
  let selectedFile = $state(null);
  let previewUrl = $state(null);
  let categoryImageUrl = $state('');

  $effect(() => {
    if (isEdit && category) {
      categoryName = category.name || '';
      description = category.description || '';
      iconClass = category.icon || 'fa-folder';
      categoryColor = category.color || '#0d6efd';
      categoryStatus = category.status || 'active';
      categoryImageUrl = category.image || '';
      previewUrl = null;
      selectedFile = null;
    } else if (!isEdit) {
      categoryName = '';
      description = '';
      iconClass = 'fa-folder';
      categoryColor = '#0d6efd';
      categoryStatus = 'active';
      categoryImageUrl = '';
      previewUrl = null;
      selectedFile = null;
    }
  });

  let displayImageUrl = $derived(
    previewUrl ||
      (categoryImageUrl
        ? categoryImageUrl.startsWith('http')
          ? categoryImageUrl
          : `${base}${categoryImageUrl}`
        : null)
  );

  function processFile(file) {
    const maxSize = 5 * 1024 * 1024;
    const allowedTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];

    if (file.size > maxSize) {
      showToast('Dosya boyutu çok büyük (Maks. 5MB)');
      if (fileInput) fileInput.value = '';
      return;
    }

    if (!allowedTypes.includes(file.type)) {
      showToast('Geçersiz dosya türü (Sadece resim)');
      if (fileInput) fileInput.value = '';
      return;
    }

    selectedFile = file;
    const reader = new FileReader();
    reader.onload = (e) => {
      previewUrl = e.target.result;
    };
    reader.readAsDataURL(file);
  }

  function onFileChange(event) {
    const file = event.target.files[0];
    if (file) {
      processFile(file);
    }
  }

  function onRemoveImage() {
    selectedFile = null;
    previewUrl = null;
    categoryImageUrl = '';
    if (fileInput) fileInput.value = '';
  }

  function handleFileError(event) {
    const { error } = event.detail;
    if (error === 'INVALID_SIZE') {
      showToast('Dosya boyutu çok büyük (Maks. 5MB)');
    } else if (error === 'INVALID_TYPE') {
      showToast('Geçersiz dosya türü (Sadece resim)');
    }
  }
</script>

<div class="modal fade" id="createCategoryModal" tabindex="-1" aria-labelledby="createCategoryModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createCategoryModalLabel">{isEdit ? 'Kategori Düzenle' : 'Kategori Oluştur'}</h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Kapat"></button>
      </div>
      <div class="modal-body pb-0">
        
        <!-- Image Upload -->
        <div class="mb-3 text-center">
          {#key displayImageUrl}
            {#if displayImageUrl}
              <div class="position-relative w-100">
                <div
                  class="preview-container rounded border d-flex align-items-center justify-content-center bg-body-tertiary position-relative overflow-hidden"
                  style="aspect-ratio: 21/9; cursor: pointer;"
                  role="button"
                  tabindex="0"
                  use:tooltip={['Değiştir', { placement: 'bottom' }]}
                  onclick={() => fileInput.click()}
                  onkeydown={(e) => e.key === 'Enter' && fileInput.click()}>
                  <img
                    src={displayImageUrl}
                    alt="Önizleme"
                    class="w-100 h-100 object-fit-cover" />
                  <div class="preview-overlay position-absolute bottom-0 start-0 w-100 p-3 text-white text-start">
                    <div class="d-flex align-items-center gap-2">
                      <div class="d-flex align-items-center justify-content-center bg-white rounded" style="width: 24px; height: 24px;">
                        <i class="fas {iconClass} fs-6" style="color: {categoryColor};"></i>
                      </div>
                      <span>{categoryName || 'Kategori Adı'}</span>
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  class="btn btn-sm btn-danger position-absolute top-0 start-100 translate-middle"
                  style="z-index: 10;"
                  use:tooltip={['Kaldır', { placement: 'bottom' }]}
                  onclick={(e) => { e.stopPropagation(); onRemoveImage(); }}>
                  <i class="fas fa-minus"></i>
                </button>
              </div>
            {:else}
              <DragAndDropZone
                style="aspect-ratio: 21/9;"
                icon="fas fa-image fa-2x"
                title="Kategori Görseli (İsteğe Bağlı)"
                subtitle="Sürükleyip bırakın veya seçmek için tıklayın"
                accept={['image/png', 'image/jpeg', 'image/gif', 'image/webp']}
                maxFileSize={5 * 1024 * 1024}
                on:drop={(e) => processFile(e.detail)}
                on:error={handleFileError} />
            {/if}
          {/key}
        </div>

        <!-- Hidden File Input -->
        <input
          id="category-image"
          type="file"
          class="d-none"
          accept="image/png,image/jpeg,image/gif,image/webp"
          onchange={onFileChange}
          bind:this={fileInput} />

        <!-- Category Name -->
        <div class="form-floating mb-3">
          <input type="text" class="form-control" id="categoryNameInput" bind:value={categoryName} placeholder="Kategori Adı" />
          <label for="categoryNameInput">Kategori Adı</label>
        </div>

        <!-- Description -->
        <div class="form-floating mb-3">
          <textarea class="form-control" id="categoryDescInput" bind:value={description} placeholder="Açıklama" style="height: 80px"></textarea>
          <label for="categoryDescInput">Açıklama</label>
        </div>

        <div class="row g-3 mb-3">
          <!-- Icon -->
          <div class="col-sm-7">
            <IconPicker bind:value={iconClass} color={categoryColor} />
          </div>

          <!-- Color -->
          <div class="col-sm-5">
            <div class="d-flex align-items-center h-100 gap-2 border rounded p-2 px-3 bg-body-tertiary">
              <input type="color" class="form-control form-control-color p-0 border-0 bg-transparent" id="categoryColorInput" bind:value={categoryColor} title="Renk Seç" style="width: 32px; height: 32px; cursor: pointer;" />
              <label for="categoryColorInput" class="form-label mb-0 cursor-pointer">Renk Seç</label>
            </div>
          </div>
        </div>

        <!-- Status -->
        <div class="mb-0">
          <div class="form-check form-switch mt-2 mb-0">
            <input
              class="form-check-input cursor-pointer"
              type="checkbox"
              role="switch"
              id="category-status"
              checked={categoryStatus === 'active'}
              onchange={(e) => categoryStatus = e.target.checked ? 'active' : 'inactive'} />
            <label class="form-check-label cursor-pointer user-select-none" for="category-status">
              {categoryStatus === 'active' ? 'Aktif' : 'Pasif'}
            </label>
          </div>
        </div>

      </div>
      <div class="modal-footer border-0 p-3 pt-3">
        {#if isEdit}
          <button type="button" class="btn btn-primary w-100 m-0">Düzenle</button>
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
  .preview-container {
    transition: all 0.2s ease;
  }
  .preview-overlay {
    background: rgba(0, 0, 0, 0.5);
    backdrop-filter: blur(8px);
    -webkit-backdrop-filter: blur(8px);
  }
</style>
