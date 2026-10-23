import { expect, test } from "@playwright/test";

test("a synthetic customer can preview a workflow without silently creating a default project", async ({ page }) => {
  await page.route("**/api/**", async (route) => {
    const path = new URL(route.request().url()).pathname;
    if (path === "/api/auth/session") return route.fulfill({ json: { authenticated: true, roles: ["ROLE_OPERATOR"], username: "owner@v-core.local", csrfToken: "fixture" } });
    if (path === "/api/session") return route.fulfill({ json: { user: { id: "owner", name: "Owner", email: "owner@v-core.local" }, workspaces: [{ id: "workspace", name: "Demo", slug: "demo", role: "OWNER" }] } });
    if (path.endsWith("/overview")) return route.fulfill({ json: { projects: [], members: [], invitations: [] } });
    if (path.endsWith("/workflow-templates")) return route.fulfill({ json: [] });
    if (path.endsWith("/workflow-presets")) return route.fulfill({ json: [{ key: "CLIENT_B_OPERATIONS", version: 1, name: "Client B — Business operations (synthetic)", scenario: "CLIENT_B", columns: [
      { name: "RECEIVED", category: "BACKLOG", position: 0, wipLimit: null }, { name: "COMPLETED", category: "DONE", position: 1, wipLimit: null }
    ] }] });
    return route.fulfill({ status: 404 });
  });
  await page.goto("/");
  await page.getByLabel("Customer scenario", { exact: true }).selectOption("CLIENT_B");
  await expect(page.getByRole("list", { name: "Workflow preview" })).toContainText("RECEIVED");
  await expect(page.getByRole("button", { name: "Create stream", exact: true })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Validate and publish version" })).toBeEnabled();
});
