package dev.vcore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest(
        properties = {
            "vcore.security.bff-shared-secret=test-bff-secret",
            "vcore.security.demo-read-enabled=true",
            "vcore.outbox.poll-interval=60000",
            "vcore.sse.heartbeat-interval=60000",
            "springdoc.api-docs.enabled=true",
            "springdoc.swagger-ui.enabled=true"
        })
@AutoConfigureMockMvc
@Testcontainers
@Sql(scripts = {"/reset-test-data.sql", "/db/migration/V3__demo_tasks.sql"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class VCoreApplicationTest {
    @Test
    void aReadOnlyAccountKeepsItsAuthorizedStreamButCannotBorrowAnotherIdentity() throws Exception {
        var authorized = ownerRequest();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        String sessionId = jdbc.sql("SELECT session_id FROM spring_session WHERE principal_name=:username")
                .param("username", OWNER_EMAIL)
                .query(String.class)
                .single();
        jdbc.sql(
                        "INSERT INTO auth_account(username,password_hash,user_id,role) SELECT 'member@v-core.local',password_hash,'00000000-0000-0000-0000-000000000002','OPERATOR' FROM auth_account WHERE username=:username")
                .param("username", OWNER_EMAIL)
                .update();
        try {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> eventHub.subscribe(
                            java.util.UUID.fromString(WORKSPACE_ID),
                            new dev.vcore.activity.application.SseSubscriptionAccess.Identity(
                                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000002"),
                                    sessionId,
                                    false))))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            jdbc.sql("UPDATE auth_account SET role='VIEWER' WHERE username=:username")
                    .param("username", OWNER_EMAIL)
                    .update();
            var stream = mockMvc.perform(get("/api/workspaces/{workspace}/events", WORKSPACE_ID)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(authorized))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                            .asyncStarted())
                    .andReturn();
            eventHub.publish(new dev.vcore.activity.application.SseEventHub.OutboxEvent(
                    java.util.UUID.randomUUID(),
                    java.util.UUID.fromString(WORKSPACE_ID),
                    "task.created.v1",
                    "{\"marker\":\"read-only-account-can-read\"}"));
            assertThat(stream.getResponse().getContentAsString()).contains("read-only-account-can-read");
            jdbc.sql("DELETE FROM spring_session WHERE session_id=:id")
                    .param("id", sessionId)
                    .update();
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(eventHub, "heartbeat");
            assertThat(stream.getAsyncResult(1000)).isNull();
        } finally {
            jdbc.sql("UPDATE auth_account SET role='OPERATOR' WHERE username=:username")
                    .param("username", OWNER_EMAIL)
                    .update();
            jdbc.sql("DELETE FROM auth_account WHERE username='member@v-core.local'")
                    .update();
        }
    }

    @Test
    void anIdleStreamRevalidatesWithoutExtendingTheLoginSession() throws Exception {
        var stream = mockMvc.perform(get("/api/workspaces/{workspace}/events", WORKSPACE_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                        .asyncStarted())
                .andReturn();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        long before = jdbc.sql("SELECT last_access_time FROM spring_session WHERE principal_name=:username")
                .param("username", OWNER_EMAIL)
                .query(Long.class)
                .single();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(eventHub, "heartbeat");
        assertThat(stream.getResponse().getContentAsString()).contains("heartbeat");
        assertThat(jdbc.sql("SELECT last_access_time FROM spring_session WHERE principal_name=:username")
                        .param("username", OWNER_EMAIL)
                        .query(Long.class)
                        .single())
                .isEqualTo(before);
        jdbc.sql("UPDATE spring_session SET expiry_time=0,last_access_time=0").update();
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(eventHub, "heartbeat");
        assertThat(stream.getAsyncResult(1000)).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"logout", "expire", "disabled"})
    void openStreamsCloseBeforeSendingAfterSessionAccessIsRevoked(String action) throws Exception {
        var authorized = ownerRequest();
        var stream = mockMvc.perform(get("/api/workspaces/{workspace}/events", WORKSPACE_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(authorized))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                        .asyncStarted())
                .andReturn();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        try {
            switch (action) {
                case "logout" ->
                    mockMvc.perform(post("/api/auth/logout")
                                    .header("X-VCore-Bff-Key", BFF_SECRET)
                                    .with(authorized))
                            .andExpect(status().isNoContent());
                case "expire" ->
                    jdbc.sql("UPDATE spring_session SET expiry_time=0,last_access_time=0")
                            .update();
                case "disabled" ->
                    jdbc.sql("UPDATE auth_account SET enabled=FALSE WHERE username=:username")
                            .param("username", OWNER_EMAIL)
                            .update();
                default -> throw new IllegalArgumentException(action);
            }
            eventHub.publish(new dev.vcore.activity.application.SseEventHub.OutboxEvent(
                    java.util.UUID.randomUUID(),
                    java.util.UUID.fromString(WORKSPACE_ID),
                    "task.created.v1",
                    "{\"marker\":\"must-not-leak-after-session-revoke\"}"));
            assertThat(stream.getResponse().getContentAsString()).doesNotContain("must-not-leak-after-session-revoke");
            assertThat(stream.getAsyncResult(1000)).isNull();
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(stream))
                    .andExpect(status().isOk());
        } finally {
            jdbc.sql("UPDATE auth_account SET enabled=TRUE WHERE username=:username")
                    .param("username", OWNER_EMAIL)
                    .update();
        }
    }

    @Test
    void theExplicitPublicViewerCanSubscribeWithoutBeingLoggedIn() throws Exception {
        var stream = mockMvc.perform(
                        get("/api/workspaces/{workspace}/events", WORKSPACE_ID).header("X-VCore-Bff-Key", BFF_SECRET))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                        .asyncStarted())
                .andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("connected");
        eventHub.publish(new dev.vcore.activity.application.SseEventHub.OutboxEvent(
                java.util.UUID.randomUUID(),
                java.util.UUID.fromString(WORKSPACE_ID),
                "task.created.v1",
                "{\"marker\":\"public-demo-hint\"}"));
        assertThat(stream.getResponse().getContentAsString()).contains("public-demo-hint");
        // Terminate the test stream via the same access revalidation used in production.
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql(
                        "DELETE FROM workspace_membership WHERE workspace_id=CAST(:workspace AS uuid) AND user_id='00000000-0000-0000-0000-000000000003'")
                .param("workspace", WORKSPACE_ID)
                .update();
        try {
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(eventHub, "heartbeat");
            assertThat(stream.getAsyncResult(1000)).isNull();
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(stream))
                    .andExpect(status().isOk());
        } finally {
            jdbc.sql(
                            "INSERT INTO workspace_membership(workspace_id,user_id,role) VALUES(CAST(:workspace AS uuid),'00000000-0000-0000-0000-000000000003','VIEWER')")
                    .param("workspace", WORKSPACE_ID)
                    .update();
        }
    }

    @Autowired
    dev.vcore.activity.application.SseEventHub eventHub;

    @Test
    void anOpenStreamStopsBeforeSendingAnEventAfterMembershipRemoval() throws Exception {
        var stream = mockMvc.perform(get("/api/workspaces/{workspace}/events", WORKSPACE_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request()
                        .asyncStarted())
                .andReturn();
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql(
                        "DELETE FROM workspace_membership WHERE workspace_id=CAST(:workspace AS uuid) AND user_id='00000000-0000-0000-0000-000000000001'")
                .param("workspace", WORKSPACE_ID)
                .update();
        try {
            eventHub.publish(new dev.vcore.activity.application.SseEventHub.OutboxEvent(
                    java.util.UUID.randomUUID(),
                    java.util.UUID.fromString(WORKSPACE_ID),
                    "task.created.v1",
                    "{\"marker\":\"must-not-leak-after-revoke\"}"));
            assertThat(stream.getResponse().getContentAsString()).doesNotContain("must-not-leak-after-revoke");
            assertThat(stream.getAsyncResult(1000)).isNull();
        } finally {
            jdbc.sql(
                            "INSERT INTO workspace_membership(workspace_id,user_id,role) VALUES(CAST(:workspace AS uuid),'00000000-0000-0000-0000-000000000001','OWNER')")
                    .param("workspace", WORKSPACE_ID)
                    .update();
        }
    }

    @Test
    void publicDemoReadsDoNotInvalidateTheAnonymousCsrfSession() throws Exception {
        var bootstrap = mockMvc.perform(get("/api/auth/session").header("X-VCore-Bff-Key", BFF_SECRET))
                .andReturn()
                .getResponse();
        var cookie = bootstrap.getCookie("VCORE_SESSION");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var csrf =
                mapper.readTree(bootstrap.getContentAsString()).get("csrfToken").asText();
        mockMvc.perform(get("/api/session")
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .cookie(cookie))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/session")
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .cookie(cookie))
                .andExpect(jsonPath("$.authenticated").value(false))
                .andExpect(jsonPath("$.csrfToken").value(csrf));
    }

    @Autowired
    org.springframework.session.jdbc.JdbcIndexedSessionRepository sessionRepository;

    @Test
    void concurrentSessionAttributeInsertionDoesNotFailARequest() {
        verifyConcurrentSessionAttributes(sessionRepository);
    }

    private <S extends org.springframework.session.Session> void verifyConcurrentSessionAttributes(
            org.springframework.session.SessionRepository<S> repository) {
        var session = repository.createSession();
        repository.save(session);
        var requestOne = repository.findById(session.getId());
        var requestTwo = repository.findById(session.getId());
        requestOne.setAttribute("concurrent-csrf", "one");
        requestTwo.setAttribute("concurrent-csrf", "two");
        repository.save(requestOne);
        repository.save(requestTwo);
        assertThat((String) repository.findById(session.getId()).getAttribute("concurrent-csrf"))
                .isEqualTo("two");
    }

    @Test
    void removedMembershipCannotReadEvenWhenRedisCachesOwner() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        redisTemplate
                .opsForValue()
                .set("vcore:membership:" + WORKSPACE_ID + ":00000000-0000-0000-0000-000000000001", "OWNER");
        jdbc.sql(
                        "DELETE FROM workspace_membership WHERE workspace_id=CAST(:workspace AS uuid) AND user_id='00000000-0000-0000-0000-000000000001'")
                .param("workspace", WORKSPACE_ID)
                .update();
        try {
            mockMvc.perform(get("/api/workspaces/{workspace}/projects/{project}/tasks", WORKSPACE_ID, PROJECT_ID)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(ownerRequest()))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.sql(
                            "INSERT INTO workspace_membership(workspace_id,user_id,role) VALUES(CAST(:workspace AS uuid),'00000000-0000-0000-0000-000000000001','OWNER')")
                    .param("workspace", WORKSPACE_ID)
                    .update();
        }
    }

    @Test
    void expiredDisabledAndLoggedOutSessionsCannotWrite() throws Exception {
        var authorized = ownerRequest();
        mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", WORKSPACE_ID)
                        .cookie(ownerCookie)
                        .header("X-VCore-Bff-Key", BFF_SECRET))
                .andExpect(status().isForbidden());
        JdbcClient jdbc = JdbcClient.create(dataSource);
        jdbc.sql("UPDATE auth_account SET enabled=FALSE WHERE username=:name")
                .param("name", OWNER_EMAIL)
                .update();
        try {
            mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", WORKSPACE_ID)
                            .with(authorized)
                            .header("X-VCore-Bff-Key", BFF_SECRET))
                    .andExpect(status().isUnauthorized());
        } finally {
            jdbc.sql("UPDATE auth_account SET enabled=TRUE WHERE username=:name")
                    .param("name", OWNER_EMAIL)
                    .update();
        }
        ownerCookie = null;
        authorized = ownerRequest();
        jdbc.sql("UPDATE spring_session SET last_access_time=0,expiry_time=0").update();
        mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", WORKSPACE_ID)
                        .with(authorized)
                        .header("X-VCore-Bff-Key", BFF_SECRET))
                .andExpect(status().isUnauthorized());
        ownerCookie = null;
        authorized = ownerRequest();
        mockMvc.perform(post("/api/auth/logout").with(authorized).header("X-VCore-Bff-Key", BFF_SECRET))
                .andExpect(status().isNoContent());
        mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", WORKSPACE_ID)
                        .with(authorized)
                        .header("X-VCore-Bff-Key", BFF_SECRET))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void publicDemoIsAViewerRegardlessOfForgedOwnerHeaders() throws Exception {
        mockMvc.perform(get("/api/session")
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .header("X-VCore-User-Email", OWNER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("viewer@v-core.local"))
                .andExpect(jsonPath("$.workspaces[0].role").value("VIEWER"));
        mockMvc.perform(get("/api/auth/session").header("X-VCore-Bff-Key", BFF_SECRET))
                .andExpect(jsonPath("$.authenticated").value(false));
    }

    @Test
    void aTrustedBffCannotGrantWritesByClaimingAnOwnerEmail() throws Exception {
        mockMvc.perform(
                        post("/api/workspaces/{workspaceId}/projects", WORKSPACE_ID)
                                .header("X-VCore-Bff-Key", BFF_SECRET)
                                .header("X-VCore-User-Email", OWNER_EMAIL)
                                .header("Idempotency-Key", "forged-identity-project")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Spoofed project\",\"key\":\"SPOOF\",\"description\":\"must not write\",\"sprintName\":\"Test sprint\",\"sprintGoal\":\"No forged writes\"}"))
                .andExpect(status().isUnauthorized());
    }

    private static final String BFF_SECRET = "test-bff-secret";
    private static final String OWNER_EMAIL = "owner@v-core.local";
    private static final String WORKSPACE_ID = "00000000-0000-0000-0000-000000000100";
    private static final String PROJECT_ID = "00000000-0000-0000-0000-000000000200";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17.6-alpine");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.2-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.bootstrap.username", () -> OWNER_EMAIL);
        registry.add(
                "auth.bootstrap.password-hash",
                () -> new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder()
                        .encode("test-owner-password"));
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private jakarta.servlet.http.Cookie ownerCookie;

    private synchronized org.springframework.test.web.servlet.request.RequestPostProcessor ownerRequest()
            throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        if (ownerCookie == null) {
            var bootstrap = mockMvc.perform(get("/api/auth/session").header("X-VCore-Bff-Key", BFF_SECRET))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse();
            var login = mockMvc.perform(post("/api/auth/login")
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .cookie(bootstrap.getCookie("VCORE_SESSION"))
                            .header(
                                    "X-CSRF-TOKEN",
                                    mapper.readTree(bootstrap.getContentAsString())
                                            .get("csrfToken")
                                            .asText())
                            .param("username", OWNER_EMAIL)
                            .param("password", "test-owner-password"))
                    .andExpect(status().isNoContent())
                    .andReturn()
                    .getResponse();
            ownerCookie = login.getCookie("VCORE_SESSION");
            assertThat(ownerCookie).isNotNull();
            assertThat(ownerCookie.getValue())
                    .isNotEqualTo(bootstrap.getCookie("VCORE_SESSION").getValue());
        }
        var session = mockMvc.perform(get("/api/auth/session")
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .cookie(ownerCookie))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String csrf =
                mapper.readTree(session.getContentAsString()).get("csrfToken").asText();
        var cookie = ownerCookie;
        return request -> {
            request.setCookies(cookie);
            request.addHeader("X-CSRF-TOKEN", csrf);
            return request;
        };
    }

    @Test
    void startsWithPostgresMigrationAndExposesHealth() throws Exception {
        Integer migrationCount = JdbcClient.create(dataSource)
                .sql("select count(*) from flyway_schema_history where success")
                .query(Integer.class)
                .single();

        assertThat(migrationCount).isPositive();
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void publishesTheOpenApiContract() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("V-Core API"))
                .andExpect(jsonPath("$.info.version").value("v1"));
    }

    @Test
    void trustedBffIdentityCanReadItsSessionAndWorkspaces() throws Exception {
        mockMvc.perform(get("/api/session")
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value("owner@v-core.local"))
                .andExpect(jsonPath("$.user.name").value("Demo Owner"))
                .andExpect(jsonPath("$.workspaces[0].slug").value("v-core-product-lab"))
                .andExpect(jsonPath("$.workspaces[0].role").value("OWNER"));
    }

    @Test
    void rejectsAnIdentityFromAnUntrustedCaller() throws Exception {
        mockMvc.perform(get("/api/session")
                        .header("X-VCore-Bff-Key", "wrong-secret")
                        .header("X-VCore-User-Email", "owner@v-core.local"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
    }

    @Test
    void listsTheAuthenticatedMembersProjectTasks() throws Exception {
        mockMvc.perform(get("/api/workspaces/{workspaceId}/projects/{projectId}/tasks", WORKSPACE_ID, PROJECT_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].key").value("VC-104"))
                .andExpect(jsonPath("$.items[0].version").value(0));

        // Authorization does not populate a role cache: PostgreSQL is authoritative for reads too.
    }

    @Test
    void retriesATaskMoveWithoutDuplicatingTheMutationOrAudit() throws Exception {
        String taskId = "00000000-0000-0000-0000-000000000501";
        String requestBody = """
                {
                  "targetColumnId": "00000000-0000-0000-0000-000000000403",
                  "expectedVersion": 0,
                  "position": 2000
                }
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                                    "/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/moves",
                                    WORKSPACE_ID,
                                    PROJECT_ID,
                                    taskId)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(ownerRequest())
                            .header("Idempotency-Key", "move-task-501-to-review")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("review"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        JdbcClient client = JdbcClient.create(dataSource);
        Integer activityCount = client.sql("SELECT COUNT(*) FROM activity WHERE aggregate_id = CAST(:id AS uuid)")
                .param("id", taskId)
                .query(Integer.class)
                .single();
        Integer outboxCount = client.sql("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = CAST(:id AS uuid)")
                .param("id", taskId)
                .query(Integer.class)
                .single();

        assertThat(activityCount).isEqualTo(1);
        assertThat(outboxCount).isEqualTo(1);
        mockMvc.perform(get("/api/workspaces/{workspaceId}/activities", WORKSPACE_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("TASK_MOVED"))
                .andExpect(jsonPath("$.items[0].details.toColumnId").value("00000000-0000-0000-0000-000000000403"));
    }

    @Test
    void concurrentMovesCannotExceedTheTargetColumnsWipLimit() throws Exception {
        String targetColumnId = "00000000-0000-0000-0000-000000000402";
        CountDownLatch startGate = new CountDownLatch(1);

        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<MvcResult>> requests = List.of(
                    executor.submit(() -> moveAfterGate(
                            startGate, "00000000-0000-0000-0000-000000000504", targetColumnId, "concurrent-move-504")),
                    executor.submit(() -> moveAfterGate(
                            startGate, "00000000-0000-0000-0000-000000000505", targetColumnId, "concurrent-move-505")));

            startGate.countDown();
            List<Integer> statuses = requests.stream()
                    .map(future -> getUnchecked(future).getResponse().getStatus())
                    .sorted()
                    .toList();

            assertThat(statuses).containsExactly(200, 409);
        }

        Long taskCount = JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM task WHERE column_id = CAST(:columnId AS uuid)")
                .param("columnId", targetColumnId)
                .query(Long.class)
                .single();
        assertThat(taskCount).isEqualTo(3);
    }

    @Test
    void titleEditsAreIdempotentAndRejectAStaleVersion() throws Exception {
        String taskId = "00000000-0000-0000-0000-000000000503";
        String body = """
                {"title":"Close every long-session resource","expectedVersion":0}
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(patch(
                                    "/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}",
                                    WORKSPACE_ID,
                                    PROJECT_ID,
                                    taskId)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(ownerRequest())
                            .header("Idempotency-Key", "edit-task-503-title")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.title").value("Close every long-session resource"))
                    .andExpect(jsonPath("$.version").value(1));
        }

        mockMvc.perform(patch(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}",
                                WORKSPACE_ID,
                                PROJECT_ID,
                                taskId)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest())
                        .header("Idempotency-Key", "stale-edit-task-503-title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Overwrite a newer title","expectedVersion":0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Conflict"));
    }

    @Test
    void createsATaskOnceWhenTheClientRetries() throws Exception {
        String requestBody = """
                {
                  "title":"Prove retry-safe task creation",
                  "columnId":"00000000-0000-0000-0000-000000000401",
                  "priority":"high",
                  "points":5,
                  "tags":["reliability","api"]
                }
                """;

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/workspaces/{workspaceId}/projects/{projectId}/tasks", WORKSPACE_ID, PROJECT_ID)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(ownerRequest())
                            .header("Idempotency-Key", "create-retry-safe-task")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(requestBody))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.key").value("VC-152"))
                    .andExpect(jsonPath("$.title").value("Prove retry-safe task creation"))
                    .andExpect(jsonPath("$.version").value(0));
        }

        Integer createdCount = JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM task WHERE title = 'Prove retry-safe task creation'")
                .query(Integer.class)
                .single();
        assertThat(createdCount).isEqualTo(1);
    }

    @Test
    void workspaceOperationsAreTenantScopedAndInvitationRetriesAreSafe() throws Exception {
        mockMvc.perform(get("/api/workspaces/{workspaceId}/overview", WORKSPACE_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].key").value("VC"))
                .andExpect(jsonPath("$.projects[0].activeSprint.name").value("Sprint 24"))
                .andExpect(jsonPath("$.members.length()").value(3));

        String invitation = """
                {"email":"engineer@example.jp","role":"MEMBER"}
                """;
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/workspaces/{workspaceId}/invitations", WORKSPACE_ID)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(ownerRequest())
                            .header("Idempotency-Key", "invite-engineer-example-jp")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(invitation))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.email").value("engineer@example.jp"))
                    .andExpect(jsonPath("$.status").value("PENDING"));
        }

        Integer invitationCount = JdbcClient.create(dataSource)
                .sql("SELECT COUNT(*) FROM workspace_invitation WHERE email = 'engineer@example.jp'")
                .query(Integer.class)
                .single();
        assertThat(invitationCount).isEqualTo(1);
    }

    @Test
    void taskCollaborationAssignsMembersAndRetriesCommentsSafely() throws Exception {
        String taskId = "00000000-0000-0000-0000-000000000501";
        mockMvc.perform(patch(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/assignee",
                                WORKSPACE_ID,
                                PROJECT_ID,
                                taskId)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest())
                        .header("Idempotency-Key", "assign-task-501-mai")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"assigneeId":"00000000-0000-0000-0000-000000000002","expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assignee.name").value("Mai Tran"))
                .andExpect(jsonPath("$.version").value(1));

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post(
                                    "/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/comments",
                                    WORKSPACE_ID,
                                    PROJECT_ID,
                                    taskId)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(ownerRequest())
                            .header("Idempotency-Key", "comment-task-501-proof")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"body":"Validated with the customer acceptance checklist."}
                                    """))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.author").value("Demo Owner"));
        }

        mockMvc.perform(get(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/comments",
                                WORKSPACE_ID,
                                PROJECT_ID,
                                taskId)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void ownerCanCreateAProjectWithAnActiveSprintAndTuneWipUsingVersions() throws Exception {
        String projectResponse = mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", WORKSPACE_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest())
                        .header("Idempotency-Key", "create-project-evidence")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name":"Evidence Delivery",
                                  "key":"EVD",
                                  "description":"Publish verifiable engineering claims",
                                  "sprintName":"Evidence Sprint",
                                  "sprintGoal":"Turn tests into recruiter-facing proof"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.key").value("EVD"))
                .andExpect(jsonPath("$.activeSprint.status").value("ACTIVE"))
                .andExpect(jsonPath("$.columns.length()").value(4))
                .andReturn()
                .getResponse()
                .getContentAsString();

        com.fasterxml.jackson.databind.JsonNode project =
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(projectResponse);
        String projectId = project.get("id").asText();
        String inProgressColumnId = project.get("columns").get(1).get("id").asText();

        mockMvc.perform(patch(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/workflow-columns/{columnId}",
                                WORKSPACE_ID,
                                projectId,
                                inProgressColumnId)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest())
                        .header("Idempotency-Key", "set-evidence-wip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wipLimit":4,"expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wipLimit").value(4))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void rejectsAWipLimitBelowTheCurrentOccupancy() throws Exception {
        mockMvc.perform(patch(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/workflow-columns/{columnId}",
                                WORKSPACE_ID,
                                PROJECT_ID,
                                "00000000-0000-0000-0000-000000000402")
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest())
                        .header("Idempotency-Key", "reject-limit-below-occupancy")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"wipLimit\":1,\"expectedVersion\":0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void mutationRejectsARevokedRoleEvenWhenRedisStillCachesOwner() throws Exception {
        JdbcClient jdbc = JdbcClient.create(dataSource);
        redisTemplate
                .opsForValue()
                .set("vcore:membership:" + WORKSPACE_ID + ":00000000-0000-0000-0000-000000000001", "OWNER");
        jdbc.sql(
                        "UPDATE workspace_membership SET role='VIEWER' WHERE workspace_id=CAST(:workspace AS uuid) AND user_id='00000000-0000-0000-0000-000000000001'")
                .param("workspace", WORKSPACE_ID)
                .update();
        try {
            mockMvc.perform(patch(
                                    "/api/workspaces/{workspace}/projects/{project}/tasks/{task}",
                                    WORKSPACE_ID,
                                    PROJECT_ID,
                                    "00000000-0000-0000-0000-000000000503")
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .with(ownerRequest())
                            .header("Idempotency-Key", "revoked-owner")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"title\":\"Forbidden edit\",\"expectedVersion\":0}"))
                    .andExpect(status().isForbidden());
        } finally {
            jdbc.sql(
                            "UPDATE workspace_membership SET role='OWNER' WHERE workspace_id=CAST(:workspace AS uuid) AND user_id='00000000-0000-0000-0000-000000000001'")
                    .param("workspace", WORKSPACE_ID)
                    .update();
        }
    }

    private MvcResult moveAfterGate(
            CountDownLatch startGate, String taskId, String targetColumnId, String idempotencyKey) throws Exception {
        startGate.await();
        return mockMvc.perform(post(
                                "/api/workspaces/{workspaceId}/projects/{projectId}/tasks/{taskId}/moves",
                                WORKSPACE_ID,
                                PROJECT_ID,
                                taskId)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .with(ownerRequest())
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "targetColumnId": "%s",
                                  "expectedVersion": 0,
                                  "position": 3000
                                }
                                """.formatted(targetColumnId)))
                .andReturn();
    }

    private MvcResult getUnchecked(Future<MvcResult> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("Concurrent request did not complete.", exception);
        }
    }
}
