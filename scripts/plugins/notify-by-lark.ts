import * as lark from "npm:@larksuiteoapi/node-sdk@1.19.0";
import { parseArgs } from "jsr:@std/cli@1.0.23/parse-args";

type richContentSection =
  | richContentSectionText
  | richContentSectionA
  | richContentSectionAt
  | richContentSectionImage;
interface richContentSectionText {
  tag: "text";
  text: string;
  un_escape?: boolean;
}

interface richContentSectionA {
  tag: "a";
  text: string;
  href: string;
}

interface richContentSectionAt {
  tag: "at";
  user_id: string;
  user_name?: string;
}

interface richContentSectionImage {
  tag: "img";
  image_key: string;
}

interface richContentSectionMedia {
  tag: "media";
  file_key: string;
  image_key?: string;
}

interface richContentSectionMediaEmotion {
  tag: "emotion";
  emoji_type: string;
}

interface cliArgs {
  title: string;
  message: string;
  links: string[];
  to_emails: string[];
}

async function main({
  title,
  message,
  links,
  to_emails,
}: cliArgs) {
  const appID = Deno.env.get("LARK_APP_ID") || "";
  const appSecret = Deno.env.get("LARK_APP_SECRET") || "";

  const client = new lark.Client({
    appId: appID,
    appSecret: appSecret,
    appType: lark.AppType.SelfBuild,
    domain: lark.Domain.Feishu,
  });

  const content: richContentSection[][] = [[
    { tag: "text", text: message },
  ]];

  if (links?.length > 0) {
    content.push([{ tag: "text", text: "\nLinks:" }]);
  }
  links?.forEach((href) => {
    content.push([{ tag: "text", text: "- " }, { tag: "a", text: href, href }]);
  });

  const responses = await Promise.all(
    to_emails.map(async (email) =>
      await client.im.message.create({
        params: {
          receive_id_type: "email",
        },
        data: {
          receive_id: email,
          msg_type: "post",
          content: JSON.stringify({
            "zh_cn": { title, content },
            "en_us": { title, content },
          }),
        },
      })
    ),
  );

  const failedResponses = responses.filter((r) => r.code);
  if (failedResponses.length > 0) {
    console.error(failedResponses);
    Deno.exit(1);
  }
}

/**
 * ---------entry----------------
 * ****** CLI args **************
 * --title notify title
 * --message detail message.
 * --links.text link display text
 * --links.href link url
 * --to_emails receive email
 */
const args = parseArgs<cliArgs>(Deno.args, {
  collect: ["links", "to_emails"] as never[],
});

await main(args);
