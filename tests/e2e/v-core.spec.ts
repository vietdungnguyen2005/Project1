import { expect, test } from "@playwright/test";

test("mobile users can create and move tasks without native drag", async ({ page }) => {
  let taskVersion = 0;
  let createCommandCount = 0;
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());

    if (url.pathname === "/api/session") {
      return route.fulfill({
        json: {
          user: { id: "user-id", email: "owner@v-core.local", name: "Dung Nguyen" },
          workspaces: [{ id: "workspace-id", name: "V-Core", slug: "v-core", role: "OWNER" }]
        }
      });
    }

    if (url.pathname.endsWith("/activities")) {
      return route.fulfill({ json: { items: [] } });
    }

    if (url.pathname.endsWith("/events")) {
      return route.fulfill({ status: 204 });
    }

    if (request.method() === "GET" && url.pathname.endsWith("/tasks")) {
      return route.fulfill({ json: { items: [], truncated: false } });
    }

    if (request.method() === "POST" && url.pathname.endsWith("/tasks")) {
      createCommandCount += 1;
      const command = request.postDataJSON();
      return route.fulfill({
        status: 201,
        json: {
          id: "task-id",
          key: "VC-1",
          title: command.title,
          owner: "Dung Nguyen",
          status: "backlog",
          priority: command.priority,
          points: command.points,
          tags: command.tags,
          columnId: command.columnId,
          position: 1000,
          version: taskVersion,
          updatedAt: new Date().toISOString()
        }
      });
    }

    if (request.method() === "POST" && url.pathname.endsWith("/moves")) {
      const command = request.postDataJSON();
      taskVersion += 1;
      return route.fulfill({
        json: {
          id: "task-id",
          status: "done",
          columnId: command.targetColumnId,
          position: command.position,
          version: taskVersion,
          updatedAt: new Date().toISOString()
        }
      });
    }

    return route.fulfill({ status: 404 });
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "High-performance agile workspace" })).toBeVisible();

  await page.getByRole("button", { name: "Create task" }).click();
  const composer = page.getByRole("form", { name: "Task composer" });
  await expect(composer).toBeVisible();

  await composer.getByRole("textbox", { name: "Title", exact: true }).fill("Mobile production handoff");
  await composer.getByLabel("Status").selectOption("backlog");
  await composer.getByRole("button", { name: "Add", exact: true }).click();

  await expect(page.getByRole("button", { name: "Mobile production handoff" })).toHaveCount(1);
  expect(createCommandCount).toBe(1);
  await page.getByLabel("Move task Mobile production handoff").selectOption("done");
  await expect(
    page.getByRole("article", { name: "Done column" }).getByRole("button", { name: "Mobile production handoff" })
  ).toBeVisible();
});

test("production shell has no missing favicon request", async ({ page }) => {
  const responses: Array<{ url: string; status: number }> = [];
  page.on("response", (response) => {
    if (response.url().endsWith("/favicon.ico")) {
      responses.push({ url: response.url(), status: response.status() });
    }
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Sprint Board" })).toBeVisible();
  await page.goto("/favicon.ico");

  expect(responses.every((response) => response.status < 400)).toBe(true);
});

test("owners can operate the workspace and inspect recruiter-facing evidence", async ({ page }) => {
  const invitations: Array<Record<string, string>> = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === "GET" && url.pathname.endsWith("/overview")) {
      return route.fulfill({
        json: {
          projects: [{
            id: "project-id",
            name: "Sprint 24 Command Center",
            key: "VC",
            description: "Conflict-safe delivery",
            activeSprint: { id: "sprint-id", name: "Sprint 24", goal: "Ship safely", status: "ACTIVE" },
            columns: [{ id: "column-id", name: "In progress", category: "IN_PROGRESS", position: 1, wipLimit: 3, version: 0 }]
          }],
          members: [{ id: "owner-id", name: "Demo Owner", email: "owner@v-core.local", role: "OWNER" }],
          invitations
        }
      });
    }
    if (request.method() === "POST" && url.pathname.endsWith("/invitations")) {
      const command = request.postDataJSON();
      const invitation = { id: "invite-id", email: command.email, role: command.role, status: "PENDING", expiresAt: "2026-08-16T00:00:00Z" };
      invitations.push(invitation);
      return route.fulfill({ status: 201, json: invitation });
    }
    return route.fulfill({ status: 404 });
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Workspace Operations" })).toBeVisible();
  await expect(page.getByText("POSTGRES LIVE")).toBeVisible();

  const inviteForm = page.getByRole("form", { name: "Invite workspace member" });
  await inviteForm.getByLabel("Email").fill("engineer@example.jp");
  await inviteForm.getByRole("button", { name: "Invite" }).click();
  await expect(page.getByText("engineer@example.jp")).toBeVisible();

  await expect(page.getByRole("heading", { name: "Claims you can rerun." })).toBeVisible();
  await expect(page.getByRole("link", { name: /Open CI runs/ })).toHaveAttribute("href", /github\.com/);
});

test("long active task titles do not widen the mobile viewport", async ({ page }) => {
  const longTitle = "Coordinate the cross-functional production readiness handoff without clipping narrow mobile screens";
  await page.route("**/api/**", async (route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (url.pathname === "/api/session") {
      return route.fulfill({
        json: {
          user: { id: "user-id", email: "owner@v-core.local", name: "Dung Nguyen" },
          workspaces: [{ id: "workspace-id", name: "V-Core", slug: "v-core", role: "OWNER" }]
        }
      });
    }
    if (request.method() === "GET" && url.pathname.endsWith("/tasks")) {
      return route.fulfill({
        json: {
          items: [{
            id: "task-id", key: "VC-999", title: longTitle, owner: "Dung Nguyen", status: "backlog",
            priority: "high", points: 5, tags: ["handoff"], columnId: "column-id", position: 1000,
            version: 0, updatedAt: "2026-08-20T13:01:32.554760Z"
          }],
          truncated: false
        }
      });
    }
    if (url.pathname.endsWith("/overview")) return route.fulfill({ json: { projects: [], members: [], invitations: [] } });
    if (url.pathname.endsWith("/activities") || url.pathname.endsWith("/comments")) return route.fulfill({ json: { items: [] } });
    if (url.pathname.endsWith("/events")) return route.fulfill({ status: 204 });
    return route.fulfill({ status: 404 });
  });

  await page.goto("/");
  await expect(page.getByLabel("Active task")).toContainText("VC-999");
  const dimensions = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth
  }));
  expect(dimensions.content).toBeLessThanOrEqual(dimensions.viewport);
});
