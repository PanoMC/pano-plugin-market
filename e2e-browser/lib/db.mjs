// The one place a browser scenario touches the instance database: moving a stored timestamp back in time (E2E-15, scenario 70). A renewal
// is only charged "when its time comes", which a test cannot wait for; the Kotlin E2E classes rewind the same rows (`db.rewind`). Everything
// else the browser scenarios do goes through the HTTP API or the browser. The root password is read from PANO_IT_MARIADB_PASSWORD (the same
// variable the database tests use), handed to the client through its environment (never on the command line, never printed); the database is
// MARKET_E2E_DB of `e2e-instance.sh start`, the container MARKET_E2E_DB_CONTAINER (default pano-web-platform-db-1).
import { execFileSync } from 'node:child_process';

const DAY = 86_400_000;

function run(statement) {
  const password = process.env.PANO_IT_MARIADB_PASSWORD;
  const database = process.env.MARKET_E2E_DB;

  if (!password || !database)
    throw new Error(
      'PANO_IT_MARIADB_PASSWORD and MARKET_E2E_DB are required for a database rewind (runtime/streams.md, section 2)',
    );
  if (!/^pano_market_e2e/.test(database))
    throw new Error(`refusing to write to database ${database}`);

  const container = process.env.MARKET_E2E_DB_CONTAINER || 'pano-web-platform-db-1';

  return execFileSync(
    'docker',
    [
      'exec',
      '-e',
      'MYSQL_PWD',
      container,
      'mariadb',
      '-uroot',
      '-N',
      '-B',
      database,
      '-e',
      statement,
    ],
    { env: { ...process.env, MYSQL_PWD: password }, encoding: 'utf8' },
  );
}

/** Moves the epoch-millisecond column `column` of the row `id` of `pano_market_<table>` back by `days`. */
export function rewind(table, id, column, days) {
  if (!/^[a-z_]+$/.test(table) || !/^[A-Za-z]+$/.test(column)) throw new Error('bad identifier');
  if (!Number.isInteger(id)) throw new Error('bad id');
  run(
    `UPDATE \`pano_${table}\` SET \`${column}\` = \`${column}\` - ${Math.round(days * DAY)} WHERE \`id\` = ${id}`,
  );
}
