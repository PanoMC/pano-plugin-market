<div class="vstack gap-3 animate__animated animate__fadeIn">
  <div class="card">
    <CardHeader>
      <div slot="left">
        {$_('pages.create-product.fields.title', { values: { count: product.fields.length } })}
      </div>
      <div slot="right">
        <div class="dropdown">
          <button
            type="button"
            class="btn btn-sm btn-link"
            data-bs-toggle="dropdown"
            aria-expanded="false"
            title={$_('common.actions')}
            aria-label={$_('common.actions')}>
            <i class="fa-solid fa-ellipsis-vertical" aria-hidden="true"></i>
          </button>
          <div class="dropdown-menu dropdown-menu-end animate__animated animate__fadeIn">
            <button
              type="button"
              class="dropdown-item"
              disabled={product.fields.length >= MAX_FIELDS}
              onclick={() => fieldModal?.open({ siblings: product.fields })}>
              <i class="fa-solid fa-plus me-2" aria-hidden="true"></i>
              {$_('pages.create-product.fields.add')}
            </button>
          </div>
        </div>
      </div>
    </CardHeader>

    {#if errors.fields}
      <div class="card-body pb-0">
        <div class="invalid-feedback d-block" data-field="fields">
          {$_(fieldRowErrorKey(errors.fields))}
        </div>
      </div>
    {/if}

    {#if product.fields.length === 0}
      <NoContent icon="" />
    {:else}
      <div class="table-responsive">
        <table class="table table-hover">
          <thead>
            <tr>
              <th class="align-middle text-nowrap" scope="col"></th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.create-product.fields.label')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.create-product.fields.key')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.create-product.fields.type')}
              </th>
              <th class="align-middle text-nowrap" scope="col">
                {$_('pages.create-product.fields.required')}
              </th>
            </tr>
          </thead>
          <tbody>
            {#each product.fields as row, i (i)}
              {@const rowErrors = errorsOf(i)}
              <tr>
                <th class="align-middle" scope="row">
                  <div class="dropdown position-static">
                    <button
                      type="button"
                      class="btn btn-link"
                      data-bs-toggle="dropdown"
                      aria-expanded="false"
                      title={$_('common.actions')}
                      aria-label={$_('common.actions')}>
                      <i class="fas fa-ellipsis-v" aria-hidden="true"></i>
                    </button>
                    <div
                      class="dropdown-menu dropdown-menu-start animate__animated animate__fadeIn">
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={i === 0}
                        onclick={() => move(i, -1)}>
                        {$_('common.move-up')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        disabled={i === product.fields.length - 1}
                        onclick={() => move(i, 1)}>
                        {$_('common.move-down')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item"
                        onclick={() =>
                          fieldModal?.open({
                            field: row,
                            index: i,
                            siblings: product.fields.filter((_row, j) => j !== i),
                          })}>
                        {$_('common.edit')}
                      </button>
                      <button
                        type="button"
                        class="dropdown-item link-danger"
                        onclick={() => remove(i)}>
                        {$_('common.remove')}
                      </button>
                    </div>
                  </div>
                </th>
                <td class="align-middle" data-field="fields.{i}">
                  {row.label}
                  {#each rowErrors as line (line.name)}
                    <div class="invalid-feedback d-block">
                      {$_(fieldPropKey(line.name))}:
                      {$_(fieldRowErrorKey(line.code))}
                    </div>
                  {/each}
                </td>
                <td class="align-middle font-monospace">{row.fieldKey}</td>
                <td class="align-middle">{$_(`modals.product-field.types.${row.type}`)}</td>
                <td class="align-middle">
                  {#if row.required}
                    <span class="badge text-bg-primary">{$_('common.yes')}</span>
                  {:else}
                    <span class="text-body-secondary">{$_('common.no')}</span>
                  {/if}
                </td>
              </tr>
            {/each}
          </tbody>
        </table>
      </div>
    {/if}
  </div>
</div>

<ProductFieldModal bind:this={fieldModal} onSave={save} />

<script>
  import { CardHeader, NoContent } from '@panomc/sdk/components/panel';
  import { _ } from '../../../i18n';
  import ProductFieldModal from '../modals/ProductFieldModal.svelte';
  import { MAX_FIELDS, fieldPropKey, fieldRowErrorKey, moveRow } from './fields.js';

  // Props contract of every tab: product (bindable), errors (dotted path -> code).
  let { product = $bindable(), errors = {} } = $props();

  let fieldModal = $state(null);

  // `{ name, code }` of every error under `fields.<index>.`; the first path segment names the property.
  function errorsOf(index) {
    const prefix = `fields.${index}.`;
    const lines = [];
    for (const [path, code] of Object.entries(errors)) {
      if (!path.startsWith(prefix)) continue;
      const name = path.slice(prefix.length).split('.')[0];
      if (!lines.some((line) => line.name === name)) lines.push({ name, code });
    }
    return lines;
  }

  function save(field, index) {
    if (index === null || index === undefined) product.fields.push(field);
    else product.fields[index] = field;
  }

  function move(index, delta) {
    product.fields = moveRow(product.fields, index, delta);
  }

  function remove(index) {
    product.fields = product.fields.filter((_row, i) => i !== index);
  }
</script>
