// Badge classes of the sixteen status kinds (13 §3.5). Pure.
const SUCCESS = 'text-bg-success';
const WARNING = 'text-bg-warning';
const DANGER = 'text-bg-danger';
const INFO = 'text-bg-info';
const SECONDARY = 'text-bg-secondary';

// kind -> { class: [values] }
const TABLE = {
  order: {
    [SUCCESS]: ['COMPLETED'],
    [WARNING]: ['PENDING', 'REVIEW', 'PARTIALLY_REFUNDED'],
    [DANGER]: ['FAILED', 'CHARGEBACK'],
    [INFO]: ['REFUNDED'],
    [SECONDARY]: ['CANCELLED', 'EXPIRED'],
  },
  payment: {
    [SUCCESS]: ['SUCCEEDED'],
    [WARNING]: ['CREATED', 'PENDING', 'PROCESSING', 'REVIEW'],
    [DANGER]: ['FAILED'],
    [SECONDARY]: ['CANCELLED', 'EXPIRED'],
  },
  refund: {
    [SUCCESS]: ['SUCCEEDED'],
    [WARNING]: ['REQUESTED', 'PENDING'],
    [DANGER]: ['FAILED'],
    [SECONDARY]: ['CANCELLED'],
  },
  delivery: {
    [SUCCESS]: ['CONFIRMED'],
    [WARNING]: ['PENDING', 'SENDING', 'SCHEDULED', 'WAITING_SERVER', 'QUEUED', 'WAITING_PLAYER'],
    [DANGER]: ['FAILED'],
    [INFO]: ['SENT'],
    [SECONDARY]: ['CANCELLED'],
  },
  fulfillment: {
    [SUCCESS]: ['FULFILLED'],
    [WARNING]: ['PENDING', 'PARTIAL'],
    [DANGER]: ['FAILED'],
    [SECONDARY]: ['NONE', 'REVOKED'],
  },
  shipping: {
    [SUCCESS]: ['DELIVERED'],
    [WARNING]: ['PENDING', 'PARTIAL'],
    [INFO]: ['SHIPPED'],
    [SECONDARY]: ['NOT_REQUIRED', 'RETURNED'],
  },
  shipment: {
    [SUCCESS]: ['DELIVERED'],
    [WARNING]: ['CREATED', 'LABEL_READY', 'RETURNING'],
    [DANGER]: ['EXCEPTION', 'LOST'],
    [INFO]: ['IN_TRANSIT', 'OUT_FOR_DELIVERY'],
    [SECONDARY]: ['CANCELLED', 'RETURNED'],
  },
  subscription: {
    [SUCCESS]: ['ACTIVE', 'COMPLETED'],
    [WARNING]: ['PENDING', 'PAST_DUE', 'PAUSED'],
    [SECONDARY]: ['CANCELLED', 'EXPIRED'],
  },
  dispute: {
    [SUCCESS]: ['WON'],
    [WARNING]: ['OPEN', 'INQUIRY'],
    [DANGER]: ['LOST'],
    [SECONDARY]: ['NONE', 'CLOSED'],
  },
  webhook: {
    [SUCCESS]: ['SUCCEEDED'],
    [WARNING]: ['PENDING', 'SENDING', 'FAILED'],
    [DANGER]: ['DEAD'],
  },
  earning: {
    [SUCCESS]: ['PAID'],
    [WARNING]: ['PENDING'],
    [DANGER]: ['REVERSED'],
    [INFO]: ['AVAILABLE'],
  },
  payout: {
    [SUCCESS]: ['PAID'],
    [WARNING]: ['PENDING'],
    [DANGER]: ['FAILED'],
    [SECONDARY]: ['CANCELLED'],
  },
  provider: {
    [SUCCESS]: ['ACTIVE'],
    [WARNING]: ['NOT_CONFIGURED'],
    [DANGER]: ['INCOMPATIBLE', 'UNAVAILABLE'],
    [SECONDARY]: ['DISABLED'],
  },
  entitlement: {
    [SUCCESS]: ['ACTIVE'],
    [DANGER]: ['REVOKED'],
    [INFO]: ['UPGRADED'],
    [SECONDARY]: ['EXPIRED'],
  },
  event: {
    [SUCCESS]: ['PROCESSED'],
    [WARNING]: ['RECEIVED', 'DEFERRED'],
    [DANGER]: ['FAILED', 'REJECTED'],
    [SECONDARY]: ['DUPLICATE', 'SUPERSEDED'],
  },
  mail: {
    [SUCCESS]: ['SENT'],
    [WARNING]: ['PENDING', 'SENDING'],
    [DANGER]: ['FAILED'],
    [SECONDARY]: ['SKIPPED'],
  },
};

export const STATUS_KINDS = Object.keys(TABLE);

/** Every value of a kind that the table maps (used by tests and the locale check). */
export function statusValues(kind) {
  return Object.values(TABLE[kind] ?? {}).flat();
}

const LOOKUP = new Map(
  Object.entries(TABLE).map(([kind, classes]) => [
    kind,
    new Map(Object.entries(classes).flatMap(([cls, values]) => values.map((v) => [v, cls]))),
  ]),
);

/** `text-bg-*` class; an unknown kind or value is `text-bg-secondary`. */
export function badgeClass(kind, value) {
  return LOOKUP.get(kind)?.get(value) ?? SECONDARY;
}

/** True when the value is a known value of the kind (the badge shows the raw value otherwise). */
export function isKnownStatus(kind, value) {
  return LOOKUP.get(kind)?.has(value) ?? false;
}
