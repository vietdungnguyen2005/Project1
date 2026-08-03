const columns = ["backlog", "in-progress", "review", "done"];
const now = () => new Date().toISOString();

const seedTasks = [
  {
    id: "vc-104",
    title: "Instrument INP budget for inline title edits",
    owner: "An Le",
    status: "backlog",
    priority: "high",
    points: 5,
    updatedAt: "2m ago",
    tags: ["web-vitals", "editor"]
  },
  {
    id: "vc-122",
    title: "Ship granular Kanban memo boundaries",
    owner: "Minh Tran",
    status: "in-progress",
    priority: "critical",
    points: 8,
    updatedAt: "9m ago",
    tags: ["performance", "kanban"]
  },
  {
    id: "vc-138",
    title: "Add long-session cleanup controller",
    owner: "Bao Nguyen",
    status: "in-progress",
    priority: "high",
    points: 5,
    updatedAt: "14m ago",
    tags: ["cleanup", "effects"]
  },
  {
    id: "vc-149",
    title: "Cache sprint metrics with TanStack Query",
    owner: "Linh Pham",
    status: "review",
    priority: "medium",
    points: 3,
    updatedAt: "23m ago",
    tags: ["query", "ssr"]
  },
  {
    id: "vc-151",
    title: "Validate dark mode contrast in command surfaces",
    owner: "Quan Vo",
    status: "done",
    priority: "low",
    points: 2,
    updatedAt: "41m ago",
    tags: ["a11y", "theme"]
  }
];

let schemaReady;

function json(data, init = {}) {
  return new Response(JSON.stringify(data), {
    ...init,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      "Cache-Control": "no-store",
      ...init.headers
    }
  });
}

function getPrincipal(request) {
  const accessEmail = request.headers.get("Cf-Access-Authenticated-User-Email");
  const demoEmail = request.headers.get("x-vcore-member-email");
  const email = accessEmail || demoEmail || "owner@v-core.local";
  const name = email.split("@")[0].replace(/[._-]/g, " ");

  return {
    email,
    name: name.charAt(0).toUpperCase() + name.slice(1),
    provider: accessEmail ? "cloudflare-access" : "demo",
    role: "owner"
  };
}

async function ensureSchema(env) {
  if (!env.DB) {
    return false;
  }

  schemaReady ??= (async () => {
    const schemaStatements = [
      "CREATE TABLE IF NOT EXISTS workspaces (id TEXT PRIMARY KEY, name TEXT NOT NULL, plan TEXT NOT NULL, created_at TEXT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS projects (id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, name TEXT NOT NULL, phase TEXT NOT NULL, created_at TEXT NOT NULL)",
      "CREATE TABLE IF NOT EXISTS tasks (id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, project_id TEXT NOT NULL, title TEXT NOT NULL, owner TEXT NOT NULL, status TEXT NOT NULL, priority TEXT NOT NULL, points INTEGER NOT NULL, updated_at TEXT NOT NULL, tags TEXT NOT NULL, position INTEGER NOT NULL DEFAULT 0)",
      "CREATE TABLE IF NOT EXISTS activity (id TEXT PRIMARY KEY, workspace_id TEXT NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL, created_at TEXT NOT NULL)"
    ];

    for (const statement of schemaStatements) {
      await env.DB.exec(statement);
    }

    await env.DB.prepare(
      "INSERT OR IGNORE INTO workspaces (id, name, plan, created_at) VALUES (?, ?, ?, ?)"
    ).bind("ws-core", "V-Core Product Lab", "Enterprise", now()).run();

    await env.DB.prepare(
      "INSERT OR IGNORE INTO projects (id, workspace_id, name, phase, created_at) VALUES (?, ?, ?, ?, ?)"
    ).bind("prj-sprint-24", "ws-core", "Sprint 24 Command Center", "delivery", now()).run();

    const count = await env.DB.prepare("SELECT COUNT(*) as count FROM tasks").first();
    if (!count || count.count === 0) {
      const statement = env.DB.prepare(`
        INSERT INTO tasks (
          id, workspace_id, project_id, title, owner, status, priority, points, updated_at, tags, position
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      `);

      await env.DB.batch(seedTasks.map((task, index) =>
        statement.bind(
          task.id,
          "ws-core",
          "prj-sprint-24",
          task.title,
          task.owner,
          task.status,
          task.priority,
          task.points,
          task.updatedAt,
          JSON.stringify(task.tags),
          index
        )
      ));
    }
  })();

  await schemaReady;
  return true;
}

function normalizeTask(row) {
  return {
    id: row.id,
    title: row.title,
    owner: row.owner,
    status: row.status,
    priority: row.priority,
    points: row.points,
    updatedAt: row.updated_at,
    tags: JSON.parse(row.tags || "[]")
  };
}

function validateTask(task) {
  return task &&
    typeof task.id === "string" &&
    typeof task.title === "string" &&
    typeof task.owner === "string" &&
    columns.includes(task.status) &&
    ["critical", "high", "medium", "low"].includes(task.priority) &&
    Number.isFinite(Number(task.points)) &&
    Array.isArray(task.tags);
}

async function getTasks(env) {
  await ensureSchema(env);

  if (!env.DB) {
    return { tasks: seedTasks, storage: "memory", syncVersion: now() };
  }

  const result = await env.DB.prepare(
    "SELECT * FROM tasks ORDER BY position ASC, id ASC LIMIT 200"
  ).all();

  return {
    tasks: result.results.map(normalizeTask),
    storage: "d1",
    syncVersion: now()
  };
}

async function createTask(request, env) {
  const body = await request.json().catch(() => null);

  if (!validateTask(body)) {
    return json({ error: "Invalid task payload" }, { status: 400 });
  }

  await ensureSchema(env);

  if (env.DB) {
    await env.DB.prepare(`
      INSERT OR REPLACE INTO tasks (
        id, workspace_id, project_id, title, owner, status, priority, points, updated_at, tags, position
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    `).bind(
      body.id,
      "ws-core",
      "prj-sprint-24",
      body.title.trim(),
      body.owner.trim(),
      body.status,
      body.priority,
      Number(body.points),
      body.updatedAt || now(),
      JSON.stringify(body.tags),
      Date.now()
    ).run();
  }

  return json({ task: body }, { status: 201 });
}

async function patchTask(request, env, taskId) {
  const body = await request.json().catch(() => null);

  if (!body || typeof taskId !== "string") {
    return json({ error: "Invalid patch payload" }, { status: 400 });
  }

  await ensureSchema(env);

  if (body.status && !columns.includes(body.status)) {
    return json({ error: "Invalid status" }, { status: 400 });
  }

  const existing = env.DB
    ? await env.DB.prepare("SELECT * FROM tasks WHERE id = ?").bind(taskId).first()
    : seedTasks.find((task) => task.id === taskId);

  if (!existing) {
    return json({ error: "Task not found" }, { status: 404 });
  }

  const current = env.DB ? normalizeTask(existing) : existing;
  const nextTask = {
    ...current,
    title: typeof body.title === "string" && body.title.trim() ? body.title.trim() : current.title,
    status: body.status || current.status,
    updatedAt: body.updatedAt || now()
  };

  if (env.DB) {
    await env.DB.prepare(
      "UPDATE tasks SET title = ?, status = ?, updated_at = ? WHERE id = ?"
    ).bind(nextTask.title, nextTask.status, nextTask.updatedAt, taskId).run();
  }

  return json({ task: nextTask });
}

async function handleApi(request, env) {
  const url = new URL(request.url);
  const principal = getPrincipal(request);

  if (url.pathname === "/api/health") {
    const hasSchema = await ensureSchema(env);
    return json({
      status: "ok",
      storage: hasSchema ? "d1" : "memory",
      authenticated: Boolean(principal.email),
      generatedAt: now()
    });
  }

  if (url.pathname === "/api/session") {
    return json({
      user: principal,
      workspace: {
        id: "ws-core",
        name: "V-Core Product Lab",
        plan: "Enterprise"
      }
    });
  }

  if (url.pathname === "/api/workspaces") {
    return json({
      workspaces: [
        {
          id: "ws-core",
          name: "V-Core Product Lab",
          plan: "Enterprise",
          projects: [
            {
              id: "prj-sprint-24",
              name: "Sprint 24 Command Center",
              phase: "delivery"
            }
          ]
        }
      ]
    });
  }

  if (url.pathname === "/api/tasks" && request.method === "GET") {
    return json(await getTasks(env));
  }

  if (url.pathname === "/api/tasks" && request.method === "POST") {
    return createTask(request, env);
  }

  const taskMatch = url.pathname.match(/^\/api\/tasks\/([^/]+)$/);
  if (taskMatch && request.method === "PATCH") {
    return patchTask(request, env, decodeURIComponent(taskMatch[1]));
  }

  return json({ error: "Not found" }, { status: 404 });
}

const worker = {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname.startsWith("/api/")) {
      return handleApi(request, env);
    }

    return env.ASSETS.fetch(request);
  }
};

export default worker;
