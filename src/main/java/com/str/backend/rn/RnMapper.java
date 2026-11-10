package com.str.backend.rn;

import com.str.backend.rn.dto.RnResponse;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RnMapper {

    @Mapping(target = "prijedlogIzSuspenzije", source = "withdrawalFromSuspended")
    RnResponse toResponse(RnEntity entity);

    List<RnResponse> toResponseList(List<RnEntity> entities);
}
