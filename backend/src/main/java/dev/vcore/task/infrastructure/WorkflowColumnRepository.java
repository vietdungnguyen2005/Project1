package dev.vcore.task.infrastructure;

import dev.vcore.task.domain.WorkflowColumnEntity;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WorkflowColumnRepository extends JpaRepository<WorkflowColumnEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT column
            FROM WorkflowColumnEntity column
            WHERE column.id = :id
              AND column.workspaceId = :workspaceId
              AND column.projectId = :projectId
            """)
    Optional<WorkflowColumnEntity> findForMove(
            @Param("id") UUID id, @Param("workspaceId") UUID workspaceId, @Param("projectId") UUID projectId);
}
