"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { SessionCleanupRegistry } from "@/lib/session-cleanup-registry";
import { useTaskStore } from "@/store/task-store";

export function useSessionCleanup() {
  const queryClient = useQueryClient();

  useEffect(() => {
    const registry = new SessionCleanupRegistry();

    const pollingController = registry.addAbortController(new AbortController());

    registry.addTimer(window.setInterval(() => {
      if (pollingController.signal.aborted) {
        return;
      }

      queryClient.invalidateQueries({ queryKey: ["sprint-metrics"] });
    }, 1000 * 60 * 5));

    const onVisibilityChange = () => {
      if (document.visibilityState === "hidden") {
        queryClient.cancelQueries();
      }
    };
    document.addEventListener("visibilitychange", onVisibilityChange, {
      passive: true
    });
    registry.addListener(() => {
      document.removeEventListener("visibilitychange", onVisibilityChange);
    });
    registry.addCacheFlush(() => {
      useTaskStore.getState().clearEphemeralState();
      queryClient.cancelQueries();
      queryClient.removeQueries({ queryKey: ["sprint-metrics"] });
    });

    return () => registry.flush();
  }, [queryClient]);
}
