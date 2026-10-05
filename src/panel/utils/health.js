// View model of the health panel (13 §17 health): GET /health -> the cards, tables and flags the
// component renders. Pure, tolerant of missing fields (the report grows with the backend slices).
import { serverRow } from './minecraft-settings.js';

/** A job whose last run is more than five minutes old is shown in red. */
export const LAG_DANGER_SECONDS = 300;

/** Queue stat cards: id (locale suffix), health key, `text-bg-*` variant (design/cards-colored.md). */
export const QUEUE_CARDS = [
  { id: 'deliveries-pending', key: 'deliveriesPending', variant: 'secondary' },
  { id: 'deliveries-failed', key: 'deliveriesFailed', variant: 'danger' },
  { id: 'mails-pending', key: 'mailsPending', variant: 'secondary' },
  { id: 'webhooks-pending', key: 'webhooksPending', variant: 'secondary' },
  { id: 'deferred-events', key: 'deferredEvents', variant: 'warning' },
  { id: 'failed-events', key: 'failedEvents', variant: 'danger' },
];

const count = (value) => {
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : 0;
};

const list = (value) => (Array.isArray(value) ? value : []);

/** `lagSeconds` of a job row as a number (null when unknown). */
const lag = (value) => {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) && n >= 0 ? n : null;
};

export const lagIsDanger = (seconds) => seconds !== null && seconds > LAG_DANGER_SECONDS;

/** Text of a missing / unfixed schema object: kinds and details are server strings, rendered as text. */
const text = (value) => (typeof value === 'string' ? value : JSON.stringify(value));

export function healthModel(health) {
  const report = health && typeof health === 'object' ? health : {};
  const queues = report.queues && typeof report.queues === 'object' ? report.queues : {};
  const schema = report.schema && typeof report.schema === 'object' ? report.schema : {};
  const credits = report.credits && typeof report.credits === 'object' ? report.credits : null;

  const jobs = list(report.jobs).map((job) => {
    const lagSeconds = lag(job?.lagSeconds);
    return {
      name: String(job?.name ?? ''),
      lastRunAt: job?.lastRunAt ?? null,
      lagSeconds,
      lagDanger: lagIsDanger(lagSeconds),
      lastError: job?.lastError ? String(job.lastError) : '',
    };
  });

  return {
    runtimeState: report.runtimeState ? String(report.runtimeState) : '',
    schemaOk: schema.ok !== false,
    missing: list(schema.missing).map(text),
    unfixed: list(schema.unfixed).map(text),
    jobs,
    queues: QUEUE_CARDS.map((card) => ({ ...card, value: count(queues[card.key]) })),
    providers: list(report.providers).map((p) => ({ id: String(p?.id ?? ''), state: p?.state })),
    servers: list(report.servers).map((s) => ({
      id: s?.id,
      marketState: serverRow(s).state,
      waiting: serverRow(s).waiting,
    })),
    credits: credits
      ? {
          ok: credits.ok !== false,
          checkedAt: credits.checkedAt ?? null,
          problems: list(credits.problems).map(text),
        }
      : null,
    mail: report.mail ? String(report.mail) : '',
    mailEnabled: report.mailEnabled !== false,
    ipTrustWarning: report.ipTrust === 'UNCONFIGURED_PROXY',
    lockedSubjects: count(report.lockedSubjects),
    rejectedEventsLastHour: count(report.rejectedEventsLastHour),
  };
}

/** Query of the "Re-check" button: the credit self-check runs again on the server. */
export const RECHECK_CREDITS_PATH = '/health?recheck=credits';

/** True when the report is a usable object (a failed GET gives `{ error }`). */
export const isHealthReport = (value) =>
  value !== null && typeof value === 'object' && !value.error;
