package com.fly.agent.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Skill 实体
 */
@Data
@TableName("agent_skill")
public class SkillEntity {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * Skill 名称
     */
    private String skillName;

    /**
     * Skill 描述
     */
    private String description;

    /**
     * Skill 内容
     */
    private String skillContent;

    /**
     * 来源类型（builtin/custom）
     */
    private String sourceType;

    /**
     * 来源路径
     */
    private String sourcePath;

    /**
     * 是否为内置 Skill
     */
    private Boolean isBuiltin;

    /**
     * 版本号
     */
    private String version;

    /**
     * 状态
     */
    private String status;

    /**
     * 创建者
     */
    private String createdBy;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
