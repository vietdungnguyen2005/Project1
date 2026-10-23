package dev.vcore.workspace.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vcore.identity.application.AuthenticatedUser;
import dev.vcore.shared.web.ResourceNotFoundException;
import dev.vcore.task.application.IdempotencyService;
import dev.vcore.task.application.RequestHash;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WorkflowTemplateService {
    private final JdbcClient jdbc;
    private final WorkspaceAccessService access;
    private final IdempotencyService idempotency;
    private final WorkspaceEventRecorder events;
    private final ObjectMapper mapper;

    public WorkflowTemplateService(
            JdbcClient jdbc,
            WorkspaceAccessService access,
            IdempotencyService idempotency,
            WorkspaceEventRecorder events,
            ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.access = access;
        this.idempotency = idempotency;
        this.events = events;
        this.mapper = mapper;
    }

    public List<Definition> presets(AuthenticatedUser user, UUID workspaceId) {
        access.requireMembership(user.id(), workspaceId);
        return List.of(
                new Definition(
                        "CLIENT_A_SOFTWARE",
                        1,
                        "Client A — Software delivery (synthetic)",
                        "CLIENT_A",
                        List.of(
                                new ColumnSpec("BACKLOG", "BACKLOG", 0, null),
                                        new ColumnSpec("IN_PROGRESS", "IN_PROGRESS", 1, 3),
                                new ColumnSpec("REVIEW", "IN_PROGRESS", 2, 2),
                                        new ColumnSpec("DONE", "DONE", 3, null))),
                new Definition(
                        "CLIENT_B_OPERATIONS",
                        1,
                        "Client B — Business operations (synthetic)",
                        "CLIENT_B",
                        List.of(
                                new ColumnSpec("RECEIVED", "BACKLOG", 0, null),
                                        new ColumnSpec("VALIDATION", "TODO", 1, 2),
                                new ColumnSpec("APPROVAL", "IN_PROGRESS", 2, 1),
                                        new ColumnSpec("COMPLETED", "DONE", 3, null))));
    }

    @Transactional(readOnly = true)
    public List<TemplateView> list(AuthenticatedUser user, UUID workspaceId) {
        access.requireMembership(user.id(), workspaceId);
        return jdbc.sql(
                        "SELECT id,template_key,revision,name,scenario,definition::text FROM workflow_template WHERE workspace_id=:w ORDER BY template_key,revision DESC")
                .param("w", workspaceId)
                .query((row, number) -> new TemplateView(
                        row.getObject("id", UUID.class),
                        row.getString("template_key"),
                        row.getInt("revision"),
                        row.getString("name"),
                        row.getString("scenario"),
                        true,
                        readColumns(row.getString("definition"))))
                .list();
    }

    public TemplateView get(AuthenticatedUser user, UUID workspaceId, UUID id) {
        access.requireMembership(user.id(), workspaceId);
        return resolve(workspaceId, id);
    }

    // Project provisioning checks administration rights before resolving a scoped revision.
    TemplateView resolve(UUID workspaceId, UUID id) {
        return jdbc.sql(
                        "SELECT id,template_key,revision,name,scenario,definition::text FROM workflow_template WHERE workspace_id=:w AND id=:id")
                .param("w", workspaceId)
                .param("id", id)
                .query((row, number) -> new TemplateView(
                        row.getObject("id", UUID.class),
                        row.getString("template_key"),
                        row.getInt("revision"),
                        row.getString("name"),
                        row.getString("scenario"),
                        true,
                        readColumns(row.getString("definition"))))
                .optional()
                .orElseThrow(
                        () -> new ResourceNotFoundException("The workflow template does not exist in this workspace."));
    }

    @Transactional
    public TemplateView publish(AuthenticatedUser user, UUID workspaceId, String key, Definition input) {
        access.requireAdministrationAccess(user.id(), workspaceId);
        Definition definition = validate(input);
        String hash = RequestHash.sha256("workflow-template.publish.v1|" + json(definition));
        var replay = idempotency.beginOrReplay(workspaceId, key, hash, TemplateView.class);
        if (replay.isPresent()) return replay.get();
        UUID id = UUID.randomUUID();
        jdbc.sql(
                        "INSERT INTO workflow_template(id,workspace_id,template_key,revision,name,scenario,definition,created_by) VALUES(:id,:w,:key,:revision,:name,:scenario,CAST(:definition AS jsonb),:actor)")
                .param("id", id)
                .param("w", workspaceId)
                .param("key", definition.key())
                .param("revision", definition.version())
                .param("name", definition.name())
                .param("scenario", definition.scenario())
                .param("definition", json(definition.columns()))
                .param("actor", user.id())
                .update();
        TemplateView result = new TemplateView(
                id,
                definition.key(),
                definition.version(),
                definition.name(),
                definition.scenario(),
                true,
                definition.columns());
        events.record(
                workspaceId,
                user.id(),
                "WORKFLOW_TEMPLATE",
                id,
                "WORKFLOW_TEMPLATE_PUBLISHED",
                "workflow-template.published.v1",
                Map.of(
                        "key",
                        result.key(),
                        "version",
                        result.version(),
                        "scenario",
                        result.scenario(),
                        "synthetic",
                        true),
                Instant.now());
        idempotency.complete(workspaceId, key, 201, result);
        return result;
    }

    public Definition validate(Definition input) {
        if (input == null
                || input.key() == null
                || !input.key().matches("[A-Z][A-Z0-9_]{1,39}")
                || input.version() < 1
                || input.version() > 100000
                || input.name() == null
                || input.name().isBlank()
                || input.name().length() > 120
                || !List.of("CLIENT_A", "CLIENT_B", "CUSTOM").contains(input.scenario() == null ? "" : input.scenario())
                || input.columns() == null
                || input.columns().size() < 2
                || input.columns().size() > 12
                || input.columns().stream().anyMatch(Objects::isNull))
            throw new InvalidWorkflowTemplateException(
                    "A template needs a valid key, positive version, scenario, name and 2–12 columns.");
        var columns = input.columns().stream()
                .sorted(Comparator.comparingInt(ColumnSpec::position))
                .toList();
        var names = new HashSet<String>();
        for (int i = 0; i < columns.size(); i++) {
            var c = columns.get(i);
            if (c.name() == null
                    || c.name().isBlank()
                    || c.name().length() > 80
                    || !names.add(c.name().strip().toLowerCase(Locale.ROOT))
                    || c.position() != i
                    || !List.of("BACKLOG", "TODO", "IN_PROGRESS", "DONE")
                            .contains(c.category() == null ? "" : c.category())
                    || (c.wipLimit() != null && (c.wipLimit() < 1 || c.wipLimit() > 100))
                    || ("DONE".equals(c.category()) && i != columns.size() - 1))
                throw new InvalidWorkflowTemplateException(
                        "Use unique names, contiguous positions from zero, supported categories and WIP null or 1–100; only the last column may be DONE.");
        }
        if (!List.of("BACKLOG", "TODO").contains(columns.getFirst().category())
                || !"DONE".equals(columns.getLast().category()))
            throw new InvalidWorkflowTemplateException("A workflow must start with BACKLOG/TODO and end with DONE.");
        return new Definition(
                input.key(),
                input.version(),
                input.name().strip(),
                input.scenario(),
                columns.stream()
                        .map(c -> new ColumnSpec(c.name().strip(), c.category(), c.position(), c.wipLimit()))
                        .toList());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Cannot encode workflow template", failure);
        }
    }

    private List<ColumnSpec> readColumns(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<ColumnSpec>>() {});
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Stored workflow template is invalid", failure);
        }
    }

    public record ColumnSpec(
            @NotBlank @Size(max = 80) String name,

            @NotBlank @Pattern(regexp = "BACKLOG|TODO|IN_PROGRESS|DONE") String category,

            @Min(0) int position,
            @Min(1) @Max(100) Integer wipLimit) {}

    public record Definition(
            @NotBlank @Pattern(regexp = "[A-Z][A-Z0-9_]{1,39}") String key,

            @Min(1) @Max(100000) int version,
            @NotBlank @Size(max = 120) String name,

            @NotBlank @Pattern(regexp = "CLIENT_A|CLIENT_B|CUSTOM") String scenario,

            @NotNull @Size(min = 2, max = 12) List<@NotNull @Valid ColumnSpec> columns) {}

    public record TemplateView(
            UUID id,
            String key,
            int version,
            String name,
            String scenario,
            boolean synthetic,
            List<ColumnSpec> columns) {}
}
