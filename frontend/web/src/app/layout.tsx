import type { Metadata, Viewport } from "next";
import { Geist_Mono, Poppins } from "next/font/google";

import { Providers } from "@/components/providers";
import { BRAND_NAME } from "@/lib/brand";

import "./globals.css";

// Poppins is not a variable font, so each weight the UI uses is named: normal, medium, semibold
// and bold. Monospace (receipts, the audit trail) stays Geist Mono.
const poppins = Poppins({ variable: "--font-poppins", subsets: ["latin"], weight: ["400", "500", "600", "700"] });
const geistMono = Geist_Mono({ variable: "--font-geist-mono", subsets: ["latin"] });

export const metadata: Metadata = {
  title: { default: BRAND_NAME, template: `%s · ${BRAND_NAME}` },
  description: "Point of sale and back office",
};

export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#ffffff" },
    { media: "(prefers-color-scheme: dark)", color: "#0a0a0a" },
  ],
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en"
      // next-themes sets the class before hydration; the mismatch it causes is expected.
      suppressHydrationWarning
      className={`${poppins.variable} ${geistMono.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col">
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
