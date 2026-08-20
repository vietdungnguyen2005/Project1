"use client";

import { useQueryClient } from "@tanstack/react-query";
import { useEffect } from "react";
import { DEMO_WORKSPACE_ID } from "@/lib/vcore-config";

const eventNames = ["task.created.v1", "task.renamed.v1", "task.moved.v1"];

export function useWorkspaceEvents() {
  const queryClient = useQueryClient();

  useEffect(() => {
    let retryTimer: number | undefined;
    let source: EventSource | undefined;
    let stopped = false;

    const connect = () => {
      if (stopped) {
        return;
      }

      source = new EventSource(`/api/workspaces/${DEMO_WORKSPACE_ID}/events`);
      const refresh = () => {
        void queryClient.invalidateQueries({ queryKey: ["tasks"] });
        void queryClient.invalidateQueries({ queryKey: ["activities"] });
      };
      eventNames.forEach((eventName) => source?.addEventListener(eventName, refresh));
      source.onerror = () => {
        source?.close();
        retryTimer = window.setTimeout(connect, 10_000);
      };
    };

    connect();
    return () => {
      stopped = true;
      source?.close();
      if (retryTimer !== undefined) {
        window.clearTimeout(retryTimer);
      }
    };
  }, [queryClient]);
}

