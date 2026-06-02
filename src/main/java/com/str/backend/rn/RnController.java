package com.str.backend.rn;

import com.str.backend.domain.RnTrigger;
import com.str.backend.rn.dto.RnDetailDto;
import com.str.backend.rn.dto.RnResponse;
import com.str.backend.rn.dto.RnSummaryDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/rn")
public class RnController {

    private final RnService service;
    private final RnMapper mapper;

    public RnController(RnService service, RnMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    /** STR-1.5: display of inactive RNs (SUSPENDED + WITHDRAWN). */
    @GetMapping("/inactive")
    public List<RnResponse> inactive() {
        return mapper.toResponseList(service.inactive());
    }

    /** STR wireframe §12 / §13: paginated public registry of RNs. */
    @GetMapping
    public Page<RnSummaryDto> registry(
            @RequestParam(defaultValue = "ACTIVE") RnRegistryView view,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String county,
            @RequestParam(required = false) Long typeId,
            @RequestParam(required = false) String rb,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String street,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String lessor,
            @PageableDefault(size = 20, sort = "issueDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return service.searchRegistry(view, q, county, typeId, rb, city, street, name, lessor, pageable);
    }

    /** STR wireframe §12 / §13: full detail of a single RN (accommodation + lessor). */
    @GetMapping("/{rn}/detail")
    public RnDetailDto detail(@PathVariable String rn) {
        return service.detail(rn);
    }

    @GetMapping("/{rn}")
    public RnResponse get(@PathVariable String rn) {
        return mapper.toResponse(service.find(rn));
    }

    @PostMapping("/{rn}/suspend")
    public RnResponse suspend(@PathVariable String rn, @RequestParam RnTrigger reason) {
        return mapper.toResponse(service.suspend(rn, reason));
    }

    @PostMapping("/{rn}/reactivate")
    public RnResponse reactivate(@PathVariable String rn) {
        return mapper.toResponse(service.reactivate(rn));
    }

    @PostMapping("/{rn}/withdraw")
    public RnResponse withdraw(@PathVariable String rn) {
        return mapper.toResponse(service.withdraw(rn));
    }
}
