package com.str.backend.lookup;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface ObjectGroupRepository extends JpaRepository<ObjectGroupEntity, String> {

    /** Skupine objekata koje subjekt te vrste smije prijaviti. */
    @Query("SELECT g.code FROM ObjectGroupEntity g JOIN g.subjectCategories c WHERE c = :category")
    List<String> findGroupCodes(SubjectCategory category);
}
