package dev.vcore.task.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "task")
public class TaskEntity {

    @Id
    private UUID id;

    @Column(name = "workspace_id", nullable = false, updatable = false)
    private UUID workspaceId;

    @Column(name = "project_id", nullable = false, updatable = false)
    private UUID projectId;

    @Column(name = "column_id", nullable = false)
    private UUID columnId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private long position;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TaskEntity() {}

    public UUID id() {
        return id;
    }

    public UUID columnId() {
        return columnId;
    }

    public String title() {
        return title;
    }

    public long version() {
        return version;
    }

    public long position() {
        return position;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void moveTo(UUID targetColumnId, long targetPosition, UUID actorId, Instant changedAt) {
        this.columnId = targetColumnId;
        this.position = targetPosition;
        this.updatedBy = actorId;
        this.updatedAt = changedAt;
    }

    public void rename(String newTitle, UUID actorId, Instant changedAt) {
        this.title = newTitle;
        this.updatedBy = actorId;
        this.updatedAt = changedAt;
    }
}
