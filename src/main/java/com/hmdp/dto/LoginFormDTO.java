package com.hmdp.dto;

import lombok.Data;

@Data
public class LoginFormDTO {
    // 登录的数据
    // DTO: 从前端接收到的数据，一般是用来请求的
    // VO: 后端进行处理后返回给前端的数据，一般是为了在某些特定场合进行的
    private String phone;
    private String code;
    private String password;
}
