import { assertEquals } from "jsr:@std/assert@1.0.11";
import { filter, load, type Trigger } from "./trigger-test-utils.ts";

// The issue-comment notification routes are triggered by a comment on
// `tidbcloud/docker-image-controller`. GitHub HMAC (`el-github`) proves the
// event came from GitHub, but any account able to comment can write a META
// block, so the filter also requires the comment to come from a bot (the image
// controller) or from a repository owner/member (the manual fallback used when
// the bot is down). See the security model in tekton/v1/triggers/README.md.

type NamedTrigger = Trigger & {
  metadata: { name: string; labels?: Record<string, string> };
};

const notifyDirectory = "tekton/v1/triggers/triggers/env-gcp/_/notify/";
const deliveredImage =
  "us-docker.pkg.dev/pingcap-testing-account/hub/pingcap/tidbx:v7.5.0-nextgen.202611.1";

function commentPayload(userType: string, association: string) {
  const meta = JSON.stringify({
    status: "success",
    images: [deliveredImage],
    target_images: [],
    stage: "prod",
  });
  return {
    action: "created",
    repository: { full_name: "tidbcloud/docker-image-controller" },
    comment: {
      body: `<!-- META=${meta} -->`,
      user: { type: userType, login: "image-controller" },
      author_association: association,
    },
  };
}

Deno.test("issue-comment notifications require a bot or a repository member", () => {
  const accepted = [
    ["Bot", "NONE"],
    ["User", "OWNER"],
    ["User", "MEMBER"],
  ];
  const rejected = [
    ["User", "COLLABORATOR"],
    ["User", "CONTRIBUTOR"],
    ["User", "NONE"],
  ];
  for (
    const name of [
      "update-ops-when-tidbx-image-distributed-to-clouds.yaml",
      "update-tcms-when-tidbx-image-distributed-to-clouds.yaml",
    ]
  ) {
    const trigger = load<NamedTrigger>(`${notifyDirectory}${name}`);

    for (const [type, association] of accepted) {
      assertEquals(
        filter(trigger, commentPayload(type, association)),
        true,
        `${name} must accept a ${type}/${association} comment`,
      );
    }
    for (const [type, association] of rejected) {
      assertEquals(
        filter(trigger, commentPayload(type, association)),
        false,
        `${name} must reject a ${type}/${association} comment`,
      );
    }
  }
});
