"use client";

import { Button } from "@/components/ui/button";

/**
 * A page that failed to render - most often because a service could not be reached. Nothing the
 * user did is lost by trying again.
 */
export default function ErrorPage({ reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="mx-auto flex max-w-md flex-1 flex-col items-center justify-center gap-4 p-6 text-center">
      <h1 className="text-2xl font-semibold">This page could not load</h1>
      <p className="text-muted-foreground">
        The POS services may be unavailable for a moment. Nothing was changed.
      </p>
      <Button onClick={reset}>Try again</Button>
    </div>
  );
}
