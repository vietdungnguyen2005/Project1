"use client";

import { useQuery } from "@tanstack/react-query";
import { useEffect } from "react";
import { fetchCloudSession, fetchCloudTasks } from "@/lib/task-api";
import { useTaskStore } from "@/store/task-store";

export function useTaskCloudSync() {
  const hydrateTasks = useTaskStore((state) => state.hydrateTasks);
  const setSyncState = useTaskStore((state) => state.setSyncState);
  const sessionQuery = useQuery({
    queryKey: ["session"],
    queryFn: ({ signal }) => fetchCloudSession(signal),
    retry: 1,
    staleTime: 1000 * 60 * 10
  });

  const taskQuery = useQuery({
    queryKey: ["tasks"],
    queryFn: ({ signal }) => fetchCloudTasks(signal),
    retry: 1,
    staleTime: 1000 * 20
  });

  useEffect(() => {
    if (taskQuery.data) {
      hydrateTasks(taskQuery.data.tasks);
      setSyncState("cloud");
      return;
    }

    if (taskQuery.isError) {
      setSyncState("offline", "Cloud API unavailable; using local cache.");
    }
  }, [hydrateTasks, setSyncState, taskQuery.data, taskQuery.isError]);

  return {
    session: sessionQuery.data,
    storage: taskQuery.data?.storage,
    isCloudReady: taskQuery.isSuccess,
    isSyncing: taskQuery.isFetching
  };
}
