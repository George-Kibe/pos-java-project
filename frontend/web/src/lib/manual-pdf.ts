import "server-only";

import { readFile } from "node:fs/promises";
import path from "node:path";

import GithubSlugger from "github-slugger";
import type { BlockContent, DefinitionContent, List, PhrasingContent, Root, RootContent, Table } from "mdast";
// A default import: pdfmake is one CommonJS instance whose methods live on its prototype, which a
// namespace import would not carry. serverExternalPackages keeps it (and its fonts) unbundled.
import pdfmake from "pdfmake";
import type { Content, TDocumentDefinitions } from "pdfmake/interfaces";
import remarkGfm from "remark-gfm";
import remarkParse from "remark-parse";
import { unified } from "unified";

import { BUSINESS_NAME } from "@/lib/brand";
import { type Manual, manualHref } from "@/lib/manuals";


/** Roboto, shipped inside pdfmake: the standard PDF fonts cannot draw an arrow or a curly quote. */
const FONT_DIR = path.join(process.cwd(), "node_modules", "pdfmake", "fonts", "Roboto");

pdfmake.setFonts({
  Roboto: {
    normal: path.join(FONT_DIR, "Roboto-Regular.ttf"),
    bold: path.join(FONT_DIR, "Roboto-Medium.ttf"),
    italics: path.join(FONT_DIR, "Roboto-Italic.ttf"),
    bolditalics: path.join(FONT_DIR, "Roboto-MediumItalic.ttf"),
  },
});
// A manual is our own text, but nothing in it may make the server fetch a URL or read a file
// other than the fonts.
pdfmake.setUrlAccessPolicy(() => false);
pdfmake.setLocalAccessPolicy((file) => path.resolve(file).startsWith(FONT_DIR));

// The brand green, from scripts/brand/generate.py.
const PRIMARY = "#0A763D";
const MUTED = "#5f6b66";
const RULE = "#d7ddd9";

/** pdfmake's Roboto has no arrows; "Menu → Account" reads as well with a chevron. */
function printable(text: string): string {
  return text.replaceAll("→", "›");
}

/** Inline Markdown as pdfmake text runs. */
type Run = Content & object;

interface Context {
  slugger: GithubSlugger;
  /** The manual's own headings, so a link to one becomes a jump within the PDF. */
  anchors: Set<string>;
}

function inline(nodes: PhrasingContent[], ctx: Context, marks: { bold?: boolean; italics?: boolean } = {}): Run[] {
  return nodes.flatMap((node): Run[] => {
    switch (node.type) {
      case "text":
        return [{ text: printable(node.value), ...marks }];
      case "strong":
        return inline(node.children, ctx, { ...marks, bold: true });
      case "emphasis":
        return inline(node.children, ctx, { ...marks, italics: true });
      case "delete":
        return inline(node.children, ctx, marks).map((run) => ({ ...run, decoration: "lineThrough" as const }));
      case "inlineCode":
        return [{ text: node.value, ...marks, background: "#eef1ef" }];
      case "break":
        return [{ text: "\n" }];
      case "link": {
        const children = inline(node.children, ctx, marks);
        const target = manualHref(node.url);
        if (target.startsWith("#") && ctx.anchors.has(target.slice(1))) {
          return children.map((run) => ({ ...run, linkToDestination: target.slice(1), color: PRIMARY }));
        }
        if (/^https?:\/\//.test(target)) {
          return children.map((run) => ({ ...run, link: target, color: PRIMARY }));
        }
        // Another manual: each downloads as its own PDF, so the words stay and the link goes.
        return children;
      }
      default:
        return "value" in node && typeof node.value === "string" ? [{ text: node.value, ...marks }] : [];
    }
  });
}

function list(node: List, ctx: Context): Content {
  const items = node.children.map((item) => ({ stack: blocks(item.children, ctx), margin: [0, 0, 0, 2] as [number, number, number, number] }));
  return node.ordered ? { ol: items, start: node.start ?? 1, margin: [0, 0, 0, 6] } : { ul: items, margin: [0, 0, 0, 6] };
}

function table(node: Table, ctx: Context): Content {
  const [header, ...rows] = node.children;
  const width = header?.children.length ?? 0;
  const cells = (row: Table["children"][number], head: boolean) =>
    Array.from({ length: width }, (_, i) => {
      const cell = row.children[i];
      return { text: cell ? inline(cell.children, ctx, head ? { bold: true } : {}) : "", fillColor: head ? "#eef1ef" : undefined };
    });
  return {
    table: { headerRows: 1, widths: Array.from({ length: width }, () => "*"), body: header ? [cells(header, true), ...rows.map((row) => cells(row, false))] : [] },
    layout: { hLineColor: () => RULE, vLineColor: () => RULE, paddingTop: () => 3, paddingBottom: () => 3 },
    fontSize: 9.5,
    margin: [0, 2, 0, 10],
  };
}

function block(node: RootContent, ctx: Context): Content[] {
  switch (node.type) {
    case "heading": {
      // An anchor's text is plain; headings carry no formatting of their own.
      const text = printable(textOf(node.children));
      const style = node.depth === 1 ? "h1" : node.depth === 2 ? "h2" : "h3";
      return [{ text, style, id: ctx.slugger.slug(textOf(node.children)), headlineLevel: node.depth }];
    }
    case "paragraph":
      return [{ text: inline(node.children, ctx), margin: [0, 0, 0, 6] }];
    case "list":
      return [list(node, ctx)];
    case "table":
      return [table(node, ctx)];
    case "blockquote":
      return [{ stack: blocks(node.children, ctx), margin: [12, 0, 0, 6], color: MUTED }];
    case "code":
      return [{ table: { widths: ["*"], body: [[{ text: node.value, fontSize: 9 }]] }, layout: "noBorders", fillColor: "#eef1ef", margin: [0, 0, 0, 8] }];
    case "thematicBreak":
      return [{ canvas: [{ type: "line", x1: 0, y1: 0, x2: 515, y2: 0, lineWidth: 0.5, lineColor: RULE }], margin: [0, 6, 0, 10] }];
    default:
      return [];
  }
}

function blocks(nodes: (BlockContent | DefinitionContent | RootContent)[], ctx: Context): Content[] {
  return nodes.flatMap((node) => block(node, ctx));
}

function textOf(nodes: PhrasingContent[]): string {
  return nodes.map((node) => ("children" in node ? textOf(node.children) : "value" in node ? node.value : "")).join("");
}

/** The ids the rendered page gives each heading, the same way `rehype-slug` does. */
function headingIds(tree: Root): Set<string> {
  const slugger = new GithubSlugger();
  return new Set(tree.children.filter((node) => node.type === "heading").map((node) => slugger.slug(textOf(node.children))));
}

async function logo(): Promise<Content[]> {
  try {
    const png = await readFile(path.join(process.cwd(), "public", "brand", "realhive-logo-192.png"));
    return [{ image: `data:image/png;base64,${png.toString("base64")}`, width: 28 }];
  } catch {
    return [];
  }
}

/** The document pdfmake draws: exported for tests, which check its shape rather than the bytes. */
export async function manualDocument(manual: Manual, markdown: string, printedOn: Date): Promise<TDocumentDefinitions> {
  const tree = unified().use(remarkParse).use(remarkGfm).parse(markdown) as Root;
  const ctx: Context = { slugger: new GithubSlugger(), anchors: headingIds(tree) };
  const title = `${manual.title} manual`;
  const mark = await logo();
  const date = printedOn.toLocaleDateString("en-KE", { day: "numeric", month: "long", year: "numeric", timeZone: "Africa/Nairobi" });
  return {
    info: { title, author: BUSINESS_NAME, subject: "User manual" },
    pageSize: "A4",
    pageMargins: [40, 60, 40, 50],
    defaultStyle: { font: "Roboto", fontSize: 10.5, lineHeight: 1.25 },
    styles: {
      h1: { fontSize: 20, bold: true, color: PRIMARY, margin: [0, 0, 0, 10] },
      h2: { fontSize: 14, bold: true, color: PRIMARY, margin: [0, 14, 0, 6] },
      h3: { fontSize: 12, bold: true, margin: [0, 10, 0, 4] },
    },
    header: () => ({
      columns: [...mark, { text: BUSINESS_NAME, color: MUTED, fontSize: 9, margin: [8, 9, 0, 0] }, { text: title, alignment: "right", color: MUTED, fontSize: 9, margin: [0, 9, 0, 0] }],
      margin: [40, 18, 40, 0],
    }),
    footer: (page, pages) => ({
      columns: [
        { text: `Printed ${date}. The latest version is always in the POS: Manual in the menu.`, color: MUTED, fontSize: 8, width: "*" },
        { text: `${page} / ${pages}`, alignment: "right", color: MUTED, fontSize: 8, width: 40 },
      ],
      margin: [40, 20, 40, 0],
    }),
    // A heading never sits alone at the foot of a page.
    pageBreakBefore: (node, following) => typeof node.headlineLevel === "number" && following.getFollowingNodesOnPage().length === 0,
    content: blocks(tree.children, ctx),
  };
}

export async function manualPdf(manual: Manual, markdown: string, printedOn = new Date()): Promise<Buffer> {
  return pdfmake.createPdf(await manualDocument(manual, markdown, printedOn)).getBuffer();
}
