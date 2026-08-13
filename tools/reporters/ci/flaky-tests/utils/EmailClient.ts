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

import nodemailer from "npm:nodemailer";
import type { SmtpConfig } from "../core/types.ts";

type Recipients = string | string[];

export class EmailClient {
  private readonly cfg: SmtpConfig;
  private readonly verbose: boolean;

  constructor(cfg: SmtpConfig, options?: { verbose?: boolean }) {
    this.cfg = cfg;
    this.verbose = !!options?.verbose;
  }

  private newTransport() {
    if (this.verbose) {
      console.debug(
        `[email] connecting smtp://${this.cfg.host}:${this.cfg.port} secure=${this.cfg.secure}`,
      );
    }
    const transport = nodemailer.createTransport({
      host: this.cfg.host,
      port: this.cfg.port,
      secure: this.cfg.secure,
      auth: {
        user: this.cfg.username!,
        pass: this.cfg.password!,
      },
    });
    if (this.verbose) {
      console.debug(
        `[email] connected smtp://${this.cfg.host}:${this.cfg.port} secure=${this.cfg.secure}`,
      );
    }

    return transport;
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
    cc?: Recipients,
  ): Promise<void> {
    const recipients = this.normalizeRecipients(to);
    if (!from) throw new Error("from is required");
    if (recipients.length === 0) throw new Error("to is required");

    const transport = this.newTransport();
    try {
      if (this.verbose) {
        console.debug("[email] sending (html)", { from, to, cc, subject });
      }

      const mail: nodemailer.SendMailOptions = {
        from,
        to: recipients,
        subject,
        html,
      };
      if (cc) {
        mail.cc = this.normalizeRecipients(cc);
      }

      await transport.sendMail(mail);
      if (this.verbose) console.debug("[email] sent");
    } finally {
      transport.close();
      if (this.verbose) console.debug("[email] closed");
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
    cc?: Recipients,
  ): Promise<void> {
    const recipients = this.normalizeRecipients(to);
    if (!from) throw new Error("from is required");
    if (recipients.length === 0) throw new Error("to is required");

    const transport = this.newTransport();
    try {
      if (this.verbose) {
        console.debug("[email] sending (text)", {
          from,
          to,
          cc,
          subject,
        });
      }

      const mail: nodemailer.SendMailOptions = {
        from,
        to: recipients,
        subject,
        text,
      };
      if (cc) {
        mail.cc = this.normalizeRecipients(cc);
      }
      await transport.sendMail(mail);
      if (this.verbose) console.debug("[email] sent");
    } finally {
      transport.close();
      if (this.verbose) console.debug("[email] closed");
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
