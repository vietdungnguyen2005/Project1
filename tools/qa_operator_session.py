"""Real Cloudflare Pages Worker + Java + PostgreSQL smoke, without API fixtures."""
import os
import time
from urllib.request import Request, urlopen
from pathlib import Path
from playwright.sync_api import sync_playwright, expect, TimeoutError as PlaywrightTimeoutError

base = os.environ["BASE_URL"]
output = Path("backend/target/operator-browser")
output.mkdir(parents=True, exist_ok=True)

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    try:
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        page = context.new_page()
        page.goto(base)
        try:
            page.wait_for_load_state("networkidle", timeout=5_000)
        except PlaywrightTimeoutError:
            # The real SSE connection stays open. Wait for hydrated session UI, not a fake idle stream.
            expect(page.get_by_text("Public demo / read-only · Sign in to make changes", exact=True)).to_be_visible()
        print("Rendered headings:", page.get_by_role("heading").all_text_contents())
        expect(page.get_by_role("button", name="Create task", exact=True)).to_be_disabled()
        session = context.request.get(f"{base}/api/session").json()
        assert session["user"]["email"] == "viewer@v-core.local"
        assert session["workspaces"][0]["role"] == "VIEWER"
        overview_url = f"{base}/api/workspaces/00000000-0000-0000-0000-000000000100/overview"
        assert context.request.get(overview_url).status == 200
        page.screenshot(path=str(output / "public-read-only.png"), full_page=True)
        target = f"{base}/api/workspaces/00000000-0000-0000-0000-000000000100/projects"
        assert context.request.post(target, data={}).status == 401
        page.get_by_text("Operator sign-in", exact=True).click()
        page.get_by_label("Username", exact=True).fill(os.environ["TEST_OPERATOR_USERNAME"])
        page.get_by_label("Password", exact=True).fill(os.environ["TEST_OPERATOR_PASSWORD"])
        with page.expect_response(lambda r: r.url.endswith("/auth/login")) as login:
            page.get_by_role("button", name="Sign in", exact=True).click()
        assert login.value.status == 204
        expect(page.get_by_role("button", name="Sign out")).to_be_visible()
        expect(page.get_by_role("button", name="Create task", exact=True)).to_be_enabled()
        assert context.request.get(overview_url).status == 200
        cookie = next(c for c in context.cookies() if c["name"] == "VCORE_SESSION")
        assert cookie["httpOnly"] and cookie["sameSite"] == "Lax"
        assert context.request.post(target, data={}).status == 403
        page.get_by_role("button", name="Create task", exact=True).click()
        form = page.get_by_role("form", name="Task composer")
        form.get_by_role("textbox", name="Title", exact=True).fill("Real authenticated browser evidence")
        with page.expect_response(lambda r: r.url.endswith("/tasks") and r.request.method == "POST") as created:
            form.get_by_role("button", name="Add", exact=True).click()
        assert created.value.status == 201
        page.screenshot(path=str(output / "operator-session.png"), full_page=True)
        events_url = f"{base}/api/workspaces/00000000-0000-0000-0000-000000000100/events"
        stream_request = Request(events_url, headers={"Cookie": f"VCORE_SESSION={cookie['value']}", "Accept": "text/event-stream"})
        # Independent HTTP connection: the page reload on logout cannot close this socket for us.
        with urlopen(stream_request, timeout=5) as stream:
            assert stream.status == 200
            assert b"connected" in stream.readline()
            with page.expect_response(lambda r: r.url.endswith("/auth/logout")) as logout:
                page.get_by_role("button", name="Sign out").click()
            assert logout.value.status == 204
            deadline = time.monotonic() + 5
            while stream.readline():
                assert time.monotonic() < deadline, "SSE did not close after logout"
            print("PASS: independent real SSE connection reached EOF after logout")
        expect(page.get_by_role("button", name="Create task", exact=True)).to_be_disabled()
        assert context.request.post(target, data={}).status == 401
        assert context.request.get(f"{base}/api/session").json()["user"]["email"] == "viewer@v-core.local"
        assert context.request.get(overview_url).status == 200
        print("PASS: real Worker BFF, public VIEWER, authenticated login, HttpOnly cookie, CSRF, create 201, logout.")
    finally:
        browser.close()
