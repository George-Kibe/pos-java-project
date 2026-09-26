import { ApiError } from "@/lib/api/errors";
import { errorResponse } from "@/lib/api/respond";
import { getCurrentUser } from "@/lib/auth/dal";
import { manualPdf } from "@/lib/manual-pdf";
import { findManual, readManual } from "@/lib/manuals";

/**
 * A manual as a PDF, built from the same Markdown the Manual page shows, so a printed copy is the
 * current one. Signed-in users only, like the page.
 */
export async function GET(_request: Request, { params }: RouteContext<"/api/manuals/[slug]">) {
  if (!(await getCurrentUser())) {
    return errorResponse(new ApiError({ status: 401, title: "Unauthorized", code: "session.ended", detail: "Please sign in again." }));
  }
  const manual = findManual((await params).slug);
  const markdown = manual ? await readManual(manual) : null;
  if (!manual || markdown === null) {
    return errorResponse(new ApiError({ status: 404, title: "Not found", code: "manual.not_found", detail: "There is no such manual." }));
  }
  const pdf = await manualPdf(manual, markdown);
  return new Response(new Uint8Array(pdf), {
    headers: {
      "Content-Type": "application/pdf",
      "Content-Disposition": `attachment; filename="realhive-pos-${manual.slug}-manual.pdf"`,
      "Cache-Control": "private, no-store",
    },
  });
}
