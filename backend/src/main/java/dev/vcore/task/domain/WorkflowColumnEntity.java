package dev.vcore.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "workflow_column")
public class WorkflowColumnEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @Column(name = "wip_limit")
    private Integer wipLimit;

    @Version
    @Column(nullable = false)
    private long version;

    protected WorkflowColumnEntity() {}

    public UUID id() {
        return id;
    }

    public Integer wipLimit() {
        return wipLimit;
    }

    public String clientStatus() {
        if ("BACKLOG".equals(category)) {
            return "backlog";
        }
        if ("DONE".equals(category)) {
            return "done";
        }
        if ("review".equals(name.toLowerCase(Locale.ROOT))) {
            return "review";
        }
        return "in-progress";
    }
}
