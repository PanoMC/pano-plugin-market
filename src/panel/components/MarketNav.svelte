<script>
  import { page } from '@panomc/sdk/svelte';
  import { PageActions, PageNav, PageNavItem } from '@panomc/sdk/components/panel';
  import { _ } from '../../main';
</script>

<PageActions>
  <PageNav slot="middle">
    <PageNavItem href="/market" active={$page.url.pathname === '/market'}>İstatistikler</PageNavItem>
    <PageNavItem href="/market/categories" active={$page.url.pathname.startsWith('/market/categories')}>Kategoriler</PageNavItem>
    <PageNavItem href="/market/products" active={$page.url.pathname.startsWith('/market/products')}>Ürünler</PageNavItem>
    <PageNavItem href="/market/comparisons" active={$page.url.pathname.startsWith('/market/comparisons')}>Karşılaştırma Tabloları</PageNavItem>
    <PageNavItem href="/market/settings" active={$page.url.pathname.startsWith('/market/settings')}>Ayarlar & Ödemeler</PageNavItem>
  </PageNav>
</PageActions>
