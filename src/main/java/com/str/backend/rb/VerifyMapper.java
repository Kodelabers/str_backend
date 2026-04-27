package com.str.backend.rb;

import com.str.backend.rb.dto.VerifyResponse;
import com.str.backend.sso.SsoEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface VerifyMapper {

    @Mapping(source = "rb.rb", target = "registracijskiBroj")
    @Mapping(source = "rb.status", target = "status")
    @Mapping(source = "sso.maxGostiju", target = "kapacitet")
    @Mapping(source = "sso.ponuda", target = "tipPonude")
    VerifyResponse toResponse(RbEntity rb, SsoEntity sso);
}
