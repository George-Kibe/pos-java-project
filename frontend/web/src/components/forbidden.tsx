import { ShieldAlert } from "lucide-react";

import { Alert, AlertDescription, AlertTitle } from "@/components/ui/alert";

/** What a page shows someone without the permission it needs. */
export function Forbidden({ what }: { what: string }) {
  return (
    <Alert className="max-w-xl">
      <ShieldAlert aria-hidden />
      <AlertTitle>Not available to you</AlertTitle>
      <AlertDescription>
        Your role does not include {what}. If you need it, ask a manager or an administrator.
      </AlertDescription>
    </Alert>
  );
}
