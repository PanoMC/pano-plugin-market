<script>
  import MarketLayout from '../layouts/MarketLayout.svelte';
  import { _ } from '../../i18n';
</script>

<MarketLayout>
  <div class="card animate__animated animate__fadeIn">
    <div class="card-body p-5 text-center">
      <div class="mb-4">
        <i class="fas fa-gift fa-4x text-primary opacity-25"></i>
      </div>
      <h4 class="fw-bold">{$_('nav-gifts')}</h4>
      <p class="text-secondary">Bu sayfa yakında tasarlanacaktır.</p>
    </div>
  </div>
</MarketLayout>
