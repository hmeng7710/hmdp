package com.hmdp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("pre_summary")
public class PreSummary {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long blogId;
    private Integer chunkId;
    private String sourceHash;
    private String summary;
    private String keywords;
    private Integer tokenCost;
    private String model;
    private Integer qualityScore;
    private LocalDateTime updatedAt;
    private LocalDateTime expiresAt;
}


