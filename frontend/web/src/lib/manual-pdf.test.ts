import { describe, expect, it } from "vitest";

import { manualDocument, manualPdf } from "./manual-pdf";
import { findManual, MANUALS, readManual } from "./manuals";

const CASHIER = findManual("cashier")!;

describe("a manual as a PDF", () => {
  it("builds a real PDF for every manual", async () => {
    for (const manual of MANUALS) {
      const pdf = await manualPdf(manual, (await readManual(manual))!);
      expect(pdf.subarray(0, 5).toString(), manual.slug).toBe("%PDF-");
      expect(pdf.length, manual.slug).toBeGreaterThan(10_000);
    }
  });

  it("turns a link to a heading into a jump within the PDF, and drops links to other manuals", async () => {
    const markdown = "# Cashier manual\n\nSee [Returns](#returns) and [Getting started](getting-started.md).\n\n## Returns\n";
    const doc = await manualDocument(CASHIER, markdown, new Date("2026-09-26T09:00:00Z"));
    const content = JSON.stringify(doc.content);
    expect(content).toContain('"id":"returns"');
    expect(content).toContain('"linkToDestination":"returns"');
    expect(content).toContain('"text":"Getting started"');
    expect(content).not.toContain("getting-started.md");
  });

  it("keeps a table's header row and every cell", async () => {
    const doc = await manualDocument(CASHIER, "| Key | Does |\n|---|---|\n| F2 | Pay |\n| F3 | |\n", new Date());
    const body = JSON.parse(JSON.stringify(doc.content))[0].table.body;
    expect(body).toHaveLength(3);
    expect(body[2]).toHaveLength(2);
  });
});
