import { activity, sprintMetrics } from "@/lib/mock-data";
import { AppShell } from "@/components/app-shell";

export default async function Home() {
  return (
    <AppShell
      initialActivity={activity}
      initialMetrics={sprintMetrics}
    />
  );
}
