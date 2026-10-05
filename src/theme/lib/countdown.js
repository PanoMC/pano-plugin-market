// Sale countdown helpers (14 §8.3). Pure: no SDK, no clock access (callers pass `now`).

export const HOUR_MS = 3600 * 1000;
export const DAY_MS = 24 * HOUR_MS;

/** The countdown is shown only within this window before the end of a sale. */
export const COUNTDOWN_WINDOW_MS = 72 * HOUR_MS;

/** The public store response is cached for 30 s, so the refetch after a sale ends waits 35 s. */
export const REFETCH_DELAY_MS = 35 * 1000;

const pad = (n) => String(n).padStart(2, '0');

/**
 * Milliseconds as "2d 03:04:05"; the day part is omitted when it is 0 ("03:04:05").
 * A negative, zero or non-numeric value renders "00:00:00". Seconds are rounded down.
 */
export function format(ms) {
  const total = Math.max(0, Math.floor((Number(ms) || 0) / 1000));
  const days = Math.floor(total / 86400);
  const hours = Math.floor((total % 86400) / 3600);
  const minutes = Math.floor((total % 3600) / 60);
  const seconds = total % 60;
  const clock = `${pad(hours)}:${pad(minutes)}:${pad(seconds)}`;

  return days > 0 ? `${days}d ${clock}` : clock;
}

/** Milliseconds left until `endsAt` (epoch ms); 0 when unknown or already over. */
export function remaining(endsAt, now) {
  const end = Number(endsAt);
  if (!Number.isFinite(end) || end <= 0) return 0;

  return Math.max(0, end - now);
}

/** True while 0 < endsAt - now <= 72 h. */
export function withinWindow(endsAt, now) {
  const left = remaining(endsAt, now);

  return left > 0 && left <= COUNTDOWN_WINDOW_MS;
}
