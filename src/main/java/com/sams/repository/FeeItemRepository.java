package com.sams.repository;

import com.sams.entity.FeeItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FeeItemRepository extends JpaRepository<FeeItem, Long> {
    List<FeeItem> findAllByOrderByDueDateAscIdAsc();

    List<FeeItem> findBySectionIdIsNullOrSectionIdOrderByDueDateAscIdAsc(String sectionId);
}
