package com.str.backend.guest;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface GuestRepository extends JpaRepository<GuestEntity, UUID> {

    List<GuestEntity> findByActivityId(UUID activityId);
}
