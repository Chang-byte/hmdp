package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    public Result follow(Long id, Boolean isFollow) {
        // 1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        String key = "follows:" + userId;

        // 2.判断到底是关注还是取关，根据isFollow字段
        if (isFollow){
            // 3.关注，新增数据
            Follow follow = new Follow();
            follow.setFollowUserId(id);
            follow.setUserId(userId);
            // 根据当前实体，将其添加到数据库。
            boolean success = save(follow);
            if (success){
                //把关注的用户的id，放入到Redis中的set集合中。
                // sadd userId followUserId
                stringRedisTemplate.opsForSet().add(key, id.toString());
            }
        }else{
            // 4.取关， 删除 delete from tb_follow where user_d = ? and follow_user_id = ?
            boolean success = remove(new QueryWrapper<Follow>()
                    .eq("user_id", userId)
                    .eq("follow_user_id", id)
            );
            if (success){
                // 把关注用户的id从Redis集合中删除
                stringRedisTemplate.opsForSet().remove(key, id.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long id) {
        // 1.获取当前登录的用户
        Long userId = UserHolder.getUser().getId();
        // 2.查询判断是否关注
        // select * from tb_follow where user_id = ? and follow_user_id = ?
        // 我们并不需要知道是否关注，以及关注的具体信息，所以只需要进行查询到有数据即可，使用count()
        Integer count = query()
                .eq("user_id", userId)
                .eq("follow_user_id", id)
                .count();
        // count > 0 关注了， 否则就是没有关注。
        return Result.ok(count > 0);
    }

    @Override
    public Result getCommonFollows(Long id) {
        // 1.求目标用户与当前登录用户的交集
        // 当前用户
        Long userId = UserHolder.getUser().getId();
        String key1 = "follows:" + userId;
        // 目标用户的key
        String key2 = "follows:" + id;
        // 求交集
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        // 注意这里是必须的！！
        if (intersect.isEmpty() || intersect == null){
            // 无交集,也是需要返回的。
            return Result.ok(Collections.emptyList());
        }
        // 解析id,将string 转换成为long类型
        List<Long> commonIds = intersect.stream()
                .map(Long::valueOf).collect(Collectors.toList());
        // 根据id进行批量查询,顺序不重要
        List<UserDTO> users = userService.listByIds(commonIds)
                .stream()
                .map(user ->
                        BeanUtil.copyProperties(user, UserDTO.class)
                ).collect(Collectors.toList());
        return Result.ok(users);
    }
}
