from pathlib import Path
from playwright.sync_api import Route, sync_playwright


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "docs" / "evidence"
OUTPUT.mkdir(parents=True, exist_ok=True)


OVERVIEW = {
    "projects": [{
        "id": "00000000-0000-0000-0000-000000000200",
        "name": "Sprint 24 Command Center",
        "key": "VC",
        "description": "Portfolio workspace demonstrating conflict-safe delivery workflows.",
        "activeSprint": {
            "id": "00000000-0000-0000-0000-000000000300",
            "name": "Sprint 24",
            "goal": "Ship a reliable collaboration workflow",
            "status": "ACTIVE",
        },
        "columns": [
            {"id": "column-1", "name": "Backlog", "category": "BACKLOG", "position": 0, "wipLimit": 100, "version": 0},
            {"id": "column-2", "name": "In progress", "category": "IN_PROGRESS", "position": 1, "wipLimit": 3, "version": 0},
            {"id": "column-3", "name": "Review", "category": "IN_PROGRESS", "position": 2, "wipLimit": 2, "version": 0},
            {"id": "column-4", "name": "Done", "category": "DONE", "position": 3, "wipLimit": 100, "version": 0},
        ],
    }],
    "members": [
        {"id": "owner-id", "name": "Demo Owner", "email": "owner@v-core.local", "role": "OWNER"},
        {"id": "member-id", "name": "Mai Tran", "email": "member@v-core.local", "role": "MEMBER"},
    ],
    "invitations": [],
}


def route_api(route: Route) -> None:
    path = route.request.url.split("?", 1)[0]
    if path.endswith("/api/session"):
        route.fulfill(json={
            "user": {"id": "owner-id", "email": "owner@v-core.local", "name": "Demo Owner"},
            "workspaces": [{"id": "workspace-id", "name": "V-Core Product Lab", "slug": "v-core-product-lab", "role": "OWNER"}],
        })
    elif path.endswith("/overview"):
        route.fulfill(json=OVERVIEW)
    elif path.endswith("/tasks"):
        route.fulfill(json={"items": [{
            "id": "task-id",
            "key": "VC-104",
            "title": "Prove conflict-safe delivery",
            "owner": "Mai Tran",
            "status": "in-progress",
            "priority": "high",
            "points": 5,
            "tags": ["reliability", "evidence"],
            "columnId": "column-2",
            "position": 1000,
            "version": 0,
            "updatedAt": "2026-08-09T10:00:00Z",
        }], "truncated": False})
    elif path.endswith("/comments"):
        route.fulfill(json={"items": []})
    elif path.endswith("/activities"):
        route.fulfill(json={"items": []})
    elif path.endswith("/events"):
        route.fulfill(status=204)
    else:
        route.fulfill(status=404, json={"title": "Visual QA route not mocked"})


def inspect_page(browser, name: str, width: int, height: int) -> None:
    context = browser.new_context(viewport={"width": width, "height": height})
    page = context.new_page()
    errors = []
    page.on("console", lambda message: errors.append(f"console:{message.type}:{message.text}") if message.type == "error" else None)
    page.on("pageerror", lambda error: errors.append(f"page:{error}"))
    page.route("**/api/**", route_api)
    page.goto("http://127.0.0.1:3021", wait_until="networkidle")
    page.get_by_role("heading", name="Workspace Operations").wait_for()
    page.get_by_role("heading", name="Claims you can rerun.").wait_for()
    page.screenshot(path=str(OUTPUT / f"{name}.png"), full_page=True)
    document_width = page.evaluate("document.documentElement.scrollWidth")
    viewport_width = page.evaluate("document.documentElement.clientWidth")
    if document_width > viewport_width + 1:
        errors.append(f"horizontal-overflow:{document_width}>{viewport_width}")
    if errors:
        raise AssertionError(f"{name} visual QA failed: {errors}")
    print(f"{name}: {width}x{height}, no console errors, no horizontal overflow")
    context.close()


with sync_playwright() as playwright:
    chromium = playwright.chromium.launch(headless=True)
    inspect_page(chromium, "desktop", 1440, 1000)
    inspect_page(chromium, "mobile", 393, 852)
    chromium.close()
