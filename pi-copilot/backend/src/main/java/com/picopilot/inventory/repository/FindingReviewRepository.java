package com.picopilot.inventory.repository;

import com.picopilot.inventory.model.FindingReview;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FindingReviewRepository extends JpaRepository<FindingReview, UUID> {
    List<FindingReview> findByFindingIdOrderByReviewedAtDesc(UUID findingId);
}