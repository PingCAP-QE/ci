import type { ConnectionOptions } from "npm:mysql2/promise";

function convertDsnToClientConfig(dsn: string): ConnectionOptions {
  try {
    const url = new URL(dsn);
    if (url.protocol !== "mysql:") {
      throw new Error("Invalid DSN protocol. Expected 'mysql:'.");
    }
    const config: ConnectionOptions = {
      host: url.hostname,
      port: Number(url.port),
      user: decodeURIComponent(url.username),
      password: decodeURIComponent(url.password),
      database: url.pathname.substring(1),
    };
    if (!config.host || !config.port || !config.user || !config.database) {
      throw new Error(
        "Incomplete DSN. Must include user, host, port, and database.",
      );
    }
    return config;
  } catch (e) {
    throw new Error(
      `Invalid DSN format: ${e instanceof Error ? e.message : String(e)}`,
    );
  }
}

export { convertDsnToClientConfig };
