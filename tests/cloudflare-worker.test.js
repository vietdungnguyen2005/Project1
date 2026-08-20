import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../public/_worker.js";

describe("Cloudflare BFF", () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("rejects API traffic when its backend trust settings are absent", async () => {
    const response = await worker.fetch(new Request("https://v-core.pages.dev/api/session"), {
      ASSETS: { fetch: vi.fn() }
    });

    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toMatchObject({ title: "Backend unavailable" });
  });

  it("removes spoofed identity headers and injects the trusted BFF identity", async () => {
    const backendFetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        headers: { "Content-Type": "application/json" }
      })
    );
    vi.stubGlobal("fetch", backendFetch);

    const request = new Request("https://v-core.pages.dev/api/session", {
      headers: {
        "X-VCore-Bff-Key": "attacker-key",
        "X-VCore-User-Email": "attacker@example.com"
      }
    });
    const response = await worker.fetch(request, {
      BACKEND_ORIGIN: "https://api.example.com",
      BFF_SHARED_SECRET: "trusted-key",
      DEMO_USER_EMAIL: "owner@v-core.local",
      ASSETS: { fetch: vi.fn() }
    });

    expect(response.status).toBe(200);
    const [target, init] = backendFetch.mock.calls[0];
    expect(target.toString()).toBe("https://api.example.com/api/session");
    expect(init.headers.get("X-VCore-Bff-Key")).toBe("trusted-key");
    expect(init.headers.get("X-VCore-User-Email")).toBe("owner@v-core.local");
  });

  it("exposes the backend readiness endpoint through the public API namespace", async () => {
    const backendFetch = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ status: "UP" }), {
        headers: { "Content-Type": "application/json" }
      })
    );
    vi.stubGlobal("fetch", backendFetch);

    await worker.fetch(new Request("https://v-core.pages.dev/api/health"), {
      BACKEND_ORIGIN: "https://api.example.com",
      BFF_SHARED_SECRET: "trusted-key",
      ASSETS: { fetch: vi.fn() }
    });

    expect(backendFetch.mock.calls[0][0].toString()).toBe(
      "https://api.example.com/actuator/health"
    );
  });
});
