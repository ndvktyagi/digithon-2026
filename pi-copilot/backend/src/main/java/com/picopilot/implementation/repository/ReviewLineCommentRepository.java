package com.picopilot.implementation.repository;

import com.picopilot.implementation.model.ReviewLineComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReviewLineCommentRepository extends JpaRepository<ReviewLineComment, UUID> {
    List<ReviewLineComment> findByRunIdOrderByPathAscLineNumberAsc(UUID runId);
}
