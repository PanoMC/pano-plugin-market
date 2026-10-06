<script module>
  import ApiUtil, { buildQueryParams } from '@panomc/sdk/utils/api';

  /**
   * @type {import("@sveltejs/kit").PageLoad}
   */
  export async function load(event) {
    const {
      parent,
      url: { searchParams },
    } = event;
    const { pageTitle } = await parent();
    pageTitle.set('plugins.pano-plugin-market.pages.categories.title');

    const search = searchParams.get('search');

    const queryParams = buildQueryParams({
      search,
    });

    try {
      const res = await ApiUtil.get({
        path: '/api/panel/market/categories' + queryParams,
        request: event,
      });

      if (res.error) throw res.error;

      return { data: res };
    } catch (e) {
      console.error('[Market] Failed to load categories', e);
      return { data: { categories: [], categoryCount: 0 } };
    }
  }
</script>

<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { CardHeader, CardFilters, CardFiltersItem, SearchInput, NoContent } from '@panomc/sdk/components/panel';
  import { flip } from 'svelte/animate';
  import { base, page, goto } from '@panomc/sdk/svelte';
  import { _, showSuccessToast, showErrorToast } from '../../i18n';
  import ConfirmModal from '../components/ConfirmModal.svelte';
  import CreateCategoryModal from '../components/modals/CreateCategoryModal.svelte';
  import { sectionsFor } from '../navigation.js';
  import { call, errorKey, marketPath } from '../utils/api.js';
  import { siblingMove } from '../utils/category-gift.js';

  let { data } = $props();

  // View toggle (table | sort) lives in the URL (?view=) exactly like every other
  // view/section toggle in this plugin, so it survives the panel host's remount on
  // each load() re-run ({#key data}) — otherwise reordering/searching would bounce
  // the user out of Sort mode — and stays deep-linkable. The default 'table' keeps
  // the URL param-free.
  let view = $derived($page.url.searchParams.get('view') === 'sort' ? 'sort' : 'table');
  let draggedId = $state(null);
  let dragTarget = $state(null);
  let pendingDragPoint = null;
  let previewFrame = null;
  let searching = $state(false);

  // Optimistic tree override applied while drag-sorting; cleared once the
  // reconciling reload lands so the derived list takes over again.
  let optimisticCategories = $state(null);

  // Search lives in the URL (?search=); read it straight from the URL so the
  // address bar stays deep-linkable and the back button is correct.
  let searchValue = $derived($page.url.searchParams.get('search') || '');

  // Category tree data comes from load(); re-derives whenever load() re-runs.
  let categoriesData = $derived(data.categories || []);
  let categoryCount = $derived(data.categoryCount ?? (data.categories?.length ?? 0));

  let mappedCategories = $derived(categoriesData.map(mapCategory));
  let categories = $derived(optimisticCategories ?? mappedCategories);

  let confirmModal = $state(null);
  const user = $derived($page.data?.user);
  let isEditModal = $state(false);
  let selectedCategory = $state(null);

  function openCreateModal() {
    isEditModal = false;
    selectedCategory = null;
  }

  function openEditModal(category) {
    isEditModal = true;
    selectedCategory = category;
  }

  // Enum name (ACTIVE/INACTIVE/HIDDEN) -> lowercase display value the markup/modal expect.
  function mapCategory(node) {
    return {
      id: node.id,
      name: node.name,
      description: node.description,
      icon: node.icon || 'fa-folder',
      color: node.color || '#0d6efd',
      status: (node.status || 'ACTIVE').toLowerCase(),
      tiered: node.tiered === true,
      upgradeMode: node.upgradeMode || 'DIFFERENCE',
      productsCount: node.productsCount ?? 0,
      parentId: node.parentId ?? null,
      position: node.position ?? 0,
      imageFileName: node.imageFileName || null,
      image: node.imageFileName
        ? `${base}/api/panel/market/categories/image/${node.imageFileName}`
        : null,
      children: (node.children || []).map(mapCategory),
    };
  }

  // Navigate to the same route with the search encoded in the URL so the address
  // bar stays deep-linkable; load() re-runs with the new param. The panel host
  // remounts the plugin page on every load() re-run ({#key data}); that remount
  // is the accepted cost of URL-driven navigation here.
  function refreshData() {
    searching = true;
    const queryParams = buildQueryParams({
      search: $page.url.searchParams.get('search') || null,
      view: $page.url.searchParams.get('view') === 'sort' ? 'sort' : null,
    });
    return goto(`${base}/market/categories${queryParams}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true,
    });
  }

  function onSearchChange(value) {
    searching = true;
    const queryParams = buildQueryParams({
      search: value ? value.trim() || null : null,
      view: $page.url.searchParams.get('view') === 'sort' ? 'sort' : null,
    });
    return goto(`${base}/market/categories${queryParams}`, {
      invalidateAll: true,
      keepFocus: true,
      noscroll: true,
    });
  }

  // Toggle table/sort through the router so the choice lives in the URL and
  // survives the remount; preserve the active ?search. No invalidateAll — the
  // list data doesn't depend on the view, and `view` re-derives from $page.
  function setView(next) {
    if (next === view) return;
    const queryParams = buildQueryParams({
      view: next === 'sort' ? 'sort' : null,
      search: $page.url.searchParams.get('search') || null,
    });
    return goto(`${base}/market/categories${queryParams}`, {
      replaceState: true,
      keepFocus: true,
      noscroll: true,
    });
  }

  // A modal is hidden before the page is re-loaded; Bootstrap's fade takes 300 ms.
  function afterModalHidden(run) {
    setTimeout(run, 350);
  }

  function deleteCategory(category) {
    confirmModal?.open({
      icon: 'fa-solid fa-trash',
      title: $_('pages.categories.delete-title'),
      description: $_('pages.categories.confirm-delete', { values: { name: category.name } }),
      confirmLabel: $_('common.delete'),
      variant: 'danger',
      onConfirm: async () => {
        const result = await call(ApiUtil.delete({ path: marketPath(`/categories/${category.id}`) }));
        if (!result.ok) {
          // CATEGORY_IN_USE keeps the row; a missing row (404) or a stale tree is refreshed.
          showErrorToast($_(errorKey(result.error)));
          if (['NOT_FOUND', 'CATEGORY_IN_USE'].includes(result.error)) {
            afterModalHidden(() => refreshData());
          }
          return false;
        }
        showSuccessToast($_('pages.categories.toast-delete-success'));
        afterModalHidden(() => refreshData());
      },
    });
  }

  // Move Up / Move Down: the keyboard alternative to drag-and-drop (same POST /categories/sort).
  async function moveCategory(category, dir) {
    const body = siblingMove(categories, category.id, dir);
    if (!body) return;
    const result = await call(ApiUtil.post({ path: marketPath('/categories/sort'), body }));
    if (!result.ok) showErrorToast($_(errorKey(result.error)));
    await refreshData();
  }

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

    optimisticCategories = nextCategories;
    return true;
  }

  // Persist a single applied move to the backend; reload to reconcile the tree on failure.
  async function persistCategoryMove(sourceId, target) {
    const positionMap = { before: 'BEFORE', after: 'AFTER', inside: 'INSIDE' };

    const body = target.id === null
      ? { id: sourceId, position: 'ROOT' }
      : { id: sourceId, position: positionMap[target.position], targetId: target.id };

    try {
      const result = await ApiUtil.post({
        path: '/api/panel/market/categories/sort',
        body,
      });

      if (result.error) throw result.error;

      // Reconcile the optimistic tree against the server's canonical order.
      await refreshData();
    } catch (e) {
      console.error('[Market] Failed to sort categories', e);
      showErrorToast($_('pages.categories.toast-sort-error'));
      // Revert to the server's order.
      await refreshData();
    } finally {
      optimisticCategories = null;
    }
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
      const sourceId = draggedId;
      applyDropTarget(target);
      if (applyCategoryMove(target)) {
        persistCategoryMove(sourceId, target);
      }
    }
    onDragEnd();
  }
</script>

<MarketLayout area="catalog" sections={sectionsFor('catalog', user)} active="categories">
  {#snippet right()}
    <button type="button" class="btn btn-secondary" data-bs-toggle="modal" data-bs-target="#createCategoryModal" onclick={openCreateModal}>
      <i class="fa-solid fa-plus"></i>
      <span class="d-lg-inline d-none ms-2">{$_('pages.categories.add-category')}</span>
    </button>
  {/snippet}

  {#if view === 'sort' && categories.length > 0}
    <div class="alert alert-info d-flex align-items-center mb-0">
      <i class="fas fa-info-circle me-3"></i>
      {$_('pages.categories.sort-hint')}
    </div>
  {/if}

  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.categories.category-count', { values: { count: categoryCount } })}
      </div>
      <div slot="middle" style="width: 250px;">
        <SearchInput
          autofocus
          initialValue={searchValue}
          {searching}
          placeholderKey="plugins.pano-plugin-market.search.categories"
          onchange={onSearchChange} />
      </div>
      <CardFilters slot="right">
        <CardFiltersItem button active={view === 'table'} onclick={() => setView('table')}>{$_('pages.categories.view-table')}</CardFiltersItem>
        <CardFiltersItem button active={view === 'sort'} onclick={() => setView('sort')}>{$_('pages.categories.view-sort')}</CardFiltersItem>
      </CardFilters>
    </CardHeader>

    {#if categories.length === 0}
      <NoContent />
    {:else if view === 'table'}
      <div class="table-responsive">
      <table class="table table-hover align-middle text-nowrap">
        <thead>
          <tr>
            <th scope="col" style="width: 50px;"></th>
            <th scope="col" style="width: 60px;"></th>
            <th scope="col">{$_('pages.categories.table.category')}</th>
            <th scope="col" class="text-center" style="width: 120px;">{$_('common.status')}</th>
            <th scope="col" class="text-center" style="width: 150px;">{$_('pages.categories.table.product-count')}</th>
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
                    title={$_('common.actions')}
                    aria-label={$_('common.actions')}>
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createCategoryModal" onclick={() => openEditModal(category)}>
                      <i class="fas fa-pen me-2"></i> {$_('common.edit')}
                    </button>
                    <button type="button" class="dropdown-item text-danger" onclick={() => deleteCategory(category)}>
                      <i class="fas fa-trash me-2"></i> {$_('common.delete')}
                    </button>
                  </div>
                </div>
              </th>
              <td class="align-middle">
                <a href="#" class="d-flex align-items-center justify-content-center bg-primary-subtle rounded overflow-hidden text-decoration-none focus-ring" style="width: 40px; height: 40px;" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createCategoryModal" onclick={(e) => { e.preventDefault(); openEditModal(category); }}>
                  {#if category.image}
                    <img src={category.image} alt={category.name} class="w-100 h-100 object-fit-cover" />
                  {:else}
                    <!-- Premium vector category icon representing default folder/grid -->
                    <svg class="w-100 h-100 p-2 text-primary opacity-75" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <rect x="3" y="3" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5"/>
                      <rect x="14" y="3" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5"/>
                      <rect x="3" y="14" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5"/>
                      <rect x="14" y="14" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5" class="opacity-50"/>
                    </svg>
                  {/if}
                </a>
              </td>
              <td class="align-middle">
                <a href="#" class="d-flex align-items-center gap-3 text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createCategoryModal" onclick={(e) => { e.preventDefault(); openEditModal(category); }}>
                  <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded" style="width: 32px; height: 32px; flex-shrink: 0;">
                    <i class="fas {category.icon} fs-6" style="color: {category.color}"></i>
                  </div>
                  <div>
                    <div class="">
                      {category.name}
                      {#if category.tiered}
                        <span class="badge text-bg-info ms-1">{$_('pages.categories.tiered')}</span>
                      {/if}
                    </div>
                    <div class="small text-truncate d-none d-md-block" style="max-width: 300px;">
                      {category.description || '-'}
                    </div>
                  </div>
                </a>
              </td>
              <td class="align-middle text-center">
                {#if category.status === 'active'}
                  <span class="badge text-bg-success">{$_('common.active')}</span>
                {:else if category.status === 'hidden'}
                  <span class="badge text-bg-secondary">{$_('common.hidden')}</span>
                {:else}
                  <span class="badge text-bg-danger">{$_('common.inactive')}</span>
                {/if}
              </td>
              <td class="align-middle text-center">
                <span>{$_('pages.categories.product-count-value', { values: { count: category.productsCount } })}</span>
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    {:else if view === 'sort'}
      <div class="card-body overflow-x-auto">

        {#snippet categoryRows(items)}
          {#each items as category (category.id)}
            <div
              class="category-sort-item"
              class:category-sort-before={isDragTarget(category.id, 'before')}
              class:category-sort-after={isDragTarget(category.id, 'after')}
              role="listitem"
              animate:flip={{ duration: 150 }}>
              <div
                class="list-group-item category-row d-flex align-items-center gap-2 gap-md-3 p-2 p-md-3 bg-body text-start w-100 text-nowrap"
                class:opacity-50={category.id === draggedId}
                class:bg-body-tertiary={category.id === draggedId}
                class:category-row-active={isDragTarget(category.id, 'inside')}
                class:category-row-disabled={draggedId !== null && !canDropOnCategory(category.id)}
                role="group"
                data-category-row="true"
                data-category-id={category.id}
                data-drop-position="row"
                draggable="true"
                aria-label={$_('pages.categories.drag-aria', { values: { name: category.name } })}
                ondragstart={(e) => onDragStart(e, category.id)}
                ondragend={onDragEnd}>
                <div class="dropdown me-2">
                  <button
                    type="button"
                    class="btn btn-link text-body-emphasis p-0"
                    data-bs-toggle="dropdown"
                    title={$_('common.actions')}
                    aria-label={$_('common.actions')}
                    draggable="false">
                    <span class="fas fa-ellipsis-v"></span>
                  </button>
                  <div class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                    <button type="button" class="dropdown-item" data-bs-toggle="modal" data-bs-target="#createCategoryModal" onclick={() => openEditModal(category)}>
                      <i class="fas fa-pen me-2"></i> {$_('common.edit')}
                    </button>
                    {#if siblingMove(categories, category.id, 'up')}
                      <button type="button" class="dropdown-item" onclick={() => moveCategory(category, 'up')}>
                        <i class="fas fa-arrow-up me-2"></i> {$_('common.move-up')}
                      </button>
                    {/if}
                    {#if siblingMove(categories, category.id, 'down')}
                      <button type="button" class="dropdown-item" onclick={() => moveCategory(category, 'down')}>
                        <i class="fas fa-arrow-down me-2"></i> {$_('common.move-down')}
                      </button>
                    {/if}
                    <button type="button" class="dropdown-item text-danger" onclick={() => deleteCategory(category)}>
                      <i class="fas fa-trash me-2"></i> {$_('common.delete')}
                    </button>
                  </div>
                </div>

                <span class="cursor-grab">
                  <i class="fas fa-grip-vertical"></i>
                </span>

                <a href="#" class="d-none d-sm-flex align-items-center justify-content-center bg-primary-subtle rounded overflow-hidden sort-thumb text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createCategoryModal" onclick={(e) => { e.preventDefault(); openEditModal(category); }}>
                  {#if category.image}
                    <img src={category.image} alt={category.name} class="w-100 h-100 object-fit-cover" />
                  {:else}
                    <!-- Premium vector category icon representing default folder/grid -->
                    <svg class="w-100 h-100 p-2 text-primary opacity-75" viewBox="0 0 24 24" fill="none" xmlns="http://www.w3.org/2000/svg">
                      <rect x="3" y="3" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5"/>
                      <rect x="14" y="3" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5"/>
                      <rect x="3" y="14" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5"/>
                      <rect x="14" y="14" width="7" height="7" rx="1.5" stroke="currentColor" stroke-width="1.5" class="opacity-50"/>
                    </svg>
                  {/if}
                </a>

                <a href="#" class="d-flex align-items-center gap-2 gap-md-3 flex-grow-1 overflow-hidden text-decoration-none focus-ring" title={$_('common.edit')} data-bs-toggle="modal" data-bs-target="#createCategoryModal" onclick={(e) => { e.preventDefault(); openEditModal(category); }}>
                  <div class="d-flex align-items-center justify-content-center bg-primary-subtle rounded category-icon flex-shrink-0">
                    <i class="fas {category.icon} fs-6" style="color: {category.color}"></i>
                  </div>
                  <div class="overflow-hidden">
                    <div class="">
                      {category.name}
                      {#if category.tiered}
                        <span class="badge text-bg-info ms-1">{$_('pages.categories.tiered')}</span>
                      {/if}
                    </div>
                    <div class="small text-truncate d-none d-md-block" style="max-width: 300px;">
                      {category.description || '-'}
                    </div>
                  </div>
                </a>

                {#if isDragTarget(category.id, 'inside')}
                  <span class="badge text-bg-primary d-none d-md-inline-block">{$_('pages.categories.make-subcategory')}</span>
                {/if}
                
                <div class="ms-auto d-flex align-items-center gap-1 gap-md-2">
                  {#if category.status === 'active'}
                    <span class="badge text-bg-success">{$_('common.active')}</span>
                  {:else if category.status === 'hidden'}
                    <span class="badge text-bg-secondary">{$_('common.hidden')}</span>
                  {:else}
                    <span class="badge text-bg-danger">{$_('common.inactive')}</span>
                  {/if}

                  <span>
                    {category.productsCount} <span class="d-none d-md-inline">{$_('pages.categories.product-label')}</span>
                  </span>
                </div>
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

<ConfirmModal bind:this={confirmModal} />
<CreateCategoryModal isEdit={isEditModal} category={selectedCategory} onSaved={refreshData} />

<style>
  .category-sort-item {
    background: var(--bs-body-bg);
    position: relative;
  }

  .category-sort-list {
    gap: 0.125rem;
    min-width: fit-content;
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

  @media (max-width: 576px) {
    .category-children {
      margin-left: 0.75rem;
      padding-left: 0.25rem;
    }
  }

</style>
