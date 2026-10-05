// Section key -> component of the settings page (13 §17). A section without an entry renders the
// hook `market:panel:settings:section:<key>` (PluginHook) instead; adding a section = one line here.
import BillingSettings from './BillingSettings.svelte';
import CheckoutSettings from './CheckoutSettings.svelte';
import CreditSettings from './CreditSettings.svelte';
import CurrencySettings from './CurrencySettings.svelte';
import GeneralSettings from './GeneralSettings.svelte';
import LegalSettings from './LegalSettings.svelte';
import PaymentMethods from './PaymentMethods.svelte';
import DeliverySettings from './DeliverySettings.svelte';
import HealthPanel from './HealthPanel.svelte';
import MailSettings from './MailSettings.svelte';
import MinecraftSettings from './MinecraftSettings.svelte';
import SecuritySettings from './SecuritySettings.svelte';
import StoreModuleSettings from './StoreModuleSettings.svelte';
import WebhookDeliveries from './WebhookDeliveries.svelte';
import Webhooks from './Webhooks.svelte';

export const SECTION_COMPONENTS = {
  general: GeneralSettings,
  checkout: CheckoutSettings,
  currencies: CurrencySettings,
  billing: BillingSettings,
  legal: LegalSettings,
  payments: PaymentMethods,
  credits: CreditSettings,
  delivery: DeliverySettings,
  modules: StoreModuleSettings,
  security: SecuritySettings,
  mail: MailSettings,
  minecraft: MinecraftSettings,
  health: HealthPanel,
  webhooks: Webhooks,
  'webhook-deliveries': WebhookDeliveries,
};
