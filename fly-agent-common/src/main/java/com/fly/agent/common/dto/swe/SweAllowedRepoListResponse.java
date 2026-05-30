package com.fly.agent.common.dto.swe;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Paginated repositories accepted by the SCA license gate.
 */
@Data
public class SweAllowedRepoListResponse {

    private Integer page;

    private Integer perPage;

    private Long total;

    private Integer totalPages;

    private List<SweAllowedRepoDTO> repositories = new ArrayList<>();
}
