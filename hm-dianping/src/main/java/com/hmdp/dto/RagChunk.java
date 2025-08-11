package com.hmdp.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagChunk {
    private Long blogId;
    private String title;
    private String text; // 分块后的文本
}


