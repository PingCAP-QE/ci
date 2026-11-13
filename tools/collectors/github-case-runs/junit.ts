import { Unzip, UnzipInflate, unzipSync } from "npm:fflate@0.8.3";
import { XMLParser, XMLValidator } from "npm:fast-xml-parser@5.12.0";

export type ProblemCases = Record<
  string,
  { new_flaky: Array<{ name: string; reason: string }> }
>;

const MAX_ARCHIVE_BYTES = 16 * 1024 * 1024;
const MAX_XML_BYTES = 10 * 1024 * 1024;

type Element = {
  [tag: string]: Element[] | Record<string, string> | undefined;
  ":@"?: Record<string, string>;
};

function validCodePoint(value: number): boolean {
  return value === 9 || value === 10 || value === 13 ||
    (value >= 0x20 && value <= 0xD7FF) ||
    (value >= 0xE000 && value <= 0xFFFD) ||
    (value >= 0x10000 && value <= 0x10FFFF);
}

// XMLValidator does not reject all text outside a self-closing root element.
function checkDocumentBoundary(xml: string): void {
  let depth = 0;
  for (let index = 0; index < xml.length; index++) {
    if (xml.startsWith("<!--", index) || xml.startsWith("<?", index)) {
      const end = xml.startsWith("<!--", index) ? "-->" : "?>";
      const position = xml.indexOf(end, index + 2);
      if (position < 0) throw new Error("Invalid JUnit document boundary");
      index = position + end.length - 1;
    } else if (xml.startsWith("<![CDATA[", index)) {
      if (!depth) throw new Error("Invalid JUnit document boundary");
      const position = xml.indexOf("]]>", index + 9);
      if (position < 0) throw new Error("Invalid JUnit document boundary");
      index = position + 2;
    } else if (xml[index] === "<") {
      const closing = xml[index + 1] === "/";
      let quote = "";
      let end = index + 1;
      for (; end < xml.length; end++) {
        const char = xml[end];
        if (quote) {
          if (char === "<") throw new Error("Invalid JUnit attribute");
          if (char === quote) quote = "";
        } else if (char === '"' || char === "'") quote = char;
        else if (char === ">") break;
      }
      if (end === xml.length) {
        throw new Error("Invalid JUnit document boundary");
      }
      depth += closing ? -1 : xml[end - 1] === "/" ? 0 : 1;
      index = end;
    } else if (
      !depth && !/[ \t\r\n]/.test(xml[index]) &&
      !(index === 0 && xml[index] === "\uFEFF")
    ) {
      throw new Error("Invalid JUnit document boundary");
    }
  }
}

/** Parse nextest JUnit; only explicit within-nextest recovery counts as flaky. */
export function parseJUnit(xml: string): ProblemCases {
  const invalid = () => {
    throw new Error("Invalid or unsafe JUnit XML");
  };
  if (new TextEncoder().encode(xml).length > MAX_XML_BYTES) {
    throw new Error("JUnit XML is too large");
  }
  for (const char of xml) {
    if (!validCodePoint(char.codePointAt(0)!)) invalid();
  }
  // XMLValidator permits unknown entities and some non-XML characters. Check
  // those separately; comments and CDATA do not expand entity references.
  const markup = xml.replace(
    /<!--[\s\S]*?-->|<!\[CDATA\[[\s\S]*?\]\]>/g,
    (literal) => {
      if (
        literal.startsWith("<!--") &&
        (literal.slice(4, -3).includes("--") ||
          literal.slice(4, -3).endsWith("-"))
      ) invalid();
      return "";
    },
  );
  if (/<!DOCTYPE|<!ENTITY/i.test(markup)) invalid();
  const encoding = markup.match(/<\?xml\s[^?]*encoding\s*=\s*(["'])(.*?)\1/i)
    ?.[2];
  if (encoding && !/^utf-?8$/i.test(encoding)) invalid();
  const withoutEntities = markup.replace(
    /&(?:amp|lt|gt|apos|quot|#\d+|#x[\da-fA-F]+);/g,
    (entity) => {
      if (entity.startsWith("&#")) {
        const value = entity.startsWith("&#x")
          ? Number.parseInt(entity.slice(3, -1), 16)
          : Number(entity.slice(2, -1));
        if (!validCodePoint(value)) invalid();
      }
      return "";
    },
  );
  if (withoutEntities.includes("&") || markup.includes("]]>")) invalid();
  if (!markup.trim().endsWith(">") || XMLValidator.validate(xml) !== true) {
    invalid();
  }
  checkDocumentBoundary(xml);

  const elements: Element[] = new XMLParser({
    ignoreAttributes: false,
    preserveOrder: true,
    removeNSPrefix: true,
    trimValues: false,
    parseTagValue: false,
    parseAttributeValue: false,
    // Enables numeric references; non-XML named entities are rejected above.
    htmlEntities: true,
  }).parse(xml);
  const roots = elements.filter((item) =>
    !Object.keys(item)[0].startsWith("?")
  );
  if (
    roots.length !== 1 ||
    !["testsuite", "testsuites"].includes(Object.keys(roots[0])[0])
  ) invalid();

  const cases: ProblemCases = {};
  const seen = new Set<string>();
  const pending = [...roots];
  while (pending.length) {
    const item = pending.pop()!;
    const tag = Object.keys(item)[0];
    const children = item[tag] as Element[];
    if (tag === "testsuite" || tag === "testsuites") {
      for (let index = children.length - 1; index >= 0; index--) {
        if (
          ["testsuite", "testsuites", "testcase"].includes(
            Object.keys(children[index])[0],
          )
        ) pending.push(children[index]);
      }
      continue;
    }
    if (tag !== "testcase") continue;
    const suite = item[":@"]?.["@_classname"];
    const name = item[":@"]?.["@_name"];
    if (typeof suite !== "string" || typeof name !== "string") invalid();
    const key = JSON.stringify([suite, name]);
    if (seen.has(key)) throw new Error("Duplicate JUnit case identity");
    seen.add(key);
    if (
      !children.some((child) =>
        Object.hasOwn(child, "flakyFailure") ||
        Object.hasOwn(child, "flakyError")
      )
    ) continue;
    if (children.some((child) => Object.hasOwn(child, "skipped"))) continue;
    // Own properties preserve arbitrary classnames, including __proto__.
    if (!Object.hasOwn(cases, suite!)) {
      Object.defineProperty(cases, suite!, {
        value: { new_flaky: [] },
        enumerable: true,
        writable: true,
        configurable: true,
      });
    }
    cases[suite!].new_flaky.push({ name: name!, reason: "flaky" });
  }
  return cases;
}

/** Read exactly one safe junit.xml entry in memory, never extracting to disk. */
export function parseArtifact(archive: Uint8Array): ProblemCases {
  if (archive.length > MAX_ARCHIVE_BYTES) {
    throw new Error("JUnit archive is too large");
  }
  let count = 0;
  let name = "";
  // Inspect the central directory without allocating/decompressing any entries.
  unzipSync(archive, {
    filter: (entry) => {
      count++;
      if (
        count !== 1 || !/^(?:[^/]+\/)*junit\.xml$/.test(entry.name) ||
        entry.name.includes("\\") || entry.name.includes("\0") ||
        /^[a-z]:/i.test(entry.name) ||
        entry.name.split("/").some((part) => part === "." || part === "..")
      ) throw new Error("Unsafe or ambiguous JUnit archive entry");
      if (entry.originalSize > MAX_XML_BYTES) {
        throw new Error("JUnit XML is too large");
      }
      name = entry.name;
      return false;
    },
  });
  if (count !== 1) throw new Error("Expected exactly one junit.xml");
  const bytes = new Uint8Array(MAX_XML_BYTES);
  let length = 0;
  let entries = 0;
  let complete = false;
  const unzip = new Unzip((file) => {
    if (++entries !== 1 || file.name !== name) {
      throw new Error("Unsafe or ambiguous JUnit archive entry");
    }
    if ((file.originalSize ?? 0) > MAX_XML_BYTES) {
      throw new Error("JUnit XML is too large");
    }
    file.ondata = (error, data, final) => {
      if (error) throw error;
      if (length + data.length > MAX_XML_BYTES) {
        throw new Error("JUnit XML is too large");
      }
      bytes.set(data, length);
      length += data.length;
      complete = final;
    };
    file.start();
  });
  unzip.register(UnzipInflate);
  // Limit each inflate call too: checking ondata after feeding the whole archive
  // would still allocate its full expanded output before the size check ran.
  for (let offset = 0; offset < archive.length; offset += 1024) {
    unzip.push(
      archive.subarray(offset, offset + 1024),
      offset + 1024 >= archive.length,
    );
  }
  if (entries !== 1 || !complete) throw new Error("Invalid JUnit ZIP archive");
  return parseJUnit(
    new TextDecoder("utf-8", { fatal: true }).decode(bytes.subarray(0, length)),
  );
}
