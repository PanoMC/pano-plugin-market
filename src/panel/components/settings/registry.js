// Section key -> component of the settings page (13 §17). A section without an entry renders the
// hook `market:panel:settings:section:<key>` (PluginHook) instead; adding a section = one line here.
import BillingSettings from './BillingSettings.svelte';
import CheckoutSettings from './CheckoutSettings.svelte';
import CreditSettings from './CreditSettings.svelte';
import CurrencySettings from './CurrencySettings.svelte';
import GeneralSettings from './GeneralSettings.svelte';
import LegalSettings from './LegalSettings.svelte';
import PaymentMethods from './PaymentMethods.svelte';

export const SECTION_COMPONENTS = {
  general: GeneralSettings,
  checkout: CheckoutSettings,
  currencies: CurrencySettings,
  billing: BillingSettings,
  legal: LegalSettings,
  payments: PaymentMethods,
  credits: CreditSettings,
};
