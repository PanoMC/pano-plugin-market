// Modals that other slices own and the order detail page only opens (13 §6.2): RefundModal (MPU-05),
// CreateShipmentModal and ShippingAddressModal (MPU-18). A missing component hides its menu item
// (actions.js `withAvailable`): a dead button is worse than none.
//
// To wire one in, import it here and replace `null`. Contract the page uses for every entry:
//   <Modal bind:this={ref} detail={detail} ctx={ctx} user={user} onDone={(toastKey) => ...} onStale={() => ...} />
//   ref.open()
// `onDone(toastKey)` refreshes the order and shows the success toast; `onStale()` refreshes after a
// stale-flag error (INVALID_ORDER_TRANSITION, INVALID_STATE, ORDER_NOT_SHIPPABLE); the modal hides
// itself before calling either.
export const EXTERNAL_MODALS = {
  refund: null,
  createShipment: null,
  editShippingAddress: null,
};
