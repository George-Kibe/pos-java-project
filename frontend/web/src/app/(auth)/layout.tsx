import type { ReactNode } from "react";

import { BrandLogo } from "@/components/brand";
import { BRAND_NAME } from "@/lib/brand";

export default function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <main className="flex flex-1 items-center justify-center bg-muted/40 p-4">
      <div className="grid w-full max-w-md gap-6">
        <div className="flex flex-col items-center gap-3 text-center">
          <BrandLogo size={72} />
          <p className="text-xl font-semibold tracking-tight">{BRAND_NAME}</p>
        </div>
        {children}
      </div>
    </main>
  );
}
