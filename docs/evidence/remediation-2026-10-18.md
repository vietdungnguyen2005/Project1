# V-Core — remediation evidence, đợt đầu

Ngày: 2026-10-18 (Asia/Bangkok). Base commit `5182e8d` + local working-tree changes; chưa commit/push/deploy. Đây là bằng chứng local, không phải kết quả production.

## Verification — đợt WIP/mutation đầu tiên

Chạy từ thư mục `backend`:

```powershell
.\mvnw.cmd -q spotless:apply verify
```

Exit code **0**. Surefire: **14 tests, 0 failures, 0 errors, 0 skipped**. Integration tests dùng PostgreSQL 17.6 và Redis 8.2 Testcontainers; validation tests dùng Jakarta/Hibernate Validator thực. Spotless format/check và Maven package đã qua trong verify.

Có thể chạy lại lệnh trên để tái tạo kết quả. Reports local: `backend/target/surefire-reports/TEST-*.xml`; log `backend/target/remediation-verify.log`. Target artifacts không được đưa vào Git.

## Những thay đổi đã xác minh

- Mutation/admin role được đọc trực tiếp từ PostgreSQL; cache role không còn quyết định quyền ghi.
- Đổi WIP vẫn lock column trước khi count; limit thấp hơn occupancy trả 409, không ghi column/audit/idempotency response thành công.

| Regression test | Trước sửa | Sau sửa |
| --- | --- | --- |
| `rejectsAWipLimitBelowTheCurrentOccupancy` | 200 khi đặt WIP=1 dưới số task hiện có | 409 |
| `mutationRejectsARevokedRoleEvenWhenRedisStillCachesOwner` | 200 dù DB VIEWER, Redis OWNER | 403 |

Source: [WorkspaceAccessService](../../backend/src/main/java/dev/vcore/workspace/application/WorkspaceAccessService.java), [WorkspaceOperationsService](../../backend/src/main/java/dev/vcore/workspace/application/WorkspaceOperationsService.java), [integration tests](../../backend/src/test/java/dev/vcore/VCoreApplicationTest.java).

## Giới hạn còn lại

Phần session và quyền đọc đã được sửa tiếp trong mục dưới. Chưa bổ sung toàn bộ race test admin/create/move. Idempotency expiry và SSE recovery còn theo plan. Không coi các thay đổi này là hoàn tất toàn bộ AUTH-1/CORE-1/CORE-2.

## AUTH-1 / CORE-1 — identity, sessions và quyền đọc

Snapshot: base commit `5182e8d` + working tree local ngày 2026-10-18; chưa commit/push/deploy.

### Verification mới nhất

- Backend: `./mvnw.cmd -q spotless:apply verify` từ `backend`, exit 0; **20 tests, 0 failures/errors/skipped**. PostgreSQL 17.6 và Redis 8.2 Testcontainers.
- Frontend: `npm run lint`, `npm run typecheck`, `npm test`, `npm run build` đều exit 0; **29 unit tests**.
- `npx playwright test` exit 0; **8 desktop/mobile UI fixture tests**. Fixture xác nhận UI/contract, không thay cho backend thật.
- Compose config và `git diff --check` qua. Không chạy lại performance benchmark/Lighthouse; số performance cũ không phải phép đo của đợt auth này.

Logs tái tạo: `backend/target/auth-verify.log`, `auth-build.log`, `auth-e2e.log`; Surefire XML dưới `backend/target/surefire-reports/`. Target artifacts ignored.

### Behavior được xác minh

| Scenario | Before | After |
| --- | --- | --- |
| BFF secret + forged owner email tạo project | 201 | 401, không có user session |
| Public GET với forged owner email | Có thể chọn owner qua header | Dedicated Demo Viewer, workspace role VIEWER |
| Xóa membership, Redis còn OWNER, đọc tasks | 200 | 403 |
| Session hết hạn / account disabled / logout | Chưa có user-session protocol | Mutation 401 |
| Logged-in mutation không có CSRF | Chưa bảo vệ | 403 |
| Hai request load cùng session rồi cùng thêm attribute | Duplicate primary key | Hai save thành công; last committed attribute wins |
| Đọc demo giữa CSRF bootstrap và auth-session refresh | Demo bị hiểu như login, CSRF đổi | Token ổn định; demo vẫn anonymous |

Migration V6 thêm BCrypt account liên kết app_user, Spring Session JDBC, login windows và một VIEWER riêng cho demo workspace. Bootstrap không tạo workspace OWNER, không overwrite account đã tồn tại và không cho bootstrap demo viewer. Existing migrations không sửa.

Worker loại bỏ email headers, không dùng `DEMO_USER_EMAIL` hoặc email Cloudflare Access làm identity; allowlist method + pathname và origin guard nằm trước upstream. BFF secret chỉ xác nhận service trust. Cookie/CSRF được forward; user/workspace role kiểm tra lại tại Java.

Public demo chỉ bật khi backend cấu hình `VCORE_DEMO_READ_ENABLED=true`. Demo dùng anonymous authentication với ROLE_DEMO_VIEWER, không giả lập một login thật và không kích hoạt CSRF/session rotation. Mutations cần account role OPERATOR rồi tiếp tục kiểm tra workspace membership/role thực trong PostgreSQL. Redis membership authorization cache đã bỏ; Redis connection/health vẫn được cấu hình, không claim đang dùng cache quyền.

Session cookie HttpOnly/Secure/SameSite=Lax, idle 15 phút, login đổi ID; logout/disabled/expiry có test qua session thật. Secure cookie chỉ tắt cho local HTTP; production startup guard từ chối tắt. Login throttle PostgreSQL có 10 attempts/minute/backend peer address, không claim per-end-user-IP sau BFF.

Session attribute concurrency dùng `PostgreSqlJdbcIndexedSessionRepositoryCustomizer` có sẵn trong Spring Session, không tự thay session crypto/protocol. Test dùng hai snapshot session độc lập rồi save cùng attribute để tái hiện deterministic insert race. Đây không phải guarantee linearizable session state hoặc retroactive revocation.

### Real browser smoke

`pwsh -NoProfile -File tools/verify-operator-session.ps1` đã qua với frontend export → **Wrangler Pages Worker thật** → Java JAR → PostgreSQL/Redis disposable, không mock API:

public VIEWER → mutation 401 → login 204 + cookie HttpOnly/SameSite → missing-CSRF 403 → UI create task 201 → logout 204 → mutation 401, public GET/overview vẫn 200. Script và `tools/qa_operator_session.py` tái tạo được flow; screenshot/log dưới `backend/target/operator-browser/` được xem để kiểm tra layout.

Real SSE giữ connection mở nên smoke không yêu cầu network-idle vô hạn: thử idle có timeout rồi đợi session UI hydrate. Một lượt trước đó phát hiện duplicate session attribute trong log; chỉ lượt sau khi thêm standard PostgreSQL customizer mới được dùng làm proof ổn định.

### Phần còn lại / rollout

**Chưa deploy.** Apply V6 + backend config trước, sau đó frontend/Worker, smoke live đúng commit. Không cần đổi repo/URL.

Phần active SSE revalidation đã được bổ sung trong đợt tiếp theo dưới đây. Session không hủy hồi tố mutation đã được authorize. Cross-actor idempotency scope/expiry/retention, SSE convergence/multi-instance và race admin/create/move mở rộng vẫn theo plan. Chưa có public signup/password recovery; operator credentials phải provision phía server và không được công khai trong demo.

## CORE-1 follow-up — revoke các SSE subscription đang mở

Snapshot vẫn là base `5182e8d` + working tree local, chưa commit/push/deploy. Không có migration/schema change mới cho đợt SSE này.

### Test và kết quả

`./mvnw.cmd -q spotless:apply verify` từ `backend` exit **0**: **28 tests, 0 failures, 0 errors, 0 skipped** (27 PostgreSQL/Redis integration invocations + 1 isolated hub failure-policy test). Log: `backend/target/sse-verify.log`.

`npm run lint`, `npm run typecheck`, `npm test` cũng exit 0; **29 frontend unit tests**. Frontend source/UI không đổi trong đợt SSE này; không đo lại Lighthouse/performance hoặc claim số mới.

| Scenario / test | Before | After |
| --- | --- | --- |
| `anOpenStreamStopsBeforeSendingAnEventAfterMembershipRemoval` | Event marker vẫn đi qua stream sau khi xóa membership | Không gửi marker; hoàn tất stream |
| Logout / expiry / disabled account sau khi subscribe | Stream cũ chỉ có handshake authorization | Original session/account bị recheck; không gửi marker và đóng stream |
| `anIdleStreamRevalidatesWithoutExtendingTheLoginSession` | Heartbeat không kiểm tra access | Heartbeat kiểm tra DB; expired session bị đóng, last_access_time không được kéo dài |
| Public demo VIEWER | Phải giữ khả năng đọc demo | Nhận hint hợp lệ; mất membership thì heartbeat đóng stream |
| Session của user khác | Cần tránh dùng nhầm identity/session | Session/account/user binding bị từ chối; VIEWER account hợp lệ vẫn đọc được |
| Authorization storage không đọc được (isolated failure test) | Chưa có policy cho active stream | Close/unregister, không tự mở lại khi storage hồi phục |
| Public SSE async completion sau revoke | Regression ném “response is already committed” | Container ASYNC completion qua, initial REQUEST vẫn được bảo vệ |

Regressions có log red riêng: `sse-membership-red.log`, `sse-demo-red.log`, `sse-demo-dispatch-red.log`. Membership regression fail vì marker thật xuất hiện trong response; async-dispatch regression fail đúng Servlet/Spring Security error, không phải build/dependency failure.

### Cơ chế

- `SseSubscriptionAccess` truy vấn PostgreSQL: current workspace membership + enabled account + original session ID, principal binding và expiry. Explicit public demo chỉ dùng dedicated viewer với membership hiện tại.
- Hub kiểm tra lúc subscribe, trước **mỗi event** và **mỗi heartbeat**, không chỉ khi handshake.
- Không đọc/refresh Spring Session qua servlet trên heartbeat; SQL chỉ đọc nên stream không giữ login sống mãi.
- Denial hoặc DataAccessException làm stream bị gỡ và đóng. Unit failure test dùng một policy double ném DataAccessException, không giả nhận đã dừng PostgreSQL thật.
- Hub dùng registry compute và trạng thái đóng theo subscriber để cleanup idempotent; onCompletion/onError chỉ unregister, không recursive complete. Đã xử lý race khi servlet async error lifecycle kết thúc trước application close.
- Spring Security cho phép container ASYNC/ERROR redispatch, không cho phép bỏ authorization ở initial REQUEST. External caller không chọn được servlet dispatcher type bằng HTTP header/query.

### Evidence qua Worker thật

`pwsh -NoProfile -File tools/verify-operator-session.ps1` exit **0** sau bản sửa cuối:

1. Browser thật đăng nhập qua Pages Worker → Java → PostgreSQL.
2. Một kết nối HTTP SSE **độc lập với page** dùng session cookie và nhận connected comment.
3. Browser logout (204); kết nối độc lập nhận EOF trong deadline test, nên không nhầm với việc page reload tự hủy socket.
4. Public demo GET/overview tiếp tục 200; anonymous mutation vẫn 401.
5. Runner kiểm tra backend log không có ` ERROR `; kiểm tra lại log sau cleanup cũng không thấy ERROR. Các QA containers được dọn.

QA dùng heartbeat **250 ms** và deadline đọc 5 giây để chạy nhanh; production vẫn mặc định **15 giây**. Đây không phải phép đo latency production. Lượt đầu chỉ thấy EOF nhưng còn error log **không** được coi là pass cuối; harness hiện chặn trường hợp đó.

### Giới hạn còn lại

Revalidation là check-at-dispatch, không khóa DB xuyên socket write: revoke commit sau một check không rút lại byte đang gửi. Idle detection phụ thuộc scheduler, DB/network latency; không hứa hard 15-second SLA. Mỗi event/subscriber hoặc heartbeat có DB query; chưa benchmark large fanout.

Đợt này sửa **authorization lifecycle**, không sửa outbox mark-before-send, durable delivery, reconnect snapshot convergence hoặc multi-instance notification. Các hạng mục CORE-3/CORE-4 và những P0 nghiệp vụ khác trong kế hoạch vẫn còn; chưa đủ điều kiện gọi cả ba repo hoàn tất remediation.
