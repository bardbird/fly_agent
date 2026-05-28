package com.fly.agent.common.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Generic request body carrying a database id.
 */
@Data
public class IdRequest {

    @NotNull(message = "id不能为空")
    private Long id;
}
