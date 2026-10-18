# V-Core — Java Backend & FDE Interview Prep

Snapshot: **2026-10-17**, local commit `5182e8d`. Phạm vi Intern/Fresher/Junior; ôn P0 của **một** project trong 60–90 phút, không học cả ba cùng lúc.

**Cách đọc bằng chứng:** `Confirmed in code` = đã đọc implementation/test; `Inferred` = suy luận có giải thích; `Not verified` = chưa xác minh. Test được trích dẫn là **test có trong repo**, không phải kết quả chạy lại ở lần phân tích này. Không gọi live service, không đọc `.env`. File path dưới đây tính từ gốc repo; số dòng theo snapshot này.

**Đóng góp cá nhân: Not verified.** Repo không chứng minh bạn tự viết hoặc tự quyết định tất cả. Đáp án dùng “the implementation/the project”; chỉ đổi sang “I implemented” cho phần bạn thực sự làm và có thể giải thích. Nếu dùng AI/code có sẵn, nêu đúng phạm vi hỗ trợ khi được hỏi.

## 1. Project Overview

- **Confirmed in code:** Workspace Kanban với role, task create/rename/move, WIP limits, audit và sự kiện SSE [C1–C8].
- **Inferred:** Người dùng mục tiêu là nhóm làm việc cần tránh ghi đè thay đổi và vượt giới hạn công việc khi nhiều người thao tác.
- Frontend Next.js/React/TypeScript; Pages Worker làm BFF, Java 21/Spring Boot là backend [C9, C12].
- Backend là một ứng dụng chia package theo chức năng; không có service độc lập cho mỗi module [C12].
- PostgreSQL lưu workspace, membership, project, column, task, idempotency, activity/outbox [C6].
- JPA cho task/column, `JdbcClient` cho membership và event/idempotency; không phải toàn bộ dùng ORM [C2–C6].
- Redis cache role 5 phút, lỗi cache thì truy vấn DB [C5].
- BFF truyền identity cho Spring; demo có fallback identity, không phải hệ thống đăng nhập SaaS hoàn chỉnh [C9].
- Không thấy tích hợp thanh toán/email trong các flow đã kiểm tra. Cloudflare là BFF; PostgreSQL/Redis là dịch vụ dữ liệu.
- **Not verified:** hosting hiện tại, production load, lịch sử sự cố, người dùng thật, hiệu quả định lượng. File deploy/CI chỉ chứng minh cấu hình tồn tại [C12].

## 2. Architecture & Critical Flows

### Bản đồ code cần mở

| ID | File / vị trí | Trách nhiệm |
| --- | --- | --- |
| C1 | [TaskController.java:82](backend/src/main/java/dev/vcore/task/web/TaskController.java#L82), DTO:116 | HTTP move, validation, idempotency header |
| C2 | [TaskMoveService.java:39](backend/src/main/java/dev/vcore/task/application/TaskMoveService.java#L39) | `move`, transaction, version/WIP, ghi event |
| C3 | [WorkflowColumnRepository.java:14](backend/src/main/java/dev/vcore/task/infrastructure/WorkflowColumnRepository.java#L14); [TaskRepository.java:8](backend/src/main/java/dev/vcore/task/infrastructure/TaskRepository.java#L8); [TaskEntity.java:33](backend/src/main/java/dev/vcore/task/domain/TaskEntity.java#L33) | Lock column; tenant-scoped query; `@Version` |
| C4 | [IdempotencyService.java:28](backend/src/main/java/dev/vcore/task/application/IdempotencyService.java#L28), `complete`:77 | Claim key, so hash, lưu response |
| C5 | [WorkspaceAccessService.java:27](backend/src/main/java/dev/vcore/workspace/application/WorkspaceAccessService.java#L27), `resolveRole`:50 | Membership, mutation/admin role, Redis fallback |
| C6 | [V1__baseline.sql:20](backend/src/main/resources/db/migration/V1__baseline.sql#L20), task:77, indexes:101, outbox:119, idempotency:135 | Schema, FK, index, unique key |
| C7 | [TaskEventRecorder.java:24](backend/src/main/java/dev/vcore/task/application/TaskEventRecorder.java#L24); [OutboxClaimService.java:22](backend/src/main/java/dev/vcore/activity/application/OutboxClaimService.java#L22); [OutboxPublisher.java:22](backend/src/main/java/dev/vcore/activity/application/OutboxPublisher.java#L22) | Audit/outbox write và poll |
| C8 | [SseEventHub.java:18](backend/src/main/java/dev/vcore/activity/application/SseEventHub.java#L18); [ActivityController.java:42](backend/src/main/java/dev/vcore/activity/web/ActivityController.java#L42); [use-workspace-events.ts:22](hooks/use-workspace-events.ts#L22) | Subscribe, local emitters, invalidation |
| C9 | [public/_worker.js:30](public/_worker.js#L30); [BffAuthenticationFilter.java:33](backend/src/main/java/dev/vcore/identity/security/BffAuthenticationFilter.java#L33) | Demo/Access identity, shared-secret trust |
| C10 | [VCoreApplicationTest.java:134](backend/src/test/java/dev/vcore/VCoreApplicationTest.java#L134), concurrency:181, stale edit:210, tenant:281 | Test evidence |
| C11 | [ApiExceptionHandler.java:14](backend/src/main/java/dev/vcore/shared/web/ApiExceptionHandler.java#L14); [TaskEditService.java:34](backend/src/main/java/dev/vcore/task/application/TaskEditService.java#L34); [TaskCreateService.java:47](backend/src/main/java/dev/vcore/task/application/TaskCreateService.java#L47) | Error mapping; rename/create |
| C12 | [backend/pom.xml:7](backend/pom.xml#L7); [VCoreApplication.java:8](backend/src/main/java/dev/vcore/VCoreApplication.java#L8); [application.yml:20](backend/src/main/resources/application.yml#L20); [Dockerfile:1](backend/Dockerfile#L1); [ci.yml:33](.github/workflows/ci.yml#L33); [package.json](package.json) | Build, entry, Flyway/JPA config, Docker/CI |

### Flow A — Di chuyển task vào column gần đầy

`POST /api/workspaces/{w}/projects/{p}/tasks/{t}/moves` → Worker inject identity [C9] → controller validate UUID/version/position/key [C1] → `TaskMoveService.move` [C2] → role check [C5] → idempotency claim/replay [C4] → lock **target column** [C3] → đọc task theo workspace/project → check version → count target tasks → check WIP → `saveAndFlush` → activity + outbox → lưu response → commit → HTTP 200.

Ví dụ WIP=3, hiện có 2: A và B cùng đưa hai task khác nhau vào. A giữ lock target, thêm task và commit; B sau khi lấy lock đếm lại thấy 3 nên nhận 409. Đây là điều test tại C10:181 kiểm tra. UUID không tồn tại → 404; thiếu quyền → 403; stale version/full WIP → 409 [C11]. Di chuyển trong cùng column không tăng WIP nên bỏ bước count [C2:66]. Không suy rộng test này thành mọi lịch chạy concurrent đều đã được kiểm chứng.

### Flow B — Rename và gửi lại sau khi mất response

`PATCH .../tasks/{t}` → `TaskEditService.rename` [C11:34] → authorize → hash project/task/title/version → `beginOrReplay` [C4] → nếu đã hoàn tất thì trả response cũ → nếu mới, kiểm tra version và đổi tên → flush JPA, ghi activity/outbox và response trong transaction. Test gửi hai lần cùng key chỉ tăng version một lần [C10:210]. Cùng key nhưng payload khác → 409. Key mới với version cũ → 409. Idempotency không thay thế optimistic locking.

### Flow C — Activity cập nhật trên trình duyệt

Mutation commit activity/outbox [C7] → scheduler poll → `claimBatch` lock `SKIP LOCKED`, đánh dấu published trong transaction → publisher gửi cho emitters **trong process hiện tại** [C8] → frontend nhận event và invalidate tasks/activities → GET lại dữ liệu. SSE chỉ báo cần refresh; DB vẫn là nguồn dữ liệu.

**Giới hạn đã xác nhận từ thứ tự code:** outbox được đánh dấu trước khi gửi SSE; crash ở giữa có thể bỏ lỡ notification. Không có bảo đảm giao exactly-once/at-least-once tới browser. Reconnect của hook tạo kết nối mới nhưng không có explicit replay event đã lỡ [C7–C8].

## 3. P0 Interview Q&A

### P0.1 — What problem does V-Core solve, and what was your contribution?

**Vietnamese explanation:** Vấn đề chính là nhiều người thao tác trên cùng board: vượt WIP, sửa dữ liệu cũ, retry gây lặp. Nói rõ phạm vi bản demo và phần bản thân đã làm; chưa xác minh tác giả thì không nhận toàn bộ công lao.

**English sample answer:** “V-Core is a team Kanban workspace. Its backend handles workspace access, concurrent task changes, and safe request retries. The main example is keeping a column within its WIP limit. I can walk through that flow in the code; my personal contribution should be limited to the parts I actually worked on.”

**Code evidence:** C1–C6. **Possible follow-up:** “Which part did you personally debug?” **What I must understand:** business invariant là điều kiện dữ liệu phải luôn giữ đúng; chuẩn bị một đóng góp có thật, không bịa incident.

### P0.2 — Walk me through a task move from the browser to the database.

**Vietnamese explanation:** Kể Flow A theo thứ tự, đặc biệt quyền → replay → lock → check → write. `@Valid` kiểm tra hình dạng request; service kiểm tra quy tắc cần dữ liệu DB. Constructor injection cho service nhận repository/access/idempotency thay vì tự tạo chúng.

**English sample answer:** “The browser sends a move request through the BFF. The controller validates it and calls a transactional service. The service checks access, handles the idempotency key, and locks the target column. It then checks the task version and WIP limit before saving the change and audit records.”

**Code evidence:** C1:82–119; C2:39–99; C9. **Possible follow-up:** “What gets rolled back if audit writing fails?” **What I must understand:** transaction bao quanh các ghi DB; runtime exception làm transaction rollback theo Spring mặc định. Không có HTTP call downstream trong move.

### P0.3 — Why do you need both a column lock and a task version?

**Vietnamese explanation:** Lock column bảo vệ quy tắc trên **nhiều task**: count rồi thêm. Version bảo vệ **một task** khỏi stale update. Hai task khác nhau có version độc lập, nên `@Version` trên task không ngăn vượt WIP. Ngược lại, hai move một task sang hai column khác nhau không chia sẻ target lock; version vẫn cần thiết.

**English sample answer:** “The column lock protects the WIP rule across multiple tasks. The task version protects one task from stale updates. Optimistic locking alone would not stop two different tasks from filling the last slot. The trade-off is that moves into the same column must wait for each other.”

**Code evidence:** C2:54–78; C3:14 / `TaskEntity`:33; C10:181. **Possible follow-up:** “Why not lock only the task?” **What I must understand:** count-then-write race, row-lock lifetime đến commit, stale version versus aggregate invariant; Java `synchronized` không bảo vệ nhiều backend instance.

### P0.4 — How does request idempotency work here?

**Vietnamese explanation:** `(workspace_id,key)` là unique/primary key. Insert `ON CONFLICT DO NOTHING`; nếu key tồn tại, so hash và đọc response cũ. Record, mutation và audit nằm trong cùng transaction của caller. Retry phải giữ nguyên key và payload; thao tác mới cần key mới. Hash kiểm tra request giống nhau, không phải chữ ký xác thực.

**English sample answer:** “The database stores a request key for each workspace. A repeated key with the same request hash returns the stored response. Reusing the key for different data produces a conflict. The mutation and its stored response share one transaction, so a normal retry does not add another audit event.”

**Code evidence:** C4:28–95; C6:135; C10:134. **Possible follow-up:** “Does the key expire after 24 hours?” **What I must understand:** code ghi `expires_at` nhưng lookup không lọc expiry, và không tìm thấy cleanup trong backend đã rà. Không nói TTL thực tế là 24 giờ.

### P0.5 — How do you keep one workspace from accessing another?

**Vietnamese explanation:** Có hai lớp: identity đáng tin từ BFF, rồi membership/role trong workspace. Task query có cả task ID, workspace ID và project ID; schema có composite FK theo workspace. UUID khó đoán không phải authorization. Redis role có TTL nên quyền có thể cũ trong khoảng cache còn sống.

**English sample answer:** “The backend first resolves an identity from a trusted BFF request. It then checks membership and the role for the requested workspace. Task queries include both workspace and project identifiers. Database foreign keys add another layer of consistency, but they do not replace permission checks.”

**Code evidence:** C9; C5:27–67; C3 task repository:10; C6:94–98; C10:281. **Possible follow-up:** “Is the public demo a real user login system?” **What I must understand:** BFF trust ≠ end-user authentication; Worker có demo owner fallback, Cloudflare Access enforcement phía platform chưa xác minh.

### P0.6 — Why use Redis, and what happens when it fails?

**Vietnamese explanation:** Redis chỉ cache role trong flow này. Cache miss/lỗi → SQL membership lookup; cache write lỗi không hủy request. PostgreSQL giữ dữ liệu gốc nhưng authorization có thể dùng role cũ từ Redis; availability và freshness là hai vấn đề khác nhau.

**English sample answer:** “Redis caches workspace roles for five minutes. If the cache is unavailable, the service reads membership from PostgreSQL. This keeps requests working during a cache outage. The trade-off is that a cached role may be stale, so immediate permission revocation would need explicit invalidation.”

**Code evidence:** C5:17,50–91. **Possible follow-up:** “Would you cache authorization data in a sensitive system?” **What I must understand:** TTL, cache-aside, revocation window; không nói Redis là distributed lock trong dự án này.

### P0.7 — Are the audit log and live activity feed equally reliable?

**Vietnamese explanation:** Không. Audit/outbox write đi cùng mutation, nên giữ bằng chứng trong DB khi transaction commit. SSE là gửi tới kết nối đang mở, lưu subscriber trong RAM. `published_at` được ghi trước send, nên delivery browser không có tính bền vững tương đương DB. Với hai instance, event bị claim bởi instance không giữ kết nối của người dùng có thể không tới người đó.

**English sample answer:** “The database change and audit records are written together. The live feed is weaker because its subscribers live in application memory. The current publisher marks an event before sending it, so a crash can lose a notification. I would treat SSE as a refresh hint and add a replay or shared delivery mechanism if stronger guarantees were required.”

**Code evidence:** C7 claim:23–51, publisher:25; C8 hub:16–41, hook:22–30. **Possible follow-up:** “How would this work with two replicas?” **What I must understand:** durable write versus delivery acknowledgement; đây là hạn chế suy ra từ code, chưa tái hiện bằng runtime test.

### P0.8 — How do you know concurrent moves and retries behave correctly?

**Vietnamese explanation:** Testcontainers PostgreSQL/Redis + Spring context/MockMvc chạy đường HTTP backend, không thay DB bằng mock. Concurrent test dùng start gate cho hai move, assert 200/409 và final count=3. Retry test assert activity/outbox chỉ một record. Đây là correctness ở các scenario được dựng, không phải production benchmark hay chứng minh toàn diện.

**English sample answer:** “The repository has integration tests using real PostgreSQL and Redis containers. One test starts two moves together and expects one success and one conflict. It also checks the final column size. Another test repeats the same request and checks that audit and outbox records are not duplicated.”

**Code evidence:** C10:44–60,134–206. **Possible follow-up:** “What race conditions are still untested?” **What I must understand:** assertions, reset data, coordinated concurrency; đọc thêm tests same-task/different-target, crash during publication hoặc role revocation trước khi khẳng định đã phủ chúng.

### P0.9 — A user gets a conflict or sees stale data. How would you debug it?

**Vietnamese explanation:** Đây là tình huống, không phải incident đã xảy ra. Kiểm tra status/detail và request ID, xác định WIP hay stale version hay key reuse; xem request body và dữ liệu DB, tải lại board. Không retry mù với key mới. Nếu DB đúng nhưng UI cũ thì kiểm tra SSE/invalidation/refetch. Không log shared secret.

**English sample answer:** “I would first inspect the response and request ID. A conflict may mean a full column, an old task version, or an idempotency-key mismatch. I would compare the request with the current database state before retrying. If the database is correct but the screen is stale, I would inspect event delivery and query refresh.”

**Code evidence:** C2:61–72; C4:63–67; C11:14–31; C8; [use-task-cloud-sync.ts:25](hooks/use-task-cloud-sync.ts#L25). **Possible follow-up:** “What if the backend is down but tasks still appear?” **What I must understand:** UI có local-cache/offline state; UI hiện dữ liệu không tự chứng minh backend đã lưu mutation.

## 4. P1 Follow-up Questions

| Question | Ý chính để trả lời | Code |
| --- | --- | --- |
| Why not use serializable isolation everywhere? | Lock target là phạm vi hẹp; serializable có chi phí/serialization retry. Đây là alternative, không phải cấu hình đã triển khai. | C2–C3 |
| What if two users edit the same task at once? | Cùng đọc version có thể cùng qua check Java; `@Version` phát hiện khi flush. Explicit check một mình chưa đủ. | C3 entity:33; C11 rename:54–62 |
| Why mix JDBC and JPA? | Code dùng cả hai; JDBC dễ thể hiện insert/upsert/event SQL, JPA quản lý entity/version. Lý do tác giả chọn: chưa xác minh. | C2–C4,C7 |
| What prevents duplicate task numbers? | Create lock project trước `MAX+1`; schema unique `(workspace,project,task_number)`. Đổi mọi write path phải giữ protocol này. | C11 create:65–99; C6:97 |
| Does the board index make every query fast? | Index board khớp prefix/filter/order; cần execution plan và dataset để đánh giá tốc độ, không có benchmark mới. | C6:101–103 |
| What happens when the transaction fails after changing the entity? | Task/audit/idempotency phải rollback cùng transaction; Redis cache không thuộc PostgreSQL transaction. | C2:39–99; C4,C7 |
| Does the current SSE client catch up after reconnecting? | Reconnect có, explicit missed-event replay không thấy; cần refresh/read DB khi reconnect hoặc cursor replay. | C8 hook:22–30 |

## 5. FDE Solution Questions

Các câu dưới đây là **tình huống đề xuất**, không phải kinh nghiệm khách hàng đã xác minh.

| Question | Ý chính / câu hỏi cần làm rõ | Code nền |
| --- | --- | --- |
| A customer asks for a different WIP policy per team. What would you clarify? | Giới hạn theo column/project/người? Có tính blocked task? Khi giảm dưới số hiện có thì cấm hay grandfather? Chốt acceptance scenario trước sửa. | C2:66–74; `WorkspaceOperationsService.java:166–220` |
| How would you demonstrate an MVP to a nontechnical team lead? | Một workspace, hai role, last-slot race, retry không lặp audit. Dùng kết quả kiểm chứng thay vì hứa năng suất tăng bao nhiêu %. | C5,C10 |
| A customer requires immediate access revocation. Is the current design sufficient? | Cache role 5 phút chưa đáp ứng; đề xuất invalidate hoặc kiểm tra DB cho action nhạy cảm. Cần xác định thời hạn thu hồi và SLA. | C5 |
| The customer has two backend replicas and misses updates. What would you investigate? | Local SSE hub + cross-instance outbox claim; đề xuất shared fanout/cursor replay và browser refetch. Không khẳng định đã có broker. | C7–C8 |

## 6. Interview Risks & Knowledge Gaps

Đối chiếu **hai bullet V-Core trong `D:/CV/main.tex:189–190`**; file CV được đọc làm nguồn claim, chưa sửa.

| Interviewer có thể hỏi | Code chứng minh gì / mức xác minh | Cần học / trả lời chính xác |
| --- | --- | --- |
| “Board limits remained consistent” trong CV nghĩa gì? | **Confirmed:** C10:181 kiểm tra hai move vào một target. Không phải bằng chứng mọi flow/WIP policy. | Học race/lock/version; nói “the tested concurrent-move scenario”, chưa nói quy tắc WIP đã được kiểm chứng toàn diện. |
| “Idempotent updates and audit history” bảo đảm gì? | **Confirmed:** rename/move + audit cùng transaction; retry tests C10. | Giải thích key phải được reuse; `expires_at` chưa được enforce/cleanup trong code đã rà C4. Không gọi đó là exactly-once của mọi hệ thống. |
| “Transactional outbox” có đảm bảo browser nhận event? | **Confirmed limitation:** mark published trước send, local subscriber [C7–C8]. **Inferred failure:** crash hoặc nhiều replica có thể mất notification. | Học acknowledgement/fanout/replay; nói durable DB writes nhưng best-effort live notifications. |
| “Multi-tenant SaaS” có login thật chưa? | **Confirmed:** workspace filtering, role check; Worker fallback demo owner [C5,C9]. **Not verified:** Access platform config. | Phân biệt tenant isolation trong API với identity lifecycle. Chưa chứng minh production authentication hoàn chỉnh. |
| Role bị thu hồi có áp dụng ngay? | **Confirmed:** đọc role cache trước DB, TTL 5 phút [C5]. | Stale authorization là rủi ro khi thay đổi membership; chưa chứng minh có incident. Đề xuất invalidation. |
| Redis/JPA/Spring Security là tự xây? | **Confirmed:** framework handles DI/JPA version/filter chain; code tự viết policy orchestration [C2–C5,C9]. | Không nhận tự viết lock engine, ORM hoặc auth framework. Lý do lịch sử chọn framework chưa xác minh. |
| CI/Docker có chứng minh deployment đang khỏe? | **Confirmed:** config build/test và non-root image [C12]. **Not verified:** CI/live hiện tại. | Đọc test command/Docker stages; không nói đã chạy lại hoặc đạt production SLA trong lần phân tích này. |

## 7. English Project Presentation

### A. 60 seconds

“V-Core is a Kanban workspace for team collaboration. The main backend problem is keeping shared board data correct when users act at the same time or retry a request. It uses Java 21, Spring Boot, PostgreSQL, and Redis, with a Next.js frontend and a Cloudflare BFF.

A task move checks workspace access, the task version, and the target column's WIP limit. It uses a database lock for the column and stores an idempotency response with the mutation. Audit records explain what changed. Integration tests cover concurrent moves and repeated requests. A current limitation is that live SSE notifications can be missed, even though the committed task remains in PostgreSQL.”

**Contribution sentence — chỉ thêm sau khi xác nhận:** “My contribution was [actual component], where I [actual change] and verified it with [actual test].” Chưa có dữ liệu để điền thay bạn.

### B. Technical deep dive — khoảng 3 phút khi có pause và chỉ code

“The application separates the browser, the edge BFF, and a Spring Boot backend. The BFF forwards the request with a trusted identity header. The backend still checks workspace membership and role before accessing data. The demo identity fallback is a simplification, so I would distinguish it from a complete production login system.

The flow I want to explain is a task move. The controller validates the request shape, including the target column and expected version. The service starts a transaction and checks whether the idempotency key already has a response. If it does, it returns that response instead of repeating the mutation.

For a new request, the service locks the target column. Suppose that column has one free slot and two different tasks arrive together. Without a common lock, both requests could count the same free slot. With the lock, one request completes first. The second request checks the count after acquiring the lock and gets a conflict if the column is now full.

The task also has an optimistic version. This solves a different problem: one user may be editing a stale copy of the same task. The explicit expected-version check catches an already stale request, and JPA's version check protects the write at flush time. The implementation then writes activity, outbox data, and the idempotency response in the transaction.

Redis is used for membership caching, not for the correctness lock. If Redis fails, membership can still be read from PostgreSQL. A trade-off is that a cached role can remain stale for five minutes.

The integration tests use PostgreSQL and Redis containers. They check a last-slot race and that retrying does not duplicate audit records. These are specific correctness checks, not a production performance claim. The main improvement I would discuss is event delivery: subscribers live in one process and events are marked before sending. Stronger delivery would require replay or shared fanout and tests for crash and multi-instance scenarios.”

## 8. Technical English Vocabulary

| Phrase | Nghĩa | Interview sentence |
| --- | --- | --- |
| preserve an invariant | giữ điều kiện dữ liệu luôn đúng | “The lock preserves the WIP invariant.” |
| stale update | cập nhật từ bản dữ liệu cũ | “The version check rejects a stale update.” |
| pessimistic row lock | khóa hàng trước khi thay đổi | “The target column uses a pessimistic row lock.” |
| optimistic version | phiên bản kiểm tra xung đột khi ghi | “Each task has an optimistic version.” |
| lock contention | nhiều request tranh cùng khóa | “A busy column can cause lock contention.” |
| transaction boundary | phạm vi commit/rollback | “The service defines the transaction boundary.” |
| request fingerprint | hash nhận diện nội dung request | “The fingerprint detects key reuse with different data.” |
| tenant-scoped query | truy vấn giới hạn trong tenant | “The lookup uses a tenant-scoped query.” |
| permission revocation | thu hồi quyền | “Caching can delay permission revocation.” |
| durable audit record | lịch sử bền vững trong DB | “The mutation includes a durable audit record.” |
| best-effort notification | thông báo có thể bỏ lỡ | “The current SSE feed is a best-effort notification.” |
| missed-event replay | phát lại sự kiện đã bỏ lỡ | “The client does not implement missed-event replay.” |

## 9. Final Study Checklist

- [ ] 0–10 phút: đọc controller + move service, tự kể Flow A không nhìn đáp án.
- [ ] 10–30 phút: vẽ hai request tranh last slot; phân biệt lock column và version task (P0.3).
- [ ] 30–45 phút: lần theo key/hash/response/rollback; đọc test retry (P0.4).
- [ ] 45–60 phút: role/cache/demo identity và giới hạn SSE (P0.5–7).
- [ ] 60–75 phút: đọc assertions concurrency và retry; tự giải thích vì sao cần DB thật (P0.8).
- [ ] 75–90 phút: luyện intro, deep dive, một câu debugging và contribution có thật.
- [ ] Tự trả lời: DB đúng nhưng UI cũ có thể do gì? Thay một dòng lock/version sẽ phá điều gì?
- [ ] Chưa ưu tiên AWS/Kubernetes/Terraform; chỉ biết Docker image, DB migration và CI chạy gì [C12].

| Priority | Topic | Why it matters | Source code | Estimated study effort |
| --- | --- | --- | --- | --- |
| P0 | Move + WIP lock + version | Câu chuyện backend chính, dễ bị hỏi sâu | C1–C3,C10:181 | 25 phút |
| P0 | Idempotency + rollback + audit | Giải thích chính xác bullet CV thứ hai | C4,C7,C10:134 | 15 phút |
| P0 | Workspace role + demo trust | Không nhầm demo identity với auth hoàn chỉnh | C5,C9 | 10 phút |
| P0 | SSE delivery limits | Tránh claim quá khả năng outbox | C7–C8 | 10 phút |
| P0 | Tests + honest contribution + speaking | Nối code, evidence và câu trả lời | C10; mục 7 | 20 phút |
| P1 | Indexes, create numbering, Docker/CI | Follow-up khi còn thời gian | C6,C11–C12 | 20–30 phút thêm |
