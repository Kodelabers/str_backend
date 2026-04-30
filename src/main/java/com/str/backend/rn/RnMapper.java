package com.str.backend.rn;

import com.str.backend.rn.dto.RnResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RnMapper {

    RnResponse toResponse(RnEntity entity);

    List<RnResponse> toResponseList(List<RnEntity> entities);
}
