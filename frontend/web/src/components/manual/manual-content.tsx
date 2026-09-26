import Link from "next/link";
import Markdown from "react-markdown";
import rehypeSlug from "rehype-slug";
import remarkGfm from "remark-gfm";

import { manualHref } from "@/lib/manuals";

/**
 * A manual's Markdown as a page. Headings get GitHub's anchors, so a manual's own contents list
 * and the links between manuals land where they do on GitHub.
 */
export function ManualContent({ markdown }: { markdown: string }) {
  return (
    <article className="manual max-w-3xl" data-testid="manual">
      <Markdown
        remarkPlugins={[remarkGfm]}
        rehypePlugins={[rehypeSlug]}
        components={{
          a: ({ href = "", children }) => {
            const target = manualHref(href);
            return target.startsWith("/") || target.startsWith("#") ? (
              <Link href={target}>{children}</Link>
            ) : (
              <a href={target} rel="noreferrer" target="_blank">
                {children}
              </a>
            );
          },
          table: ({ children }) => (
            <div className="overflow-x-auto">
              <table>{children}</table>
            </div>
          ),
        }}
      >
        {markdown}
      </Markdown>
    </article>
  );
}
