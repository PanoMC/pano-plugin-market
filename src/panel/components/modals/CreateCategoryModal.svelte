<script>
  import { DragAndDropZone } from '@panomc/sdk/components/panel';
  import { api } from '@panomc/sdk/plugin-api';
  import IconPicker from '../IconPicker.svelte';
  import { _, showSuccessToast, showErrorToast } from '../../../i18n';
  import { call, errorKey, PANEL_URL } from '../../utils/api.js';
  import {
    DEFAULT_UPGRADE_MODE,
    UPGRADE_MODES,
    changedCategoryFields,
    formValue,
  } from '../../utils/category-gift.js';

  let { isEdit = false, category = null, onSaved = () => {} } = $props();

  // Lowercase form values -> backend MarketStatus enum names.
  const STATUS_MAP = { active: 'ACTIVE', inactive: 'INACTIVE', hidden: 'HIDDEN' };

  let categoryName = $state('');
  let description = $state('');
  let iconClass = $state('fa-folder');
  let categoryColor = $state('#0d6efd');
  let categoryStatus = $state('active'); // 'active', 'inactive' or 'hidden'
  let tiered = $state(false);
  let upgradeMode = $state(DEFAULT_UPGRADE_MODE);
  // CATEGORY_IN_USE (409): un-tiering a category with active entitlements; marks the switch.
  let tieredInvalid = $state(false);


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
      tiered = category.tiered === true;
      upgradeMode = UPGRADE_MODES.includes(category.upgradeMode)
        ? category.upgradeMode
        : DEFAULT_UPGRADE_MODE;
      tieredInvalid = false;
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
      tiered = false;
      upgradeMode = DEFAULT_UPGRADE_MODE;
      tieredInvalid = false;
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
        ? `${PANEL_URL}/categories/image/${imageFileName}`
        : null)
  );

  function processFile(file) {
    const maxSize = 5 * 1024 * 1024;
    const allowedTypes = ['image/png', 'image/jpeg', 'image/gif', 'image/webp'];

    if (file.size > maxSize) {
      showErrorToast($_('modals.category.toast-file-too-large'));
      if (fileInput) fileInput.value = '';
      return;
    }

    if (!allowedTypes.includes(file.type)) {
      showErrorToast($_('modals.category.toast-invalid-file-type'));
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
      showErrorToast($_('modals.category.toast-file-too-large'));
    } else if (error === 'INVALID_TYPE') {
      showErrorToast($_('modals.category.toast-invalid-file-type'));
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
      showErrorToast($_('modals.category.toast-name-required'));
      return;
    }

    loading = true;
    tieredInvalid = false;

    try {
      const current = {
        name: categoryName.trim(),
        description: description || '',
        icon: iconClass || 'fa-folder',
        color: categoryColor || '#0d6efd',
        status: STATUS_MAP[categoryStatus] || 'ACTIVE',
        tiered,
        upgradeMode,
      };
      const formData = new FormData();
      let result;
      if (isEdit && category) {
        // PUT is a partial update: only the changed keys are sent (parentId / position stay as
        // they are on the server).
        const original = {
          name: category.name || '',
          description: category.description || '',
          icon: category.icon || 'fa-folder',
          color: category.color || '#0d6efd',
          status: STATUS_MAP[category.status] || 'ACTIVE',
          tiered: category.tiered === true,
          upgradeMode: category.upgradeMode,
        };
        for (const [key, value] of Object.entries(changedCategoryFields(original, current))) {
          formData.append(key, formValue(value));
        }
        if (selectedFile) formData.append('image', selectedFile);
        if (removeImage) formData.append('removeImage', 'true');

        result = await call(
          api.panel.put({
            path: `/categories/${category.id}`,
            body: formData,
            headers: {},
          }),
        );
      } else {
        for (const [key, value] of Object.entries(changedCategoryFields(null, current))) {
          if (key === 'upgradeMode' && !tiered) continue;
          formData.append(key, formValue(value));
        }
        if (selectedFile) formData.append('image', selectedFile);
        result = await call(
          api.panel.post({ path: '/categories', body: formData, headers: {} }),
        );
      }

      if (!result.ok) {
        if (result.error === 'CATEGORY_IN_USE') tieredInvalid = true;
        showErrorToast($_(errorKey(result.error)));
        return;
      }

      showSuccessToast(
        isEdit ? $_('modals.category.toast-updated') : $_('modals.category.toast-created'),
      );
      closeModal();
      onSaved();
    } catch (e) {
      console.error('[Market] Failed to save category', e);
      showErrorToast($_('modals.category.toast-save-error'));
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
        <div class="form-floating mb-3">
          <select class="form-select" id="category-status" bind:value={categoryStatus}>
            <option value="active">{$_('common.active')}</option>
            <option value="inactive">{$_('common.inactive')}</option>
            <option value="hidden">{$_('common.hidden')}</option>
          </select>
          <label for="category-status">{$_('common.status')}</label>
        </div>

        <!-- Tiered category -->
        <div class="form-check form-switch" class:mb-3={tiered}>
          <input
            class="form-check-input"
            class:is-invalid={tieredInvalid}
            type="checkbox"
            role="switch"
            id="category-tiered"
            bind:checked={tiered}
            onchange={() => (tieredInvalid = false)} />
          <label class="form-check-label" for="category-tiered">
            {$_('modals.category.tiered')}
          </label>
          {#if tieredInvalid}
            <div class="invalid-feedback d-block">{$_('errors.CATEGORY_IN_USE')}</div>
          {/if}
          <div class="form-text">{$_('modals.category.tiered-hint')}</div>
        </div>

        {#if tiered}
          <div class="form-floating mb-0">
            <select class="form-select" id="category-upgrade-mode" bind:value={upgradeMode}>
              {#each UPGRADE_MODES as value (value)}
                <option {value}>{$_(`modals.category.upgrade-mode-${value}`)}</option>
              {/each}
            </select>
            <label for="category-upgrade-mode">{$_('modals.category.upgrade-mode')}</label>
          </div>
        {/if}

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
