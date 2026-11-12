import assert from "node:assert/strict";
import { strToU8, UnzipInflate, zipSync } from "npm:fflate@0.8.3";
import { parseArtifact, parseJUnit } from "./junit.ts";

const wrap = (body: string) =>
  `<testsuites><testsuite>${body}</testsuite></testsuites>`;
const testcase = (name: string, body = "", suite = "suite") =>
  `<testcase classname="${suite}" name="${name}">${body}</testcase>`;
const artifact = (xml: string, path = "junit.xml") =>
  zipSync({ [path]: strToU8(xml) });

Deno.test("nextest recovery markers, not workflow or persistent failures, identify flakiness", () => {
  const rows = [
    ["passes", "", false],
    ["skipped", "<skipped/>", false],
    ["persistent", "<failure/><rerunFailure/>", false],
    ["persistent-error", "<error/><rerunError/>", false],
    ["rerun-only", "<rerunFailure/>", false],
    ["flaky", "<flakyFailure/>", true],
    ["flaky-error", "<flakyError/>", true],
    ["flaky-fail-status", "<failure/><flakyFailure/>", true],
    ["two-retries", "<flakyFailure/><flakyFailure/>", true],
    ["skipped-with-retry", "<skipped/><flakyFailure/>", false],
  ] as const;
  const result = parseArtifact(
    artifact(wrap(rows.map(([name, body]) => testcase(name, body)).join(""))),
  );
  assert.deepEqual(result, {
    suite: {
      new_flaky: rows.filter(([, , flaky]) => flaky).map(([name]) => ({
        name,
        reason: "flaky",
      })),
    },
  });
  assert.deepEqual(parseJUnit("<testsuite/>"), {});
  assert.deepEqual(parseJUnit(wrap(testcase("clean"))), {});
});

Deno.test("UTF-8, standard entities, exact identity and namespace prefixes are preserved", () => {
  const xml = `<?xml version="1.0" encoding="UTF-8"?>
    <j:testsuites xmlns:j="urn:junit"><j:testsuite name="not-classname">
      <j:testcase classname=" 模块::&lt;T&gt; &amp; &quot;quoted&quot; " name="&#x1F680;::测试::&#65;::&apos;::&amp;copy;">
        <j:flakyError><![CDATA[arbitrary &unknown; <failure/>]]></j:flakyError>
      </j:testcase>
    </j:testsuite></j:testsuites>`;
  assert.deepEqual(
    parseArtifact(artifact(xml, "target/nextest/ci/junit.xml")),
    {
      ' 模块::<T> & "quoted" ': {
        new_flaky: [{ name: "🚀::测试::A::'::&copy;", reason: "flaky" }],
      },
    },
  );
  assert.deepEqual(
    parseJUnit(
      '<testsuite><testcase classname="" name=""><flakyFailure/></testcase></testsuite>',
    ),
    {
      "": { new_flaky: [{ name: "", reason: "flaky" }] },
    },
  );
  assert.deepEqual(parseArtifact(artifact("\uFEFF<testsuite/>")), {});
});

Deno.test("untrusted classname and name strings cannot mutate prototypes or lose cases", () => {
  const reserved = ["__proto__", "constructor", "toString"];
  const before = Object.getOwnPropertyDescriptors(Object.prototype);
  const result = parseJUnit(
    wrap(
      reserved.flatMap((suite) =>
        reserved.map((name) => testcase(name, "<flakyFailure/>", suite))
      ).join(""),
    ),
  );
  assert.equal(Object.getPrototypeOf(result), Object.prototype);
  assert.deepEqual(Object.keys(result), reserved);
  for (const suite of reserved) {
    assert.ok(Object.hasOwn(result, suite));
    assert.deepEqual(
      result[suite].new_flaky,
      reserved.map((name) => ({ name, reason: "flaky" })),
    );
  }
  assert.deepEqual(Object.getOwnPropertyDescriptors(Object.prototype), before);
  assert.deepEqual(Object.keys(JSON.parse(JSON.stringify(result))), reserved);
});

Deno.test("duplicate identities reject the whole document, including clean and entity-equivalent cases", () => {
  const rows = [
    wrap(
      testcase("same", "<flakyFailure/>") + testcase("same", "<flakyError/>"),
    ),
    wrap(testcase("same") + testcase("same", "<flakyFailure/>")),
    wrap(testcase("A") + testcase("&#65;")),
    `<testsuites><testsuite>${testcase("same")}</testsuite><testsuite>${
      testcase("same")
    }</testsuite></testsuites>`,
    `<testsuites><testsuites><testsuite>${
      testcase("same")
    }</testsuite></testsuites><testsuite>${
      testcase("same")
    }</testsuite></testsuites>`,
  ];
  for (const xml of rows) {
    assert.throws(() => parseJUnit(xml), /Duplicate JUnit case identity/, xml);
  }
  assert.deepEqual(
    parseJUnit(
      wrap(
        testcase("same", "<flakyFailure/>", "first") +
          testcase("same", "<flakyFailure/>", "second"),
      ),
    ),
    {
      first: { new_flaky: [{ name: "same", reason: "flaky" }] },
      second: { new_flaky: [{ name: "same", reason: "flaky" }] },
    },
  );
});

Deno.test("DTD, entity expansion, unknown entities and invalid XML codepoints fail closed", () => {
  const invalid = [
    '<!DOCTYPE testsuite SYSTEM "https://example.invalid/secret"><testsuite/>',
    '<!DOCTYPE testsuite [<!ENTITY evil "expanded">]><testsuite>&evil;</testsuite>',
    '<!ENTITY evil SYSTEM "file:///etc/passwd"><testsuite/>',
    wrap(testcase("&unknown;", "<flakyFailure/>")),
    wrap(testcase("&nbsp;")),
    "<testsuite>&unknown;</testsuite>",
    "<testsuite>&</testsuite>",
    "<testsuite>&#0;</testsuite>",
    "<testsuite>&#xD800;</testsuite>",
    "<testsuite>&#x110000;</testsuite>",
    "<testsuite>&#xFFFF;</testsuite>",
    "<testsuite>\0</testsuite>",
    "<testsuite>\u0001</testsuite>",
    "<testsuite>\uD800</testsuite>",
    '<?xml version="1.0" encoding="UTF-16"?><testsuite/>',
    '<?xml version="1.0" encoding="ISO-8859-1"?><testsuite/>',
  ];
  for (const xml of invalid) {
    assert.throws(
      () => parseJUnit(xml),
      /Invalid or unsafe/,
      JSON.stringify(xml),
    );
  }
});

Deno.test("malformed XML, extra roots and missing identities fail closed", () => {
  const invalid = [
    "",
    "not XML",
    "<testsuite>",
    "<testsuite></different>",
    "<testsuite/><testsuite/>",
    "text<testsuite/>",
    "<testsuite/>text",
    "<testsuite/>text<?instruction?>",
    "<![CDATA[outside]]><testsuite/>",
    "<testsuite><!-- unfinished",
    "<testsuite><![CDATA[unfinished",
    "<testsuite><!-- a -- b --></testsuite>",
    "<testsuite><!-- a---></testsuite>",
    '<testsuite a="1" a="2"/>',
    '<testsuite a="raw < bracket"/>',
    '<testsuite a="<!-- still an invalid attribute -->"/>',
    "<testsuite>]]></testsuite>",
    "<testsuite a=unquoted/>",
    "<testsuite><bad / ></testsuite>",
    "<not-junit/>",
    '<testsuite><testcase name="missing-classname"/></testsuite>',
    '<testsuite><testcase classname="missing-name"/></testsuite>',
  ];
  for (const xml of invalid) {
    assert.throws(() => parseJUnit(xml), /Invalid/, JSON.stringify(xml));
  }
  assert.deepEqual(
    parseJUnit(
      '<?xml version="1.0"?><!-- before --><testsuite/><!-- after --><?instruction?>',
    ),
    {},
  );
});

Deno.test("ZIP accepts exactly one safe junit.xml path and rejects extra or traversing entries", () => {
  const xml = "<testsuite/>";
  for (
    const path of [
      "junit.xml",
      "reports/junit.xml",
      "target/nextest/ci/junit.xml",
    ]
  ) {
    assert.deepEqual(parseArtifact(artifact(xml, path)), {}, path);
  }
  for (
    const path of [
      "other.xml",
      "JUNIT.XML",
      "../junit.xml",
      "/junit.xml",
      "./junit.xml",
      "a/../junit.xml",
      "a//junit.xml",
      "a\\junit.xml",
      "C:/junit.xml",
      "a\0/junit.xml",
    ]
  ) {
    assert.throws(
      () => parseArtifact(artifact(xml, path)),
      /Unsafe/,
      JSON.stringify(path),
    );
  }
  assert.throws(() => parseArtifact(zipSync({})), /exactly one/);
  assert.throws(
    () =>
      parseArtifact(
        zipSync({
          "junit.xml": strToU8(xml),
          "another/junit.xml": strToU8(xml),
        }),
      ),
    /ambiguous/,
  );
  assert.throws(
    () =>
      parseArtifact(
        zipSync({ "junit.xml": strToU8(xml), "extra.txt": strToU8("extra") }),
      ),
    /ambiguous/,
  );
  assert.throws(() => parseArtifact(new Uint8Array([1, 2, 3])), /zip/i);
});

Deno.test("ZIP duplicate filenames are rejected even when a ZIP reader would overwrite them", () => {
  const bytes = zipSync({
    "first/junit.xml": strToU8("<testsuite/>"),
    "other/junit.xml": strToU8("<testsuite/>"),
  });
  // Both the local and central records receive the same length-preserving name.
  const oldName = strToU8("other/junit.xml");
  const newName = strToU8("first/junit.xml");
  for (let index = 0; index <= bytes.length - oldName.length; index++) {
    if (oldName.every((value, offset) => bytes[index + offset] === value)) {
      bytes.set(newName, index);
    }
  }
  assert.throws(() => parseArtifact(bytes), /ambiguous/);
});

Deno.test("UTF-16 bytes and malformed UTF-8 are not decoded as JUnit", () => {
  const utf16 = Uint8Array.from([
    0xFF,
    0xFE,
    ...Array.from("<testsuite/>").flatMap((char) => [char.charCodeAt(0), 0]),
  ]);
  for (
    const bytes of [
      utf16,
      Uint8Array.of(0xFF),
      Uint8Array.of(0xC0, 0xAF),
      Uint8Array.of(60, 0, 116, 0),
    ]
  ) {
    assert.throws(() => parseArtifact(zipSync({ "junit.xml": bytes })));
  }
});

Deno.test("compressed and expanded XML budgets are checked before parsing", () => {
  assert.throws(
    () => parseArtifact(new Uint8Array(16 * 1024 * 1024 + 1)),
    /archive is too large/,
  );
  const large = " ".repeat(10 * 1024 * 1024 + 1);
  assert.throws(() => parseJUnit(large), /XML is too large/);
  assert.throws(() => parseArtifact(artifact(large)), /XML is too large/);
  // Neither the local nor central uncompressed-size field is trustworthy.
  const forged = zipSync({ "junit.xml": strToU8(large) }, { level: 0 });
  const view = new DataView(
    forged.buffer,
    forged.byteOffset,
    forged.byteLength,
  );
  for (let index = 0; index + 46 < forged.length; index++) {
    const signature = view.getUint32(index, true);
    if (signature === 0x04034B50) view.setUint32(index + 22, 1, true);
    if (signature === 0x02014B50) view.setUint32(index + 24, 1, true);
  }
  assert.throws(() => parseArtifact(forged), /XML is too large/);
});

Deno.test("forged DEFLATE sizes cannot inflate the whole oversized entry before rejection", () => {
  const forged = zipSync({
    "junit.xml": strToU8(" ".repeat(32 * 1024 * 1024)),
  }, { level: 9 });
  const view = new DataView(
    forged.buffer,
    forged.byteOffset,
    forged.byteLength,
  );
  assert.equal(
    view.getUint16(8, true),
    8,
    "must exercise DEFLATE, not storage",
  );
  for (let index = 0; index + 46 < forged.length; index++) {
    const signature = view.getUint32(index, true);
    if (signature === 0x04034B50) view.setUint32(index + 22, 1, true);
    if (signature === 0x02014B50) view.setUint32(index + 24, 1, true);
  }
  // Count compressed bytes fed to the real inflater: it must stop early and
  // never receive a whole bomb in a single push (before ondata can enforce caps).
  const original = UnzipInflate.prototype.push;
  let consumed = 0;
  let largestChunk = 0;
  UnzipInflate.prototype.push = function (chunk, final) {
    consumed += chunk.length;
    largestChunk = Math.max(largestChunk, chunk.length);
    return original.call(this, chunk, final);
  };
  try {
    assert.throws(() => parseArtifact(forged), /XML is too large/);
    assert.ok(consumed > 0 && consumed < forged.length / 2);
    assert.ok(largestChunk <= 1024);
  } finally {
    UnzipInflate.prototype.push = original;
  }
});
