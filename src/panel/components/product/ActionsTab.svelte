<!-- Placeholder written by MPU-07 so the product form shell compiles; MPU-08 replaces this file.
     Props contract of every tab: product (bindable form state), errors (dotted path -> code),
     ctx (GET /context), servers, categories, products. -->
<div class="card animate__animated animate__fadeIn">
  <div class="card-body text-center text-body-secondary py-5">
    <i class="fa-solid fa-hammer mb-2 fs-3" aria-hidden="true"></i>
    <div>
      {$_('pages.create-product.pending-tab', {
        values: { tab: $_('pages.create-product.tabs.actions') },
      })}
    </div>
  </div>
</div>

<script>
  import { _ } from '../../../i18n';

  let {
    product = $bindable(),
    errors = {},
    ctx = null,
    servers = [],
    categories = [],
    products = [],
  } = $props();
</script>
