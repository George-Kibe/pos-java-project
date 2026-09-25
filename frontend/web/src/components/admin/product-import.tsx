"use client";

import { useRouter } from "next/navigation";
import { useRef, useState } from "react";
import { toast } from "sonner";
import { z } from "zod";

import { failureMessage, FormError } from "@/components/admin/form-parts";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { api } from "@/lib/api/client";

const ImportReportSchema = z.object({
  created: z.number(),
  updated: z.number(),
  failed: z.number(),
  errors: z.array(z.object({ line: z.number(), sku: z.string().nullable(), message: z.string() })),
});
type ImportReport = z.infer<typeof ImportReportSchema>;

/** The catalogue service's limit on an upload. */
const MAX_BYTES = 5 * 1024 * 1024;

/**
 * Products loaded from a CSV file - the same columns the export writes. Good rows land even when
 * others fail, and each failure comes back with its line so the file can be corrected and re-run.
 */
export function ProductImport() {
  const router = useRouter();
  const input = useRef<HTMLInputElement>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | undefined>();
  const [report, setReport] = useState<ImportReport | null>(null);

  async function upload(file: File) {
    if (file.size > MAX_BYTES) {
      setError("That file is over 5 MB. Split it and load the parts one after another.");
      return;
    }
    setBusy(true);
    setError(undefined);
    setReport(null);
    try {
      const form = new FormData();
      form.set("file", file);
      const result = await api("products/import", ImportReportSchema, { method: "POST", form });
      setReport(result);
      if (result.created + result.updated > 0) {
        toast.success(`${result.created} added and ${result.updated} updated.`);
        router.refresh();
      }
    } catch (failure) {
      setError(failureMessage(failure, "The file was not loaded."));
    } finally {
      setBusy(false);
      if (input.current) input.current.value = "";
    }
  }

  return (
    <>
      <Button variant="outline" onClick={() => setOpen(true)}>
        Import CSV
      </Button>
      <Dialog
        open={open}
        onOpenChange={(next) => {
          setOpen(next);
          if (!next) {
            setReport(null);
            setError(undefined);
          }
        }}
      >
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-3xl">
          <DialogHeader>
            <DialogTitle>Import products</DialogTitle>
            <DialogDescription>
              A CSV with the columns sku, name, categoryCode, uomCode, taxClassCode and basePrice, and optionally brandCode, sellByWeight, priceIncludesTax and barcodes (separated by |). A SKU already in the catalogue is updated. The export writes exactly these columns.
            </DialogDescription>
          </DialogHeader>
          <label htmlFor="product-import-file" className="text-base font-medium">
            CSV file
          </label>
          <input
            ref={input}
            id="product-import-file"
            type="file"
            accept=".csv,text/csv"
            disabled={busy}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void upload(file);
            }}
            className="text-base file:mr-3 file:h-11 file:rounded-lg file:border file:bg-background file:px-4"
          />
          {busy ? <p className="text-sm text-muted-foreground">Loading…</p> : null}
          <FormError message={error} />
          {report ? (
            <div className="grid gap-3" data-testid="import-report">
              <p className="text-base">
                <strong>{report.created}</strong> added, <strong>{report.updated}</strong> updated, <strong>{report.failed}</strong> not loaded.
              </p>
              {report.errors.length > 0 ? (
                <div className="overflow-x-auto rounded-xl border">
                  <table className="w-full text-sm">
                    <caption className="sr-only">Rows not loaded</caption>
                    <thead className="bg-muted/50 text-left">
                      <tr>
                        <th scope="col" className="px-3 py-2">
                          Row (after the header)
                        </th>
                        <th scope="col" className="px-3 py-2">
                          SKU
                        </th>
                        <th scope="col" className="px-3 py-2">
                          Why
                        </th>
                      </tr>
                    </thead>
                    <tbody>
                      {report.errors.map((row) => (
                        <tr key={row.line} className="border-t">
                          <td className="px-3 py-2 tabular-nums">{row.line}</td>
                          <td className="px-3 py-2">{row.sku || "-"}</td>
                          <td className="px-3 py-2">{row.message}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              ) : null}
            </div>
          ) : null}
        </DialogContent>
      </Dialog>
    </>
  );
}
