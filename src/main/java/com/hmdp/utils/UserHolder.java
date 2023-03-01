package com.hmdp.utils;

import com.hmdp.dto.UserDTO;

public class UserHolder {
    // 保证同一个线程下只有一个，一般用于全程
    /*
        期望在controller中直接获取用户的信息，那么怎么获取，使用ThreadLocal(本地线程)
        当然我们也可以使用SecurityContextHolder 底层也是ThreadLocal来获取全局的登录的用户。
     */
    private static final ThreadLocal<UserDTO> tl = new ThreadLocal<>();

    public static void saveUser(UserDTO user){
        tl.set(user);
    }

    public static UserDTO getUser(){
        return tl.get();
    }

    public static void removeUser(){
        tl.remove();
    }
}
