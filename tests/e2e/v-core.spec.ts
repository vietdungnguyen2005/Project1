import { expect, test } from "@playwright/test";

test("mobile users can create and move tasks without native drag", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "High-performance agile workspace" })).toBeVisible();

  await page.getByRole("button", { name: "Create task" }).click();
  const composer = page.getByRole("form", { name: "Task composer" });
  await expect(composer).toBeVisible();

  await composer.getByRole("textbox", { name: "Title", exact: true }).fill("Mobile production handoff");
  await composer.getByRole("textbox", { name: "Owner", exact: true }).fill("Dung Nguyen");
  await composer.getByLabel("Status").selectOption("backlog");
  await composer.getByRole("button", { name: "Add", exact: true }).click();

  await expect(page.getByRole("button", { name: "Mobile production handoff" })).toBeVisible();
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
