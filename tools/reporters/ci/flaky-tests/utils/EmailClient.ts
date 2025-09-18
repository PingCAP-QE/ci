/**
 * EmailClient - Simple SMTP client wrapper for sending HTML or text emails.
 *
 * This implementation uses the Deno SMTP library to send emails.
 *
 * Usage:
 *   import { EmailClient } from "./utils/EmailClient.ts";
 *   import type { SmtpConfig } from "../core/types.ts";
 *
 *   const smtp: SmtpConfig = {
 *     host: Deno.env.get("SMTP_HOST")!,
 *     port: Number(Deno.env.get("SMTP_PORT")!),
 *     username: Deno.env.get("SMTP_USER") ?? undefined,
 *     password: Deno.env.get("SMTP_PASS") ?? undefined,
 *     secure: (Deno.env.get("SMTP_SECURE") ?? "true").toLowerCase() === "true",
 *   };
 *
 *   const mailer = new EmailClient(smtp, { verbose: true });
 *   await mailer.sendHtml("from@example.com", ["to@example.com"], "Subject", "<b>Hello</b>");
 *   await mailer.sendText("from@example.com", "ops@example.com", "Ping", "Plain text body");
 */

import { SmtpClient } from "https://deno.land/x/smtp@v0.7.0/mod.ts";
import type { SmtpConfig } from "../core/types.ts";

type Recipients = string | string[];

export class EmailClient {
  private readonly cfg: SmtpConfig;
  private readonly verbose: boolean;

  constructor(cfg: SmtpConfig, options?: { verbose?: boolean }) {
    this.cfg = cfg;
    this.verbose = !!options?.verbose;
  }

  /**
   * Send an HTML email.
   *
   * @param from Sender email address
   * @param to Recipient(s) as string or string[]
   * @param subject Email subject
   * @param html HTML body content
   */
  async sendHtml(
    from: string,
    to: Recipients,
    subject: string,
    html: string,
  ): Promise<void> {
    const recipients = this.normalizeRecipients(to);
    if (!from) throw new Error("from is required");
    if (recipients.length === 0) throw new Error("to is required");

    const client = new SmtpClient();

    if (this.verbose) {
      console.debug(
        `[email] connecting smtp://${this.cfg.host}:${this.cfg.port} secure=${this.cfg.secure}`,
      );
    }

    if (this.cfg.secure) {
      await client.connectTLS({
        hostname: this.cfg.host,
        port: this.cfg.port,
        username: this.cfg.username,
        password: this.cfg.password,
      });
    } else {
      await client.connect({
        hostname: this.cfg.host,
        port: this.cfg.port,
        username: this.cfg.username,
        password: this.cfg.password,
      });
    }

    try {
      if (this.verbose) {
        console.debug(
          `[email] sending: from=${from} to=${
            recipients.join(",")
          } subject="${subject}" (html)`,
        );
      }
      await client.send({
        from,
        to: recipients.join(","),
        subject,
        content: "", // plain text part (empty since we send HTML)
        html,
      });
      if (this.verbose) console.debug("[email] sent");
    } finally {
      try {
        await client.close();
        if (this.verbose) console.debug("[email] closed");
      } catch {
        // ignore shutdown errors
      }
    }
  }

  /**
   * Send a plain text email.
   *
   * @param from Sender email address
   * @param to Recipient(s) as string or string[]
   * @param subject Email subject
   * @param text Plain text content
   */
  async sendText(
    from: string,
    to: Recipients,
    subject: string,
    text: string,
  ): Promise<void> {
    const recipients = this.normalizeRecipients(to);
    if (!from) throw new Error("from is required");
    if (recipients.length === 0) throw new Error("to is required");

    const client = new SmtpClient();

    if (this.verbose) {
      console.debug(
        `[email] connecting smtp://${this.cfg.host}:${this.cfg.port} secure=${this.cfg.secure}`,
      );
    }

    if (this.cfg.secure) {
      await client.connectTLS({
        hostname: this.cfg.host,
        port: this.cfg.port,
        username: this.cfg.username,
        password: this.cfg.password,
      });
    } else {
      await client.connect({
        hostname: this.cfg.host,
        port: this.cfg.port,
        username: this.cfg.username,
        password: this.cfg.password,
      });
    }

    try {
      if (this.verbose) {
        console.debug(
          `[email] sending: from=${from} to=${
            recipients.join(",")
          } subject="${subject}" (text)`,
        );
      }
      await client.send({
        from,
        to: recipients.join(","),
        subject,
        content: text,
      });
      if (this.verbose) console.debug("[email] sent");
    } finally {
      try {
        await client.close();
        if (this.verbose) console.debug("[email] closed");
      } catch {
        // ignore shutdown errors
      }
    }
  }

  private normalizeRecipients(to: Recipients): string[] {
    if (Array.isArray(to)) {
      return to.map((s) => s.trim()).filter(Boolean);
    }
    return String(to)
      .split(",")
      .map((s) => s.trim())
      .filter(Boolean);
  }
}

export default EmailClient;
