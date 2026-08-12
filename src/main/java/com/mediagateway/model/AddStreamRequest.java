package com.mediagateway.model;

import javax.validation.Valid;
import javax.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

/**
 * 添加流请求
 */
@Data
public class AddStreamRequest {
    /** 流列表 */
    @NotEmpty(message = "流列表不能为空")
    @Valid
    private List<StreamItem> streams;
}
