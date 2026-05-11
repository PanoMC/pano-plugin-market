<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, Pagination } from '@panomc/sdk/components/panel';
  import { onMount } from 'svelte';
  import { flip } from 'svelte/animate';

  let page = $state(1);
  let view = $state('table');
  let draggedId = $state(null);
  let dragTarget = $state(null);
  let pendingDragPoint = null;
  let previewFrame = null;

  let categories = $state([
    { id: 1, name: 'VIP Üyelikler', icon: 'fa-crown', description: 'Sunucumuzdaki tüm VIP paketlerini burada bulabilirsiniz.', productsCount: 5, status: 'active', color: '#0dcaf0', image: null, children: [] },
    { 
      id: 2, 
      name: 'Kredi Paketleri', 
      icon: 'fa-coins', 
      description: 'Mağaza içi harcamalarınız için kredi satın alın.', 
      productsCount: 12, 
      status: 'active', 
      color: '#0d6efd', 
      image: null, 
      children: [
        { id: 6, name: 'Bonus Paketler', icon: 'fa-gift', description: 'Ekstra bonus veren paketler.', productsCount: 3, status: 'active', color: '#198754', image: null, children: [] }
      ] 
    },
    { id: 3, name: 'Kasa Anahtarları', icon: 'fa-key', description: 'Gizemli kasaları açmak için gereken anahtarlar.', productsCount: 8, status: 'active', color: '#ffc107', image: null, children: [] },
    { id: 4, name: 'Özel Eşyalar', icon: 'fa-star', description: 'Sadece sınırlı süre için mevcut olan özel eşyalar.', productsCount: 15, status: 'inactive', color: '#6c757d', image: null, children: [] },
    { id: 5, name: 'Kozmetik Ürünler', icon: 'fa-shirt', description: 'Karakterinizi özelleştirebileceğiniz kozmetik ürünler.', productsCount: 20, status: 'active', color: '#d63384', image: null, children: [] }
  ]);

  function onPageClick(pageNum) {
    page = pageNum;
  }

  const paginationEvents = {
    ['$$events']: {
      firstPageClick: () => onPageClick(1),
      lastPageClick: () => onPageClick(5),
      pageLinkClick: (event) => onPageClick(event.detail.page),
    },
  };

  function onDragStart(e, id) {
    draggedId = id;
    dragTarget = null;
    cancelPendingPreview();
    if (e.dataTransfer) {
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', String(id));
      const img = new Image();
      img.src = 'data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7';
      e.dataTransfer.setDragImage(img, 0, 0);
    }
  }

  function findCategory(items, id) {
    for (const item of items) {
      if (item.id === id) return item;

      const match = findCategory(item.children || [], id);
      if (match) return match;
    }

    return null;
  }

  function containsCategory(items, id) {
    return items.some((item) => item.id === id || containsCategory(item.children || [], id));
  }

  function canDropOnCategory(targetId) {
    if (draggedId === null || draggedId === targetId) return false;

    const referenceCategories = categories;
    const draggedCategory = findCategory(referenceCategories, draggedId);
    const targetCategory = findCategory(referenceCategories, targetId);

    return Boolean(
      draggedCategory
      && targetCategory
      && !containsCategory(draggedCategory.children || [], targetId)
    );
  }

  function isDragTarget(targetId, position) {
    return dragTarget?.id === targetId && dragTarget.position === position;
  }

  function setDragTarget(target) {
    if (dragTarget?.id === target?.id && dragTarget?.position === target?.position) return;

    dragTarget = target;
  }

  function clearDragTarget() {
    dragTarget = null;
  }

  function removeCategory(items, id) {
    let removed = null;
    const nextItems = [];

    for (const item of items) {
      if (item.id === id) {
        removed = item;
        continue;
      }

      const result = removeCategory(item.children || [], id);

      if (result.removed) {
        removed = result.removed;
        nextItems.push({ ...item, children: result.items });
      } else {
        nextItems.push(item);
      }
    }

    return { items: nextItems, removed };
  }

  function appendToCategory(items, targetId, category) {
    let inserted = false;

    const nextItems = items.map((item) => {
      if (item.id === targetId) {
        inserted = true;
        return { ...item, children: [...(item.children || []), category] };
      }

      const result = appendToCategory(item.children || [], targetId, category);

      if (result.inserted) {
        inserted = true;
        return { ...item, children: result.items };
      }

      return item;
    });

    return { items: nextItems, inserted };
  }

  function insertNearCategory(items, targetId, category, position) {
    let inserted = false;
    const nextItems = [];

    for (const item of items) {
      if (item.id === targetId) {
        inserted = true;
        if (position === 'before') nextItems.push(category);
        nextItems.push(item);
        if (position === 'after') nextItems.push(category);
        continue;
      }

      const result = insertNearCategory(item.children || [], targetId, category, position);

      if (result.inserted) {
        inserted = true;
        nextItems.push({ ...item, children: result.items });
      } else {
        nextItems.push(item);
      }
    }

    return { items: nextItems, inserted };
  }

  function moveCategoryToTarget(items, sourceId, targetId, position) {
    const result = removeCategory(items, sourceId);
    if (!result.removed) return null;

    const insertion = position === 'inside'
      ? appendToCategory(result.items, targetId, result.removed)
      : insertNearCategory(result.items, targetId, result.removed, position);

    return insertion.inserted ? insertion.items : null;
  }

  function moveCategoryToRoot(items, sourceId) {
    const result = removeCategory(items, sourceId);
    return result.removed ? [...result.items, result.removed] : null;
  }

  function applyCategoryMove(target) {
    if (!target || draggedId === null) return false;

    const nextCategories = target.id === null
      ? moveCategoryToRoot(categories, draggedId)
      : moveCategoryToTarget(categories, draggedId, target.id, target.position);

    if (!nextCategories) return false;

    categories = nextCategories;
    return true;
  }

  function cancelPendingPreview() {
    if (previewFrame !== null) {
      cancelAnimationFrame(previewFrame);
      previewFrame = null;
    }

    pendingDragPoint = null;
  }

  function getDropPosition(row, clientY) {
    const rect = row.getBoundingClientRect();

    if (clientY < rect.top) return 'before';
    if (clientY > rect.bottom) return 'after';

    const offset = clientY - rect.top;
    const third = rect.height / 3;

    if (offset < third) return 'before';
    if (offset > rect.height - third) return 'after';
    return 'inside';
  }

  function getSortableRows(list) {
    return [...list.querySelectorAll('[data-category-row="true"]')]
      .filter((row) => row instanceof HTMLElement && row.dataset.categoryId !== String(draggedId));
  }

  function findRowAtPoint(list, clientX, clientY) {
    const rows = getSortableRows(list);
    let closestRow = null;
    let closestDistance = Number.POSITIVE_INFINITY;

    for (const row of rows) {
      const rect = row.getBoundingClientRect();
      const isHorizontallyAligned = clientX >= rect.left && clientX <= rect.right;
      const isVerticallyInside = clientY >= rect.top && clientY <= rect.bottom;

      if (isHorizontallyAligned && isVerticallyInside) return row;

      if (!isHorizontallyAligned) continue;

      const distance = clientY < rect.top ? rect.top - clientY : clientY - rect.bottom;
      if (distance < closestDistance) {
        closestDistance = distance;
        closestRow = row;
      }
    }

    return closestDistance <= 16 ? closestRow : null;
  }

  function getDropTargetFromPoint(clientX, clientY) {
    const element = document.elementFromPoint(clientX, clientY);
    if (!(element instanceof Element)) return null;

    const list = element.closest('[data-category-sort-list="true"]');
    if (!(list instanceof HTMLElement)) return null;

    const closestRow = element.closest('[data-category-row="true"]');
    const row = closestRow instanceof HTMLElement && closestRow.dataset.categoryId !== String(draggedId)
      ? closestRow
      : findRowAtPoint(list, clientX, clientY);

    if (!row) return null;

    const targetId = Number(row.dataset.categoryId);
    if (!Number.isFinite(targetId) || !canDropOnCategory(targetId)) return null;

    return {
      id: targetId,
      position: getDropPosition(row, clientY),
    };
  }

  function applyDropTarget(target) {
    if (!target) {
      clearDragTarget();
      return false;
    }

    setDragTarget(target);
    return true;
  }

  function applyPendingPreview() {
    previewFrame = null;

    if (draggedId === null || !pendingDragPoint) return;

    const target = getDropTargetFromPoint(pendingDragPoint.x, pendingDragPoint.y);
    applyDropTarget(target);
  }

  function onDragEnd() {
    cancelPendingPreview();
    dragTarget = null;
    draggedId = null;
  }

  function onSortListDragOver(e) {
    if (draggedId === null) {
      clearDragTarget();
      return;
    }

    e.preventDefault();
    if (e.dataTransfer) e.dataTransfer.dropEffect = 'move';

    pendingDragPoint = { x: e.clientX, y: e.clientY };

    if (previewFrame === null) {
      previewFrame = requestAnimationFrame(applyPendingPreview);
    }
  }

  function onSortListDragLeave(e) {
    if (e.currentTarget instanceof Node
      && e.relatedTarget instanceof Node
      && e.currentTarget.contains(e.relatedTarget)) {
      return;
    }

    clearDragTarget();
  }

  function onSortListDrop(e) {
    e.stopPropagation();
    if (draggedId === null) {
      clearDragTarget();
      return;
    }

    e.preventDefault();

    cancelPendingPreview();
    const target = getDropTargetFromPoint(e.clientX, e.clientY);
    if (target) {
      applyDropTarget(target);
      applyCategoryMove(target);
    }
    onDragEnd();
  }

  onMount(() => {
    console.log('Categories page loaded');
  });
</script>

<MarketLayout>
  {#snippet right()}
    <a href="/panel/market/categories/create-category" class="btn btn-secondary border-0">
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">Kategori Ekle</span>
    </a>
  {/snippet}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {categories.length} Kategori
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={view === 'table'} onclick={() => (view = 'table')}>Tablo</CardFiltersItem>
        <CardFiltersItem button active={view === 'sort'} onclick={() => (view = 'sort')}>Sıralama</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if view === 'table'}
      <div class="table-responsive">
      <table class="table table-hover align-middle">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col" style="width: 60px;"></th>
            <th scope="col">Kategori İçeriği</th>
            <th scope="col" class="text-center" style="width: 120px;">Durum</th>
            <th scope="col" class="text-center" style="width: 150px;">Ürün Sayısı</th>
          </tr>
        </thead>
        <tbody>
          {#each categories as category (category.id)}
            <tr>
              <th scope="row" class="align-middle text-center" style="width: 50px;">
                <div class="dropdown position-static">
                  <button
                    type="button"
                    class="btn btn-link"
                    data-bs-toggle="dropdown"
                    title="İşlemler"
                    aria-label="İşlemler">
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-pen me-2"></i> Düzenle
                    </button>
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-trash me-2 text-danger"></i> Sil
                    </button>
                  </div>
                </div>
              </th>
              <td class="align-middle">
                <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded overflow-hidden" style="width: 40px; height: 40px;">
                  {#if category.image}
                    <img src={category.image} alt={category.name} class="w-100 h-100 object-fit-cover" />
                  {:else}
                    <img src="/assets/images/category.png" alt={category.name} class="w-100 h-100 object-fit-cover opacity-50" />
                  {/if}
                </div>
              </td>
              <td class="align-middle">
                <div class="d-flex align-items-center gap-3">
                  <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded" style="width: 32px; height: 32px; flex-shrink: 0;">
                    <i class="fas {category.icon} fs-6" style="color: {category.color}"></i>
                  </div>
                  <div>
                    <div class="fw-bold">{category.name}</div>
                    <div class="small text-muted text-truncate d-none d-md-block" style="max-width: 300px;">
                      {category.description || '-'}
                    </div>
                  </div>
                </div>
              </td>
              <td class="align-middle text-center">
                {#if category.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}
              </td>
              <td class="align-middle text-center text-nowrap">
                <span class="badge text-bg-primary fw-normal">{category.productsCount} Ürün</span>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>

      <div class="card-footer">
         <Pagination
            {page}
            totalPage={5}
            {...paginationEvents} />
      </div>
    {:else if view === 'sort'}
      <div class="card-body">
        <div class="alert alert-info border-0 bg-info-subtle text-info-emphasis d-flex align-items-center mb-4">
          <i class="fas fa-info-circle fs-4 me-3"></i>
          <div>
            Satırın üstüne, ortasına veya altına sürükleyerek kategori konumunu seçin.
          </div>
        </div>

        {#snippet categoryRows(items)}
          {#each items as category (category.id)}
            <div
              class="category-sort-item"
              class:category-sort-before={isDragTarget(category.id, 'before')}
              class:category-sort-after={isDragTarget(category.id, 'after')}
              role="listitem"
              animate:flip={{ duration: 150 }}>
              <div
                class="list-group-item category-row d-flex align-items-center gap-3 p-3 bg-body text-start w-100"
                class:opacity-50={category.id === draggedId}
                class:bg-body-tertiary={category.id === draggedId}
                class:category-row-active={isDragTarget(category.id, 'inside')}
                class:category-row-disabled={draggedId !== null && !canDropOnCategory(category.id)}
                role="group"
                data-category-row="true"
                data-category-id={category.id}
                data-drop-position="row"
                draggable="true"
                aria-label="{category.name} kategorisini taşı"
                ondragstart={(e) => onDragStart(e, category.id)}
                ondragend={onDragEnd}>
                <div class="dropdown">
                  <button
                    type="button"
                    class="btn btn-link text-body-emphasis p-0"
                    data-bs-toggle="dropdown"
                    title="İşlemler"
                    aria-label="İşlemler"
                    draggable="false">
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-pen me-2"></i> Düzenle
                    </button>
                    <button type="button" class="dropdown-item">
                      <i class="fas fa-trash me-2 text-danger"></i> Sil
                    </button>
                  </div>
                </div>

                <span class="text-muted cursor-grab">
                  <i class="fas fa-grip-vertical"></i>
                </span>

                <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded overflow-hidden sort-thumb">
                  {#if category.image}
                    <img src={category.image} alt={category.name} class="w-100 h-100 object-fit-cover" />
                  {:else}
                    <img src="/assets/images/category.png" alt={category.name} class="w-100 h-100 object-fit-cover opacity-50" />
                  {/if}
                </div>

                <div class="d-flex align-items-center gap-3 flex-grow-1 overflow-hidden">
                  <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded category-icon flex-shrink-0">
                    <i class="fas {category.icon} fs-6" style="color: {category.color}"></i>
                  </div>
                  <div class="overflow-hidden">
                    <div class="fw-medium">{category.name}</div>
                    <div class="small text-muted text-truncate d-none d-md-block" style="max-width: 300px;">
                      {category.description || '-'}
                    </div>
                  </div>
                </div>

                {#if isDragTarget(category.id, 'inside')}
                  <span class="badge text-bg-primary fw-normal">Alt kategori yap</span>
                {/if}

                {#if category.status === 'active'}
                  <span class="badge text-bg-success">Aktif</span>
                {:else}
                  <span class="badge text-bg-danger">Pasif</span>
                {/if}

                <span class="badge text-bg-secondary fw-normal">{category.productsCount} Ürün</span>
              </div>

              {#if category.children?.length}
                <div class="category-children">
                  {@render categoryRows(category.children)}
                </div>
              {/if}
            </div>
          {/each}
        {/snippet}

        <div
          class="list-group list-group-flush category-sort-list"
          data-category-sort-list="true"
          role="list"
          ondragover={onSortListDragOver}
          ondragleave={onSortListDragLeave}
          ondrop={onSortListDrop}>
          {@render categoryRows(categories)}
        </div>
      </div>
    {/if}
  </div>
</MarketLayout>

<style>
  .category-sort-item {
    background: var(--bs-body-bg);
    position: relative;
  }

  .category-sort-list {
    gap: 0.125rem;
  }

  .category-row {
    border: 0 !important;
    box-shadow: none !important;
    border-radius: 0.375rem;
    cursor: grab;
    transition: background-color 0.15s, color 0.15s, opacity 0.15s;
  }

  .category-row:active {
    cursor: grabbing;
  }

  .category-row-disabled {
    cursor: not-allowed;
  }

  .category-row-active:not(:disabled) {
    background: var(--bs-primary-bg-subtle) !important;
    color: var(--bs-primary-text-emphasis);
  }

  .category-sort-before::before,
  .category-sort-after::after {
    content: '';
    position: absolute;
    left: 0.75rem;
    right: 0.75rem;
    height: 2px;
    border-radius: 999px;
    background: var(--bs-primary);
    pointer-events: none;
    z-index: 1;
  }

  .category-sort-before::before {
    top: 0;
  }

  .category-sort-after::after {
    bottom: 0;
  }

  .category-icon {
    width: 32px;
    height: 32px;
  }

  .sort-thumb {
    width: 40px;
    height: 40px;
    flex-shrink: 0;
  }

  .category-children {
    margin-left: 1.5rem;
    padding-left: 0.5rem;
  }

</style>
