package com.picopilot.migration.repository;

import com.picopilot.migration.model.ApprovalRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ApprovalRecordRepository extends JpaRepository<ApprovalRecord, UUID> {
    List<ApprovalRecord> findByTargetTypeAndTargetId(String targetType, UUID targetId);
}