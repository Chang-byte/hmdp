package com.hmdp.service.impl;

import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;


    @Override
    public Result getList() {
        String key = "cache:typelist";
        // 1. 在Redis中查询
        List<String> shopTypeList = new ArrayList<>();
        shopTypeList = stringRedisTemplate.opsForList().range(key, 0, -1);
        //2. 判断是否缓存命中
        if (!shopTypeList.isEmpty()){
            //3. 中了返回
            List<ShopType> typeList = new ArrayList<>();
            for (String s : shopTypeList) {
                ShopType shopType = JSONUtil.toBean(s, ShopType.class);
                typeList.add(shopType);
            }
            return Result.ok(typeList);

        }
        //4. 没中的话在数据库中查询
        List<ShopType> typeList = query().orderByAsc("sort").list();
        //5. 不存在直接返回错误
        if (typeList.isEmpty()){
            return Result.fail("不存在分类");
        }
        //6.存入redis
        for (ShopType shopType : typeList) {
            String s = JSONUtil.toJsonStr(shopType);
            shopTypeList.add(s);
        }
        stringRedisTemplate.opsForList().rightPushAll(key, shopTypeList);
        return Result.ok(shopTypeList);
    }
}
