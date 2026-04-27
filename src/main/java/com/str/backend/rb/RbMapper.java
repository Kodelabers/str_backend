package com.str.backend.rb;

import com.str.backend.rb.dto.RbResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface RbMapper {

    RbResponse toResponse(RbEntity entity);

    List<RbResponse> toResponseList(List<RbEntity> entities);
}
