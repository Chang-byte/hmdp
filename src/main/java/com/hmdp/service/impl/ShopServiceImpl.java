package com.hmdp.service.impl;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import io.lettuce.core.RedisClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;
    @Override
    public Result queryById(Long id) {
        // 缓存穿透
        Shop shop = queryWithPassThrough(id);
//        Shop shop = cacheClient
//                .queryWithPassThrough(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.MINUTES);


        // 互斥锁解决缓存击穿
//        Shop shop = queryWithMutex(id);
//        Shop shop = cacheClient.queryWithLogicalExpire(CACHE_SHOP_KEY, id, Shop.class, this::getById, CACHE_SHOP_TTL, TimeUnit.SECONDS);

        // 逻辑过期解决缓存击穿
//        Shop shop = queryWithLogicalExpire(id);
        return Result.ok(shop);
    }

    // 线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    // 逻辑过期解决缓存击穿
    private Shop queryWithLogicalExpire(Long id){
        //1. 从redis中查询商铺的缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        System.out.println("shopJson: " + shopJson);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在，直接返回
            return null;
        }
        //4. 命中，需要先把JSON反序列化为对象。
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        JSONObject data = (JSONObject) redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //5. 存在，判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())){
            //5.1 未过期，直接返回店铺信息
            return shop;
        }
        //5.2 已过期，需要进行缓存重建
        //6. 缓存重建
        //6.1 获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2 判断是否获取锁成功
        if (isLock){
            //6.3 成功，开启独立线程，实现缓存重建(阿里规定开线程必须从线程池中获取，不能new Thread！！！)
            CACHE_REBUILD_EXECUTOR.submit(()->{
                try {
                    // 重建缓存
                    this.saveShop2Redis(id, 20L);
                }catch (Exception e){
                    throw new RuntimeException(e);
                }finally {
                    unLock(key);;
                }
            });
        }
        //7. 返回
        return shop;
    }

    // 互斥锁解决缓存击穿(一个热点数据突然失效，短时间内大量请求打到数据库上造成数据库崩溃)
    private Shop queryWithMutex(Long id){
        //1. 从redis中查询商铺的缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        System.out.println("shopJson: " + shopJson);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中值是否为空值 空值解决缓存穿透问题。
        if (shopJson != null){
            // 返回一个错误信息
            return null;
        }
        //4. 实现缓存重建
        //4.1 获取互斥锁
        String lockKey = null;
        Shop shop = null;
        try {
            lockKey = LOCK_SHOP_KEY + id;
            boolean isLock = tryLock(lockKey);
            //4.2判断获取锁是否成功
            if (!isLock){
                //4.3 失败，则休眠并重试。
                Thread.sleep(50);
                // 循环进行递归
                return queryWithMutex(id);
            }
            //4. 不存在，根据id查询数据库
            shop = getById(id);
            // 模拟重建的延时
            Thread.sleep(200);
            //5. 不存在，返回错误
            if(shop == null){
                // 将空值写入Redis 缓存空值，防止缓存穿透(数据在Redis和数据库中都不存在，会返回null)
                stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);
                return null;
            }
            //6. 存在，写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //7. 释放互斥锁
            unLock(lockKey);
        }
        //7. 返回
        return shop;
    }

    // 空值解决缓存穿透
    private Shop queryWithPassThrough(Long id){
        //1. 从redis中查询商铺的缓存
        String key = CACHE_SHOP_KEY + id;
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        System.out.println("shopJson: " + shopJson);
        //2. 判断是否存在
        if (StrUtil.isNotBlank(shopJson)) {
            //3. 存在，直接返回
            return JSONUtil.toBean(shopJson, Shop.class);
        }
        // 判断命中值是否为空值 空值解决缓存穿透问题。
        if (shopJson != null){
            // 返回一个错误信息
            return null;
        }
        //4. 不存在，根据id查询数据库
        Shop shop = getById(id);
        //5. 不存在，返回错误
        if(shop == null){
        //   System.out.println("shop is null");

            // 将空值写入Redis(解决缓存穿透问题)
            stringRedisTemplate.opsForValue().set(key, "", CACHE_NULL_TTL, TimeUnit.MINUTES);

            return null;
        }
        //6. 存在，写入redis 添加过期时间，防止后面数据太多造成内存溢出
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(shop), CACHE_SHOP_TTL, TimeUnit.MINUTES);
        //7. 返回
        return shop;
    }

    // 尝试获取锁 setnx
    private boolean tryLock(String key){
        // setIfAbsent 利用redis的setnx方法来表示获取锁，该方法含义是redis中如果没有这个key，
        // 则插入成功，返回1，在stringRedisTemplate中返回true，
        // 如果有这个key则插入失败，则返回0，在stringRedisTemplate返回false，
        // 我们可以通过true，或者是false，来表示是否有线程成功插入key，
        // 成功插入的key的线程我们认为他就是获得到锁的线程。
        Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(flag);
    }

    // 释放锁
    private void unLock(String key){
        stringRedisTemplate.delete(key);
    }

    //逻辑过期 添加一个较大时间的过期字段
    public void saveShop2Redis(Long id, Long expireSeconds){
        // 1.查询店铺数据
        Shop shop = getById(id);
        // 2.封装逻辑过期时间 添加一个逻辑过期的时间的字段 线程读取过程中不需要等待，
        // 性能好，有一个额外的线程持有锁去进行重构数据，
        // 但是在重构数据完成前，其他的线程只能返回之前的数据，且实现起来麻烦
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        // 3.写入Redis
        stringRedisTemplate.opsForValue().set(CACHE_SHOP_KEY + id, JSONUtil.toJsonStr(redisData));
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        Long id = shop.getId();
        if (id == null){
            return Result.fail("店铺id不能为空");
        }
        //1. 更新数据库
        updateById(shop);
        //2. 删除缓存
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        /*
        通过之前的淘汰，我们确定了采用删除策略，来解决双写问题，
        当我们修改了数据之后，然后把缓存中的数据进行删除，
        查询时发现缓存中没有数据，则会从mysql中加载最新的数据，从而避免数据库和缓存不一致的问题
         */
        // 而后面查的时候，发现缓存中没有，则先查数据库，同时更新到缓存里面。
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 1.判断是否需要根据坐标查询
        if (x == null || y == null) {
            // 不需要坐标查询，按数据库查询
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }

        // 2.计算分页参数
        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;

        // 3.查询redis、按照距离排序、分页。结果：shopId、distance
        String key = SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo() // GEOSEARCH key BYLONLAT x y BYRADIUS 10 WITHDISTANCE
                .search(
                        key,
                        GeoReference.fromCoordinate(x, y),
                        new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end)
                );
        // 4.解析出id
        if (results == null) {
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> list = results.getContent();
        if (list.size() <= from) {
            // 没有下一页了，结束
            return Result.ok(Collections.emptyList());
        }
        // 4.1.截取 from ~ end的部分
        List<Long> ids = new ArrayList<>(list.size());
        Map<String, Distance> distanceMap = new HashMap<>(list.size());
        list.stream().skip(from).forEach(result -> {
            // 4.2.获取店铺id
            String shopIdStr = result.getContent().getName();
            ids.add(Long.valueOf(shopIdStr));
            // 4.3.获取距离
            Distance distance = result.getDistance();
            distanceMap.put(shopIdStr, distance);
        });
        // 5.根据id查询Shop
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("ORDER BY FIELD(id," + idStr + ")").list();
        for (Shop shop : shops) {
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }
        // 6.返回
        return Result.ok(shops);
    }

}
