<CartOffcanvas />

<script>
  // Compatibility shim: the cart offcanvas moved to ./cart/CartOffcanvas.svelte (MTU-03). StorePage still
  // imports this path and passes productMap / settings, which the cart no longer needs; MTU-04 rewrites
  // StorePage and deletes this file.
  import CartOffcanvas from './cart/CartOffcanvas.svelte';
</script>
