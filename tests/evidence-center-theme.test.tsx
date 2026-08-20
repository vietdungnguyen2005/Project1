import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { EvidenceCenter } from "@/components/evidence-center";

describe("EvidenceCenter theme surface", () => {
  it("uses the same semantic panel and foreground tokens as the rest of the page", () => {
    const markup = renderToStaticMarkup(<EvidenceCenter />);

    expect(markup).toContain("bg-panel text-foreground");
    expect(markup).not.toContain("bg-foreground text-background");
  });
});
