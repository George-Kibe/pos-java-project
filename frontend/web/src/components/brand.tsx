import Image from "next/image";

import { BRAND_NAME, BRAND_LOGO } from "@/lib/brand";
import { cn } from "@/lib/utils";

/** The Realhive mark. Decorative wherever the name is next to it. */
export function BrandLogo({ size = 32, className, decorative = true }: { size?: number; className?: string; decorative?: boolean }) {
  return (
    <Image
      src={BRAND_LOGO}
      width={size}
      height={size}
      alt={decorative ? "" : BRAND_NAME}
      // An SVG gains nothing from the image optimiser, which also refuses SVGs by default.
      unoptimized
      priority
      className={cn("shrink-0", className)}
    />
  );
}

/** The mark with the name beside it: the header and the sign-in pages. */
export function BrandLockup({ size = 32, className }: { size?: number; className?: string }) {
  return (
    <span className={cn("inline-flex items-center gap-2", className)}>
      <BrandLogo size={size} />
      <span className="font-semibold tracking-tight">{BRAND_NAME}</span>
    </span>
  );
}
