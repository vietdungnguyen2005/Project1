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
        registry.add("spring.data.redis.url", () -> "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(6379));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private StringRedisTemplate redisTemplate;

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
                        .header("X-VCore-User-Email", OWNER_EMAIL))
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
                        .header("X-VCore-User-Email", OWNER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(5))
                .andExpect(jsonPath("$.items[0].key").value("VC-104"))
                .andExpect(jsonPath("$.items[0].version").value(0));

        String cacheKey = "vcore:membership:" + WORKSPACE_ID + ":00000000-0000-0000-0000-000000000001";
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isEqualTo("OWNER");
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
                            .header("X-VCore-User-Email", OWNER_EMAIL)
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
                        .header("X-VCore-User-Email", OWNER_EMAIL))
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
                            .header("X-VCore-User-Email", OWNER_EMAIL)
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
                        .header("X-VCore-User-Email", OWNER_EMAIL)
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
                            .header("X-VCore-User-Email", OWNER_EMAIL)
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
                        .header("X-VCore-User-Email", OWNER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].key").value("VC"))
                .andExpect(jsonPath("$.projects[0].activeSprint.name").value("Sprint 24"))
                .andExpect(jsonPath("$.members.length()").value(2));

        String invitation = """
                {"email":"engineer@example.jp","role":"MEMBER"}
                """;
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/workspaces/{workspaceId}/invitations", WORKSPACE_ID)
                            .header("X-VCore-Bff-Key", BFF_SECRET)
                            .header("X-VCore-User-Email", OWNER_EMAIL)
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
                        .header("X-VCore-User-Email", OWNER_EMAIL)
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
                            .header("X-VCore-User-Email", OWNER_EMAIL)
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
                        .header("X-VCore-User-Email", OWNER_EMAIL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void ownerCanCreateAProjectWithAnActiveSprintAndTuneWipUsingVersions() throws Exception {
        String projectResponse = mockMvc.perform(post("/api/workspaces/{workspaceId}/projects", WORKSPACE_ID)
                        .header("X-VCore-Bff-Key", BFF_SECRET)
                        .header("X-VCore-User-Email", OWNER_EMAIL)
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
                        .header("X-VCore-User-Email", OWNER_EMAIL)
                        .header("Idempotency-Key", "set-evidence-wip")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"wipLimit":4,"expectedVersion":0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.wipLimit").value(4))
                .andExpect(jsonPath("$.version").value(1));
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
                        .header("X-VCore-User-Email", OWNER_EMAIL)
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
