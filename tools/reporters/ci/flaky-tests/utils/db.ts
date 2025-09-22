import * as mysql from "https://deno.land/x/mysql@v2.12.1/mod.ts";

function convertDsnToClientConfig(dsn: string): mysql.ClientConfig {
  try {
    const url = new URL(dsn);
    if (url.protocol !== "mysql:") {
      throw new Error("Invalid DSN protocol. Expected 'mysql:'.");
    }
    const config: mysql.ClientConfig = {
      hostname: url.hostname,
      port: Number(url.port),
      username: decodeURIComponent(url.username),
      password: decodeURIComponent(url.password),
      db: url.pathname.substring(1),
    };
    if (!config.hostname || !config.port || !config.username || !config.db) {
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
