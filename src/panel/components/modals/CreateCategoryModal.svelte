<script>
  import { DragAndDropZone } from '@panomc/sdk/components/panel';
  import { base } from '@panomc/sdk/svelte';
  import { showToast } from '@panomc/sdk/toasts';
  import ApiUtil from '@panomc/sdk/utils/api';
  import IconPicker from '../IconPicker.svelte';
  import { _ } from '../../../i18n';

  let { isEdit = false, category = null, onSaved = () => {} } = $props();

  // Lowercase form values -> backend MarketStatus enum names.
  const STATUS_MAP = { active: 'ACTIVE', inactive: 'INACTIVE', hidden: 'HIDDEN' };

  let categoryName = $state('');
  let description = $state('');
  let iconClass = $state('fa-folder');
  let categoryColor = $state('#0d6efd');
  let categoryStatus = $state('active'); // 'active', 'inactive' or 'hidden'

  // Preserved on edit so PUT does not reparent/reorder the category.
  let editParentId = $state(null);
  let editPosition = $state(null);

  let fileInput = $state(null);
  let selectedFile = $state(null);
  let previewUrl = $state(null);
  let imageFileName = $state('');
  let removeImage = $state(false);
  let loading = $state(false);

  function initForm() {
    if (isEdit && category) {
      categoryName = category.name || '';
      description = category.description || '';
      iconClass = category.icon || 'fa-folder';
      categoryColor = category.color || '#0d6efd';
      categoryStatus = category.status || 'active';
      imageFileName = category.imageFileName || '';
      editParentId = category.parentId ?? null;
      editPosition = category.position ?? null;
      previewUrl = null;
      selectedFile = null;
      removeImage = false;
    } else if (!isEdit) {
      categoryName = '';
      description = '';
      iconClass = 'fa-folder';
      categoryColor = '#0d6efd';
      categoryStatus = 'active';
      imageFileName = '';
      editParentId = null;
      editPosition = null;
      previewUrl = null;
      selectedFile = null;
      removeImage = false;
    }
  }

  // Re-init when the target category prop changes.
  $effect(initForm);

  // Also re-init on every open: the page may re-open the modal with the same prop
  // values (create -> create, or reopening the same row after an abandoned edit),
  // which would not re-trigger the prop-keyed effect.
  $effect(() => {
    const el = document.getElementById('createCategoryModal');
    if (!el) return;
    const handler = () => initForm();
    el.addEventListener('show.bs.modal', handler);
    return () => el.removeEventListener('show.bs.modal', handler);
  });

  let displayImageUrl = $derived(
    previewUrl ||
      (!removeImage && imageFileName
        ? `${base}/api/panel/market/categories/image/${imageFileName}`
        : null)
  );

  function processFile(file) {
    const maxSize = 5 * 1024 * 1024;
    const allowedTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];

    if (file.size > maxSize) {
      showToast($_('modals.category.toast-file-too-large'));
      if (fileInput) fileInput.value = '';
      return;
    }

    if (!allowedTypes.includes(file.type)) {
      showToast($_('modals.category.toast-invalid-file-type'));
      if (fileInput) fileInput.value = '';
      return;
    }

    selectedFile = file;
    removeImage = false;
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
    removeImage = true;
    if (fileInput) fileInput.value = '';
  }

  function handleFileError(event) {
    const { error } = event.detail;
    if (error === 'INVALID_SIZE') {
      showToast($_('modals.category.toast-file-too-large'));
    } else if (error === 'INVALID_TYPE') {
      showToast($_('modals.category.toast-invalid-file-type'));
    }
  }

  function closeModal() {
    const el = document.getElementById('createCategoryModal');
    if (el && typeof window !== 'undefined' && window.bootstrap) {
      window.bootstrap.Modal.getOrCreateInstance(el).hide();
    }
  }

  async function saveCategory() {
    if (!categoryName || categoryName.trim() === '') {
      showToast($_('modals.category.toast-name-required'));
      return;
    }

    loading = true;

    try {
      const formData = new FormData();
      formData.append('name', categoryName.trim());
      formData.append('description', description || '');
      formData.append('icon', iconClass || 'fa-folder');
      formData.append('color', categoryColor || '#0d6efd');
      formData.append('status', STATUS_MAP[categoryStatus] || 'ACTIVE');

      if (selectedFile) {
        formData.append('image', selectedFile);
      }

      let result;
      if (isEdit && category) {
        // Preserve hierarchy: PUT treats a missing parentId as "move to root".
        if (editParentId !== null && editParentId !== undefined) {
          formData.append('parentId', editParentId);
        }
        if (editPosition !== null && editPosition !== undefined) {
          formData.append('position', editPosition);
        }
        formData.append('removeImage', removeImage);

        result = await ApiUtil.put({
          path: `/api/panel/market/categories/${category.id}`,
          body: formData,
          headers: {},
        });
      } else {
        result = await ApiUtil.post({
          path: '/api/panel/market/categories',
          body: formData,
          headers: {},
        });
      }

      if (result.error) throw result.error;

      showToast(isEdit ? $_('modals.category.toast-updated') : $_('modals.category.toast-created'));
      closeModal();
      onSaved();
    } catch (e) {
      console.error('[Market] Failed to save category', e);
      showToast($_('modals.category.toast-save-error'));
    } finally {
      loading = false;
    }
  }
</script>

<div class="modal fade" id="createCategoryModal" tabindex="-1" aria-labelledby="createCategoryModalLabel" aria-hidden="true">
  <div class="modal-dialog modal-dialog-centered">
    <div class="modal-content">
      <div class="modal-header">
        <h5 class="modal-title" id="createCategoryModalLabel">{isEdit ? $_('modals.category.title-edit') : $_('modals.category.title-create')}</h5>
        <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label={$_('common.close')}></button>
      </div>
      <div class="modal-body pb-3">
        
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
                  title={$_('modals.category.change-image')}
                  onclick={() => fileInput.click()}
                  onkeydown={(e) => e.key === 'Enter' && fileInput.click()}>
                  <img
                    src={displayImageUrl}
                    alt={$_('modals.category.image-preview-alt')}
                    class="w-100 h-100 object-fit-cover" />
                  <div class="preview-overlay position-absolute bottom-0 start-0 w-100 p-3 text-white text-start">
                    <div class="d-flex align-items-center gap-2">
                      <div class="d-flex align-items-center justify-content-center bg-white rounded" style="width: 24px; height: 24px;">
                        <i class="fas {iconClass} fs-6" style="color: {categoryColor};"></i>
                      </div>
                      <span>{categoryName || $_('modals.category.name-placeholder')}</span>
                    </div>
                  </div>
                </div>
                <button
                  type="button"
                  class="btn btn-sm btn-danger position-absolute top-0 start-100 translate-middle"
                  style="z-index: 10;"
                  title={$_('modals.category.remove-image')}
                  aria-label={$_('modals.category.remove-image')}
                  onclick={(e) => { e.stopPropagation(); onRemoveImage(); }}>
                  <i class="fas fa-minus"></i>
                </button>
              </div>
            {:else}
              <DragAndDropZone
                style="aspect-ratio: 21/9;"
                icon="fas fa-image fa-2x"
                title={$_('modals.category.image-upload-title')}
                subtitle={$_('modals.category.image-upload-subtitle')}
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
          <input type="text" class="form-control" id="categoryNameInput" bind:value={categoryName} placeholder={$_('modals.category.name-placeholder')} />
          <label for="categoryNameInput">{$_('modals.category.name-placeholder')}</label>
        </div>

        <!-- Description -->
        <div class="form-floating mb-3">
          <textarea class="form-control" id="categoryDescInput" bind:value={description} placeholder={$_('modals.category.description')} style="height: 80px"></textarea>
          <label for="categoryDescInput">{$_('modals.category.description')}</label>
        </div>

        <div class="row g-3 mb-3">
          <!-- Icon -->
          <div class="col-sm-7">
            <IconPicker bind:value={iconClass} color={categoryColor} />
          </div>

          <!-- Color -->
          <div class="col-sm-5">
            <div class="d-flex align-items-center h-100 gap-2 border rounded p-2 px-3 bg-body-tertiary">
              <input type="color" class="form-control form-control-color p-0 border-0 bg-transparent" id="categoryColorInput" bind:value={categoryColor} title={$_('modals.category.pick-color')} style="width: 32px; height: 32px; cursor: pointer;" />
              <label for="categoryColorInput" class="form-label mb-0 cursor-pointer">{$_('modals.category.pick-color')}</label>
            </div>
          </div>
        </div>

        <!-- Status -->
        <div class="form-floating mb-0">
          <select class="form-select" id="category-status" bind:value={categoryStatus}>
            <option value="active">{$_('common.active')}</option>
            <option value="inactive">{$_('common.inactive')}</option>
            <option value="hidden">{$_('common.hidden')}</option>
          </select>
          <label for="category-status">{$_('common.status')}</label>
        </div>

      </div>
      <div class="modal-footer p-3 pt-3">
        {#if isEdit}
          <button
            type="button"
            class="btn btn-primary w-100 m-0 d-flex align-items-center justify-content-center gap-2"
            disabled={loading || !categoryName}
            onclick={saveCategory}>
            {#if loading}
              <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
            {/if}
            {$_('common.save')}
          </button>
        {:else}
          <button
            type="button"
            class="btn btn-secondary w-100 m-0 d-flex align-items-center justify-content-center gap-2"
            disabled={loading || !categoryName}
            onclick={saveCategory}>
            {#if loading}
              <span class="spinner-border spinner-border-sm" role="status" aria-hidden="true"></span>
            {/if}
            {$_('common.create')}
          </button>
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
