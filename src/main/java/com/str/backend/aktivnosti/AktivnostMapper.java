package com.str.backend.aktivnosti;

import com.str.backend.aktivnosti.dto.AktivnostResponse;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface AktivnostMapper {

    AktivnostResponse toResponse(SsoAktivnostEntity entity);

    List<AktivnostResponse> toResponseList(List<SsoAktivnostEntity> entities);
}
