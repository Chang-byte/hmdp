package com.hmdp;

import cn.hutool.core.util.StrUtil;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;


import com.hmdp.service.impl.ShopServiceImpl;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;


import javax.annotation.Resource;
import javax.swing.event.ListDataListener;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;


@SpringBootTest
class HmDianPingApplicationTests {

    @Resource
    private CacheClient cacheClient;

    @Autowired
    private IShopService shopService;
    @Autowired
    private ShopServiceImpl shopServiceImpl;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedisIdWorker redisIdWorker;

    private ExecutorService es = Executors.newFixedThreadPool(500);

    @Test
    public void test() {
        Shop shop = shopService.getById(1);
        System.out.println(shop);
    }

    @Test
    void testSaveShop2Redis(){
        System.out.println(LocalDateTime.now());
        shopServiceImpl.saveShop2Redis(1L, 10L);
    }

    @Test
    void testSvaeShop(){
        Shop shop = shopService.getById(1L);
        cacheClient.setWithLogicalExpire(CACHE_SHOP_KEY + 1L, shop, 10L, TimeUnit.SECONDS );
    }

    @Test
    void testRedisIdWorker() throws InterruptedException {
//        CountDownLatch大致的原理是将任务切分为N个，
//        让N个子线程执行，并且有一个计数器也设置为N，哪个子线程完成了就N-1
        CountDownLatch latch = new CountDownLatch(300);
        Runnable task = () ->{
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));

    }

    @Test
    void testStrJoin(){
        List<Integer> list = Arrays.asList(1,2,3,4,5);
        System.out.println(list);
        String str = StrUtil.join(",", list);
        System.out.println(str);
    }

    @Test
    void loadShopData(){
        // 1.查询店铺信息。
        List<Shop> list = shopService.list();
        // 2.把店铺分组，按照typeId分组，typeID一致的放到一个集合。
        Map<Long, List<Shop>> map =
                list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        // 3.分批完成写入Redis。
        for (Map.Entry<Long, List<Shop>> entry : map.entrySet()) {
            // 3.1获取类型id
            Long typeId = entry.getKey();
            String key = "shop:geo:" + typeId;
            // 3.2获取同类型的店铺的集合
            List<Shop> value = entry.getValue();
            List<RedisGeoCommands.GeoLocation<String>> locations = new ArrayList<>(value.size());
            // 3.3写入Redis GEOADD key 经度 纬度 member
            for (Shop shop : value) {
                locations.add(new RedisGeoCommands.GeoLocation<>(
                        shop.getId().toString(),
                        new Point(shop.getX(), shop.getY())
                 ));
//                stringRedisTemplate.opsForGeo().add(key,
//                        new Point(shop.getX(), shop.getY()), shop.getId().toString());
            }
                stringRedisTemplate.opsForGeo().add(key, locations);
        }
    }

    @Test
    void testHyperLogLog(){
        String[] values = new String[1000];
        int j = 0;
        for (int i = 0; i < 1000000; i++) {
            j = i % 1000; // j: 0~999
            values[j] = "user_" + i;
            if(j == 999){
                // 发送到Redis
                stringRedisTemplate.opsForHyperLogLog().add("hl2",values);
            }
        }

        // 统计数量
        Long count = stringRedisTemplate.opsForHyperLogLog().size("hl2");
        System.out.println("count = " + count );
    }

}
