package dev.vcore.task.infrastructure;

import dev.vcore.task.domain.TaskEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<TaskEntity, UUID> {

    Optional<TaskEntity> findByIdAndWorkspaceIdAndProjectId(UUID id, UUID workspaceId, UUID projectId);

    long countByWorkspaceIdAndProjectIdAndColumnId(UUID workspaceId, UUID projectId, UUID columnId);
}
