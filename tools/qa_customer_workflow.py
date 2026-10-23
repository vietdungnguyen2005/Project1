"""Synthetic customer acceptance through the real Pages Worker, Java and disposable DB."""
import os
import subprocess
from pathlib import Path
from playwright.sync_api import expect


def verify_customer_workflows(page, context, base):
    evidence = Path("docs/fde/evidence")
    evidence.mkdir(parents=True, exist_ok=True)
    workspace = "00000000-0000-0000-0000-000000000100"
    api = f"{base}/api/workspaces/{workspace}"
    screenshots = []
    for scenario, suffix, stages in [
        ("CLIENT_A", "A", ["BACKLOG", "IN_PROGRESS", "REVIEW", "DONE"]),
        ("CLIENT_B", "B", ["RECEIVED", "VALIDATION", "APPROVAL", "COMPLETED"]),
    ]:
        form = page.get_by_role("form", name="Create project and sprint")
        form.get_by_label("Customer scenario", exact=True).select_option(scenario)
        preview = form.get_by_role("list", name="Workflow preview")
        expect(preview).to_contain_text(stages[0])
        expect(form.get_by_role("button", name="Create stream", exact=True)).to_be_disabled()
        with page.expect_response(lambda r: r.url.endswith("/workflow-templates") and r.request.method == "POST") as activation:
            form.get_by_role("button", name="Validate and publish version", exact=True).click()
        assert activation.value.status == 201
        template = activation.value.json()
        assert template["synthetic"] and template["version"] == 1
        assert [c["name"] for c in template["columns"]] == stages
        assert template["columns"][1]["wipLimit"] == (3 if suffix == "A" else 2)
        for label, value in {
            "Project name": f"Synthetic Client {suffix} Pilot",
            "Project key": f"FDE{suffix}",
            "Sprint name": f"Client {suffix} acceptance",
            "Description": "Synthetic portfolio demonstration — not a real customer engagement",
            "Sprint goal": "Verify configured workflow and existing command guarantees",
        }.items():
            form.get_by_label(label, exact=True).fill(value)

        lost = {}
        if suffix == "A":
            def lose_committed_response(route):
                actual = route.fetch()
                assert actual.status == 201
                lost["result"] = actual.json()
                lost["key"] = route.request.headers["idempotency-key"]
                route.abort("failed")
            page.route(f"**/api/workspaces/{workspace}/projects", lose_committed_response)
            form.get_by_role("button", name="Create stream", exact=True).click()
            expect(form.get_by_role("alert")).to_contain_text("Outcome unconfirmed")
            expect(form.get_by_label("Project name", exact=True)).to_be_disabled()
            page.unroute(f"**/api/workspaces/{workspace}/projects", lose_committed_response)
        with page.expect_response(lambda r: r.url.endswith("/projects") and r.request.method == "POST") as created:
            form.get_by_role("button", name="Create stream", exact=True).click()
        assert created.value.status == 201
        project = created.value.json()
        assert project["workflowTemplateId"] == template["id"]
        assert [c["name"] for c in project["columns"]] == stages
        if lost:
            assert project == lost["result"]
            assert created.value.request.headers["idempotency-key"] == lost["key"]
        board = page.get_by_role("region", name=f"Workflow workbench FDE{suffix}")
        expect(board).to_be_visible()
        task_form = board.get_by_role("form", name="Create customer task", exact=True)
        count = 1 if suffix == "A" else 2
        for index in range(count):
            task_form.get_by_label("Customer task title", exact=True).fill(f"Synthetic {suffix} task {index + 1}")
            with page.expect_response(lambda r: r.url.endswith("/tasks") and r.request.method == "POST") as task:
                task_form.get_by_role("button", name="Add customer task", exact=True).click()
            assert task.value.status == 201
            expect(board.get_by_text(f"Synthetic {suffix} task {index + 1}", exact=True)).to_be_visible()
        task_url = f"{api}/projects/{project['id']}/tasks"
        rows = context.request.get(task_url).json()["items"]
        owner = next(m for m in context.request.get(f"{api}/overview").json()["members"] if m["role"] == "OWNER")
        first = rows[0]
        card = board.get_by_role("article", name=first["key"], exact=True)
        card.get_by_label(f"Assign {first['key']}", exact=True).select_option(owner["id"])
        with page.expect_response(lambda r: r.url.endswith("/assignee")) as assigned:
            card.get_by_role("button", name="Assign task", exact=True).click()
        assert assigned.value.status == 200
        expect(card).to_contain_text(f"v{assigned.value.json()['version']}")

        def move(row, stage, expected=200):
            selected = board.get_by_role("article", name=row["key"], exact=True)
            target = next(c["id"] for c in project["columns"] if c["name"] == stage)
            selected.get_by_label(f"Move {row['key']} to", exact=True).select_option(target)
            with page.expect_response(lambda r: r.url.endswith("/moves") and r.request.method == "POST") as moved:
                selected.get_by_role("button", name="Move task", exact=True).click()
            assert moved.value.status == expected
            if expected == 200:
                expect(board.get_by_role("region", name=f"Stage {stage}", exact=True).get_by_role("article", name=row["key"], exact=True)).to_be_visible()
            else:
                expect(board.get_by_role("status")).to_contain_text("Conflict:")

        move(first, stages[1])
        if suffix == "A":
            move(first, "REVIEW")
            move(first, "DONE")
        if suffix == "B":
            move(rows[1], "VALIDATION")
            move(first, "APPROVAL")
            move(rows[1], "APPROVAL", 409)
            current = context.request.get(task_url).json()["items"]
            approval = project["columns"][2]["id"]
            assert sum(r["columnId"] == approval for r in current) == 1
            conflict_image = evidence / "client-b-wip-conflict.png"
            board.screenshot(path=str(conflict_image))
            screenshots.append(str(conflict_image))
            move(first, "COMPLETED")
            move(rows[1], "APPROVAL")
        audit = context.request.get(f"{api}/activities?limit=100").json()["items"]
        assert sum(r["action"] == "PROJECT_CREATED" and r["aggregateId"] == project["id"] for r in audit) == 1
        assert any(r["action"] == "TASK_MOVED" for r in audit)
        assert any(r["action"] == "WORKFLOW_TEMPLATE_PUBLISHED" and r["aggregateId"] == template["id"] for r in audit)
        expect(board.get_by_text("created project", exact=False).first).to_be_visible()
        shot = evidence / f"client-{suffix.lower()}-desktop.png"
        board.screenshot(path=str(shot))
        screenshots.append(str(shot))
        container = os.environ["TEST_POSTGRES_CONTAINER"]
        sql = f"SELECT (SELECT count(*) FROM project WHERE id='{project['id']}'),(SELECT count(*) FROM sprint WHERE project_id='{project['id']}'),(SELECT count(*) FROM workflow_column WHERE project_id='{project['id']}');"
        result = subprocess.check_output(["docker", "exec", container, "psql", "-U", "vcore", "-d", "vcore", "-Atc", sql], text=True).strip()
        assert result == "1|1|4", result
        print(f"PASS synthetic Client {suffix}: preview, activation, pinned project, tasks/assignment/moves/audit; SQL project/sprint/columns={result}")
        if suffix == "B":
            page.set_viewport_size({"width": 390, "height": 844})
            page.evaluate("document.documentElement.classList.add('dark')")
            page.evaluate("document.activeElement?.blur()")
            assert page.evaluate("document.documentElement.scrollWidth <= window.innerWidth + 1")
            shot = evidence / "client-b-mobile-dark.png"
            board.evaluate("element => element.scrollIntoView({block: 'start', behavior: 'instant'})")
            skip = page.get_by_role("link", name="Skip to workspace", exact=True).bounding_box()
            assert skip is None or skip["y"] + skip["height"] <= 0, "Unfocused skip link must not cover the mobile UI"
            # A viewport capture avoids browser stitching artifacts from fixed-position skip links.
            page.screenshot(path=str(shot))
            screenshots.append(str(shot))
            page.set_viewport_size({"width": 1440, "height": 1000})
            page.evaluate("document.documentElement.classList.remove('dark')")
    print("PASS committed project response lost then same-key retry, and real Client B WIP conflict without excess occupancy")
    print("Screenshots:", screenshots)
