import * as mysql from "https://deno.land/x/mysql@v2.12.1/mod.ts";

function convertDsnToClientConfig(dsn: string) {
  // mysql://user:password@host:port/database
  const [_, config] = dsn.split("://");
  const [credentials, hostAndPort] = config.split("@");
  if (!credentials || !hostAndPort) {
    throw new Error("Invalid DSN: missing user/password or host/port");
  }

  const [username, password] = credentials.split(":");
  if (!username || !password) {
    throw new Error("Invalid DSN: missing user or password");
  }

  const [host, portAndDb] = hostAndPort.split(":");
  if (!host || !portAndDb) {
    throw new Error("Invalid DSN: missing host or port/database");
  }

  const [port, db] = portAndDb.split("/");
  if (!port || !db) {
    throw new Error("Invalid DSN: missing port or database");
  }

  return {
    hostname: host,
    port: parseInt(port),
    username: decodeURIComponent(username),
    password: decodeURIComponent(password),
    db,
  } as mysql.ClientConfig;
}

export { convertDsnToClientConfig };
