import { describe, expect, it, vi } from "vitest";
import { SessionCleanupRegistry } from "@/lib/session-cleanup-registry";

describe("SessionCleanupRegistry", () => {
  it("clears timers, aborts controllers, disconnects listeners, and flushes caches", () => {
    vi.useFakeTimers();
    const removeListener = vi.fn();
    const flushCache = vi.fn();
    const controller = new AbortController();
    const registry = new SessionCleanupRegistry();

    const timer = window.setInterval(() => undefined, 1000);
    registry.addTimer(timer);
    registry.addAbortController(controller);
    registry.addListener(removeListener);
    registry.addCacheFlush(flushCache);

    registry.flush();

    expect(controller.signal.aborted).toBe(true);
    expect(removeListener).toHaveBeenCalledTimes(1);
    expect(flushCache).toHaveBeenCalledTimes(1);

    vi.advanceTimersByTime(1000);
    expect(vi.getTimerCount()).toBe(0);
    vi.useRealTimers();
  });
});
