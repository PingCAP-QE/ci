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

    // Encrypt the connection by default: the DSN carries a database password
    // and the reporter talks to MySQL across the network. `?ssl=verify` also
    // validates the server certificate; `?ssl=false` (or `ssl=disabled`) keeps
    // the plaintext behaviour for a local MySQL that has TLS turned off.
    const sslMode = (url.searchParams.get("ssl") ??
      url.searchParams.get("ssl-mode") ?? "").toLowerCase();
    if (sslMode === "false" || sslMode === "disabled") {
      // explicitly off
    } else if (
      sslMode === "verify" || sslMode === "verify-full" ||
      sslMode === "verify-ca"
    ) {
      config.ssl = { rejectUnauthorized: true };
    } else {
      // Encrypt, but tolerate the self-signed certificate an in-cluster MySQL
      // commonly serves; `ssl=verify` is the stronger option.
      config.ssl = { rejectUnauthorized: false };
    }
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
