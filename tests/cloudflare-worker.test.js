import { afterEach, describe, expect, it, vi } from "vitest";
import worker from "../public/_worker.js";

describe("Cloudflare BFF", () => {
  it("does not forward unsafe methods on read-only paths, including with query strings", async () => {
    const backendFetch = vi.fn();
    vi.stubGlobal("fetch", backendFetch);
    const response = await worker.fetch(new Request("https://ui.test/api/session?x=1", { method: "POST" }), {
      BACKEND_ORIGIN: "https://backend.test", BFF_SHARED_SECRET: "trusted"
    });
    expect(response.status).toBe(405);
    expect(backendFetch).not.toHaveBeenCalled();
  });
  it("rejects cross-origin writes before contacting the backend", async () => {
    const backendFetch = vi.fn();
    vi.stubGlobal("fetch", backendFetch);
    const response = await worker.fetch(new Request("https://ui.test/api/auth/login", {
      method: "POST", headers: { Origin: "https://attacker.test" }
    }), { BACKEND_ORIGIN: "https://backend.test", BFF_SHARED_SECRET: "trusted" });
    expect(response.status).toBe(403);
    expect(backendFetch).not.toHaveBeenCalled();
  });
  it("never creates user identity from email headers or a demo owner fallback", async () => {
    const backendFetch = vi.fn().mockResolvedValue(new Response("{}"));
    vi.stubGlobal("fetch", backendFetch);
    await worker.fetch(new Request("https://ui.test/api/session", { headers: { "Cf-Access-Authenticated-User-Email": "owner@v-core.local" } }), {
      BACKEND_ORIGIN: "https://backend.test", BFF_SHARED_SECRET: "trusted",
      DEMO_USER_EMAIL: "owner@v-core.local", ASSETS: { fetch: vi.fn() }
    });
    const headers = backendFetch.mock.calls[0][1].headers;
    expect(headers.has("X-VCore-User-Email")).toBe(false);
    expect(headers.has("Cf-Access-Authenticated-User-Email")).toBe(false);
  });
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

  it("removes spoofed identity headers and injects only the BFF service credential", async () => {
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
    expect(init.headers.get("X-VCore-User-Email")).toBeNull();
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
