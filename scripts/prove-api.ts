import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const baseUrl = process.env.VCORE_API_URL ?? "https://v-core-saas.pages.dev";

async function readJson<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      "Accept": "application/json",
      ...(init?.headers ?? {})
    }
  });

  if (!response.ok) {
    throw new Error(`${path} failed with ${response.status}`);
  }

  return response.json() as Promise<T>;
}

async function main() {
  const taskId = "vc-api-proof";

  const health = await readJson<{ status: string; storage: string }>("/api/health");
  const session = await readJson<{ user: { email: string; provider: string } }>("/api/session");
  const tasksBefore = await readJson<{ tasks: Array<{ id: string }>; storage: string }>("/api/tasks");

  await readJson<{ task: { id: string } }>("/api/tasks", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      id: taskId,
      title: "Production API proof task",
      owner: "Automation",
      status: "backlog",
      priority: "medium",
      points: 3,
      updatedAt: "just now",
      tags: ["api-proof"]
    })
  });

  const patched = await readJson<{ task: { id: string; status: string } }>(`/api/tasks/${taskId}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      status: "done",
      updatedAt: "just now"
    })
  });

  const proof = {
    generatedAt: new Date().toISOString(),
    baseUrl,
    health,
    session,
    storage: tasksBefore.storage,
    tasksBefore: tasksBefore.tasks.length,
    createdTaskId: taskId,
    patchedStatus: patched.task.status
  };

  if (health.status !== "ok" || patched.task.status !== "done") {
    throw new Error("API proof failed");
  }

  const outputPath = resolve("reports/api-proof.json");
  mkdirSync(dirname(outputPath), { recursive: true });
  writeFileSync(outputPath, `${JSON.stringify(proof, null, 2)}\n`);
  console.log(JSON.stringify(proof, null, 2));
  console.log(`Saved API proof to ${outputPath}`);
}

void main();
