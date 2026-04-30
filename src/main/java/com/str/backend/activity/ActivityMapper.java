package com.str.backend.activity;

import com.str.backend.activity.dto.ActivityResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ActivityMapper {

    ActivityResponse toResponse(AccommodationActivityEntity entity);

    List<ActivityResponse> toResponseList(List<AccommodationActivityEntity> entities);
}
