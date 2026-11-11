import { assertEquals, assertThrows } from "jsr:@std/assert@^1.0.19";
import { convertDsnToClientConfig } from "./db.ts";

Deno.test("convertDsnToClientConfig parses the DSN fields", () => {
  const cfg = convertDsnToClientConfig(
    "mysql://user:p%40ss@db.example:3306/flaky",
  );
  assertEquals(cfg.host, "db.example");
  assertEquals(cfg.port, 3306);
  assertEquals(cfg.user, "user");
  assertEquals(cfg.password, "p@ss");
  assertEquals(cfg.database, "flaky");
});

Deno.test("convertDsnToClientConfig encrypts by default", () => {
  const cfg = convertDsnToClientConfig(
    "mysql://user:pass@db.example:3306/flaky",
  );
  // A password crosses the network, so the default must not be plaintext. An
  // in-cluster MySQL usually serves a self-signed certificate, hence
  // rejectUnauthorized: false; `?ssl=verify` is the stronger option.
  assertEquals(cfg.ssl, { rejectUnauthorized: false });
});

Deno.test("convertDsnToClientConfig honours ssl=verify", () => {
  const cfg = convertDsnToClientConfig(
    "mysql://user:pass@db.example:3306/flaky?ssl=verify",
  );
  assertEquals(cfg.ssl, { rejectUnauthorized: true });
});

Deno.test("convertDsnToClientConfig allows opting out with ssl=false", () => {
  const cfg = convertDsnToClientConfig(
    "mysql://user:pass@db.example:3306/flaky?ssl=false",
  );
  assertEquals(cfg.ssl, undefined);
});

Deno.test("convertDsnToClientConfig rejects a non-mysql DSN", () => {
  assertThrows(
    () => convertDsnToClientConfig("postgres://user:pass@db.example:5432/x"),
    Error,
    "Invalid DSN protocol",
  );
});

Deno.test("convertDsnToClientConfig rejects an incomplete DSN", () => {
  assertThrows(
    () => convertDsnToClientConfig("mysql://db.example/flaky"),
    Error,
    "Incomplete DSN",
  );
});
